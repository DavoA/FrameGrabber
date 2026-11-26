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

    /** Two at a time; the rest wait in the queue. */
    private const val MAX_ACTIVE = 2

    class Job(val key: String, val fileId: Int, val name: String, var total: Long, val video: TdApi.Video? = null) {
        @Volatile var done = 0L
        /** queued -> downloading (<-> paused) -> copying -> finished | failed | cancelled; joins: joining -> finished */
        @Volatile var stage = "queued"
        @Volatile var error: String? = null
        @Volatile var uri: Uri? = null
        internal var listener: ((TdApi.File) -> Unit)? = null
        val fraction get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
        val isJoin get() = key.startsWith("join:")
    }

    /** «Download all parts and join them»: what to join once every part is saved. */
    private class JoinReq(val postKey: String, val keys: List<String>, val name: String)
    private val joins = CopyOnWriteArrayList<JoinReq>()

    val jobs = CopyOnWriteArrayList<Job>()
    /** Something changed (main thread). */
    val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context

    private val LIVE = setOf("queued", "downloading", "paused", "copying", "joining")

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

    /** The whole video joined from a post's parts (or null). */
    fun joined(ctx: Context, postKey: String) = saved(ctx, "join:$postKey")

    private val existCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Boolean>>()

    private fun exists(ctx: Context, uri: Uri): Boolean =
        if (uri.scheme == "file") File(uri.path ?: "").exists()
        else runCatching { ctx.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false }.getOrDefault(false)

    fun active(key: String) = jobs.firstOrNull { it.key == key && it.stage in LIVE }

    /** [key] = chat id + message id (one video per message). Goes to the queue. */
    fun start(ctx: Context, key: String, video: TdApi.Video) {
        app = ctx.applicationContext
        if (active(key) != null || saved(ctx, key) != null) return
        val f = video.video
        val name = video.fileName.ifBlank { "telegram_video_${f.id}.mp4" }
        jobs.removeAll { it.key == key }
        jobs += Job(key, f.id, name, maxOf(f.size, f.expectedSize), video)
        pump()
    }

    /**
     * Every part of a post; with [joinAs] they are joined into one video once all are saved
     * (parts already downloaded aren't downloaded again).
     */
    fun startAll(ctx: Context, postKey: String, parts: List<Pair<String, TdApi.Video>>, joinAs: String?) {
        app = ctx.applicationContext
        if (joinAs != null) {
            joins.removeAll { it.postKey == postKey }
            joins += JoinReq(postKey, parts.map { it.first }, joinAs)
        }
        parts.forEach { (k, v) -> start(ctx, k, v) }
        checkJoins()
        changed()
    }

    /** Joins parts that are already downloaded. */
    fun join(ctx: Context, postKey: String, keys: List<String>, name: String) {
        app = ctx.applicationContext
        joins.removeAll { it.postKey == postKey }
        joins += JoinReq(postKey, keys, name)
        checkJoins()
    }

    fun pause(key: String) {
        val j = jobs.firstOrNull { it.key == key } ?: return
        if (j.stage == "downloading") Tg.send(TdApi.CancelDownloadFile(j.fileId, false)) {}
        if (j.stage == "downloading" || j.stage == "queued") j.stage = "paused"
        pump()
    }

    fun resume(key: String) {
        val j = jobs.firstOrNull { it.key == key && it.stage == "paused" } ?: return
        j.stage = "queued"
        pump()
    }

    fun cancel(key: String) {
        val j = active(key) ?: return
        if (j.isJoin) return
        j.stage = "cancelled"
        Tg.send(TdApi.CancelDownloadFile(j.fileId, false)) {}
        Tg.send(TdApi.DeleteFile(j.fileId)) {}
        joins.removeAll { key in it.keys }
        pump()
    }

    /** Removes the downloaded parts of a post (after joining). */
    fun deleteParts(ctx: Context, keys: List<String>) {
        val sp = ctx.getSharedPreferences("tgdl", Context.MODE_PRIVATE)
        keys.forEach { k ->
            sp.getString(k, null)?.let { u -> runCatching { ctx.contentResolver.delete(Uri.parse(u), null, null) } }
            sp.edit().remove(k).apply(); existCache.remove(k)
        }
        changed()
    }

    /** Starts queued downloads while fewer than [MAX_ACTIVE] are running. */
    private fun pump() {
        val running = jobs.count { it.stage == "downloading" }
        jobs.filter { it.stage == "queued" }.take((MAX_ACTIVE - running).coerceAtLeast(0)).forEach { launch(it) }
        changed()
        if (this.running) app.startForegroundService(Intent(app, TgDownloadService::class.java))
    }

    private fun launch(job: Job) {
        job.stage = "downloading"
        if (job.listener == null) {
            val l: (TdApi.File) -> Unit = { file -> onProgress(job, file) }
            job.listener = l
            Tg.onFile(job.fileId, l)
        }
        // resumes from what was already downloaded
        Tg.send(TdApi.DownloadFile(job.fileId, 32, 0, 0, false)) { r ->
            Tg.errorOf(r)?.let { err -> job.stage = "failed"; job.error = err; pump() }
            (r as? TdApi.File)?.let { onProgress(job, it) }
        }
    }

    private fun onProgress(job: Job, f: TdApi.File) {
        if (job.stage != "downloading") return
        job.done = f.local.downloadedSize
        if (f.size > 0) job.total = f.size
        if (f.local.isDownloadingCompleted) {
            job.stage = "copying"
            changed()
            Thread { copyOut(job, f.local.path) }.start()
            pump()
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
            existCache.remove(job.key)
            job.uri = uri
            job.stage = "finished"
            Tg.send(TdApi.DeleteFile(job.fileId)) {}
            addLibraryFolder()
        }.onFailure { job.stage = "failed"; job.error = it.message ?: it.javaClass.simpleName }
        checkJoins()
        changed()
    }

    /** The player's library shows downloaded / joined videos (when they go to the gallery folder). */
    private fun addLibraryFolder() {
        if (Prefs(app).folderTree == null) {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "${Prefs.ALBUM}/Telegram")
            Library(app).addFolder(dir.path)
        }
    }

    /** A post whose parts are all saved now: join them (in order) into one video. */
    private fun checkJoins() {
        for (j in joins) {
            val uris = j.keys.map { saved(app, it) }
            if (uris.any { it == null }) continue
            joins.remove(j)
            val key = "join:${j.postKey}"
            if (active(key) != null) continue
            val total = uris.sumOf { u -> runCatching { app.contentResolver.openAssetFileDescriptor(u!!, "r")?.use { it.length } }.getOrNull() ?: 0L }
            val job = Job(key, 0, j.name, total).apply { stage = "joining" }
            jobs.removeAll { it.key == key }
            jobs += job
            changed()
            app.startForegroundService(Intent(app, TgDownloadService::class.java))
            Thread({
                val r = runCatching {
                    var lastPct = -1
                    com.dave.framegrabber.split.VideoJoin.join(app, uris.map { it!! }, j.name) { f ->
                        job.done = (f * total).toLong()
                        val p = (f * 100).toInt()
                        if (p != lastPct) { lastPct = p; changed() }
                    }
                }
                r.onSuccess { uri ->
                    app.getSharedPreferences("tgdl", Context.MODE_PRIVATE).edit().putString(key, uri.toString()).apply()
                    existCache.remove(key)
                    job.uri = uri; job.stage = "finished"
                    addLibraryFolder()
                }.onFailure { job.stage = "failed"; job.error = it.message ?: it.javaClass.simpleName }
                changed()
            }, "join").start()
        }
    }

    private fun changed() = main.post { listeners.forEach { it() }; TgDownloadService.update() }

    val running get() = jobs.any { it.stage == "queued" || it.stage == "downloading" || it.stage == "copying" || it.stage == "joining" }
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
            TgDownloads.jobs.filter { it.stage == "downloading" || it.stage == "queued" }.forEach { TgDownloads.cancel(it.key) }
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
                .setContentTitle(if (TgDownloads.jobs.any { it.isJoin && it.stage == "finished" }) "Готово: скачано и соединено" else "Скачано видео: $done")
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
        val act = TgDownloads.jobs.filter { it.stage == "downloading" || it.stage == "copying" || it.stage == "joining" }
        val queued = TgDownloads.jobs.count { it.stage == "queued" }
        val total = act.sumOf { it.total }.coerceAtLeast(1)
        val done = act.sumOf { it.done }
        val pct = (done * 100 / total).toInt()
        val joining = act.firstOrNull { it.stage == "joining" }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_send)
            .setContentTitle(when {
                joining != null -> "Соединяю части: ${joining.name}"
                act.size == 1 -> "Скачиваю: ${act[0].name}"
                else -> "Скачиваю видео: ${act.size}"
            })
            .setContentText("$pct% · ${mb(done)} из ${mb(total)}" + if (queued > 0) " · в очереди $queued" else "")
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
