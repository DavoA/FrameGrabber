package com.dave.framegrabber.tgclient

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.dave.framegrabber.MainActivity
import com.dave.framegrabber.R
import com.dave.framegrabber.data.Output
import com.dave.framegrabber.data.Prefs
import com.dave.framegrabber.player.Library
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Videos from the channel, downloaded by the app: Telegram (TDLib) fetches the file — it resumes
 * if the connection drops — then it's moved to Movies/FrameGrabber/Telegram (or the chosen folder)
 * where the gallery and the player's library see it. Runs in a foreground service.
 */
object TgDownloads {

    class Job(val key: String, val fileId: Int, val name: String, var total: Long) {
        @Volatile var done = 0L
        /** downloading -> copying -> finished | failed | cancelled */
        @Volatile var stage = "downloading"
        @Volatile var error: String? = null
        val fraction get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    }

    val jobs = CopyOnWriteArrayList<Job>()
    /** Something changed (main thread). */
    val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context

    /** Where a finished download of this post's video was saved (or null). */
    fun saved(ctx: Context, key: String): Uri? {
        val sp = ctx.getSharedPreferences("tgdl", Context.MODE_PRIVATE)
        val uri = sp.getString(key, null)?.let(Uri::parse) ?: return null
        // the file may have been deleted since (in «Плеер», the gallery, a file manager): then it's not downloaded anymore
        val now = android.os.SystemClock.uptimeMillis()
        val c = existCache[key]
        val ok = if (c != null && now - c.first < 3000) c.second else exists(ctx, uri).also { existCache[key] = now to it }
        if (!ok) { sp.edit().remove(key).apply(); existCache.remove(key); return null }
        return uri
    }

    private val existCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Boolean>>()

    private fun exists(ctx: Context, uri: Uri): Boolean =
        if (uri.scheme == "file") File(uri.path ?: "").exists()
        else runCatching { ctx.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false }.getOrDefault(false)

    fun active(key: String) = jobs.firstOrNull { it.key == key && (it.stage == "downloading" || it.stage == "copying") }

    /** [key] = chat id + message id (one video per message). */
    fun start(ctx: Context, key: String, video: TdApi.Video) {
        app = ctx.applicationContext
        if (active(key) != null) return
        val f = video.video
        val name = video.fileName.ifBlank { "telegram_video_${f.id}.mp4" }
        val job = Job(key, f.id, name, maxOf(f.size, f.expectedSize))
        jobs.removeAll { it.key == key }
        jobs += job
        changed()
        app.startForegroundService(Intent(app, TgDownloadService::class.java))
        val listener: (TdApi.File) -> Unit = { file -> onProgress(job, file) }
        Tg.onFile(f.id, listener)
        Tg.send(TdApi.DownloadFile(f.id, 32, 0, 0, false)) { r ->
            Tg.errorOf(r)?.let { err -> job.stage = "failed"; job.error = err; Tg.offFile(f.id, listener); changed() }
            (r as? TdApi.File)?.let { onProgress(job, it) }
        }
    }

    fun cancel(key: String) {
        val j = active(key) ?: return
        j.stage = "cancelled"
        Tg.send(TdApi.CancelDownloadFile(j.fileId, false)) {}
        Tg.send(TdApi.DeleteFile(j.fileId)) {}
        changed()
    }

    private fun onProgress(job: Job, f: TdApi.File) {
        if (job.stage != "downloading") return
        job.done = f.local.downloadedSize
        if (f.size > 0) job.total = f.size
        if (f.local.isDownloadingCompleted) {
            job.stage = "copying"
            changed()
            Thread { copyOut(job, f.local.path) }.start()
        } else changed()
    }

    /** From Telegram's cache to Movies/FrameGrabber/Telegram, then free the cache. */
    private fun copyOut(job: Job, path: String) {
        val r = runCatching {
            val src = File(path)
            val mime = when (src.extension.lowercase()) { "mkv" -> "video/x-matroska"; "webm" -> "video/webm"; "mov" -> "video/quicktime"; else -> "video/mp4" }
            Output.write(app, job.name, mime, Output.Kind.VIDEO, sub = "Telegram") { t ->
                t.stream().use { o -> src.inputStream().use { it.copyTo(o, 1 shl 20) } }
            }
        }
        r.onSuccess { uri ->
            app.getSharedPreferences("tgdl", Context.MODE_PRIVATE).edit().putString(job.key, uri.toString()).apply()
            job.stage = "finished"
            Tg.send(TdApi.DeleteFile(job.fileId)) {}
            // the player's library shows downloaded videos (when they go to the gallery folder)
            if (Prefs(app).folderTree == null) {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "${Prefs.ALBUM}/Telegram")
                Library(app).addFolder(dir.path)
            }
        }.onFailure { job.stage = "failed"; job.error = it.message ?: it.javaClass.simpleName }
        changed()
    }

    private fun changed() = main.post { listeners.forEach { it() }; TgDownloadService.update() }

    val running get() = jobs.any { it.stage == "downloading" || it.stage == "copying" }
}

/** Keeps downloads going with the app in the background; progress in a notification. */
class TgDownloadService : Service() {

    companion object {
        private const val CHANNEL = "tgdl"
        private const val ID = 61
        private const val ACTION_STOP = "com.dave.framegrabber.STOP_TGDL"
        @Volatile private var instance: TgDownloadService? = null
        fun update() { instance?.refresh() }
    }

    private lateinit var nm: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Загрузки из Telegram", NotificationManager.IMPORTANCE_LOW))
        instance = this
    }

    override fun onDestroy() { instance = null; super.onDestroy() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            TgDownloads.jobs.filter { it.stage == "downloading" }.forEach { TgDownloads.cancel(it.key) }
        }
        val n = build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ID, n)
        refresh()
        return START_NOT_STICKY
    }

    private var last = 0L

    fun refresh() {
        if (!TgDownloads.running) {
            val done = TgDownloads.jobs.count { it.stage == "finished" }
            stopForeground(STOP_FOREGROUND_REMOVE)
            if (done > 0) nm.notify(ID + 1, Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_send)
                .setContentTitle("Скачано видео: $done")
                .setContentText("Movies/${Prefs.ALBUM}/Telegram — видно в плеере")
                .setContentIntent(open()).setAutoCancel(true).build())
            stopSelf()
            return
        }
        val now = System.currentTimeMillis()
        if (now - last < 700) return
        last = now
        nm.notify(ID, build())
    }

    private fun build(): Notification {
        val act = TgDownloads.jobs.filter { it.stage == "downloading" || it.stage == "copying" }
        val total = act.sumOf { it.total }.coerceAtLeast(1)
        val done = act.sumOf { it.done }
        val pct = (done * 100 / total).toInt()
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_send)
            .setContentTitle(if (act.size == 1) "Скачиваю: ${act[0].name}" else "Скачиваю видео: ${act.size}")
            .setContentText("$pct% · ${mb(done)} из ${mb(total)}")
            .setProgress(100, pct, false)
            .setOngoing(true)
            .setContentIntent(open())
            .addAction(Notification.Action.Builder(null, "Отменить",
                PendingIntent.getService(this, 1, Intent(this, TgDownloadService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)).build())
            .build()
    }

    private fun mb(b: Long) = if (b >= 1_048_576_000) "%.2f ГБ".format(b / 1_048_576_000.0) else "%.0f МБ".format(b / 1_048_576.0)

    private fun open() = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
}
