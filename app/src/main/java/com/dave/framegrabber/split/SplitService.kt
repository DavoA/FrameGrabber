package com.dave.framegrabber.split

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.dave.framegrabber.MainActivity
import com.dave.framegrabber.R
import com.dave.framegrabber.data.Output
import com.dave.framegrabber.export.Exporters
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** What to cut: the video, its parts, and how to name them. */
class SplitJob(val uri: Uri, val videoName: String, val parts: List<SplitPlan.Part>)

/** The running split, for the screen (listener) and the notification. */
object SplitState {
    @Volatile var running = false
    val cancel = AtomicBoolean(false)
    var part = 0; var parts = 0
    /** 0…1 inside the current part. */
    var fraction = 0f
    var saved = mutableListOf<Uri>()
    var error: String? = null
    var droppedAudio: List<String> = emptyList()
    var folder = ""

    interface Listener { fun onProgress(); fun onFinished() }
    var listener: Listener? = null

    internal var pending: SplitJob? = null

    fun start(ctx: Context, job: SplitJob) {
        pending = job
        cancel.set(false)
        running = true
        part = 0; parts = job.parts.size; fraction = 0f
        saved = mutableListOf(); error = null; droppedAudio = emptyList(); folder = ""
        val i = Intent(ctx, SplitService::class.java)
        ctx.startForegroundService(i)
    }

    fun stop() = cancel.set(true)

    /** "video (часть 2 из 5).mp4" */
    fun partName(video: String, n: Int, of: Int, ext: String) = "$video (часть $n из $of).$ext"
}

/**
 * Foreground service: cuts the parts one after another (no re-encoding) straight into the gallery
 * (Movies/FrameGrabber/<video name>/) or the chosen folder, with progress in a notification.
 */
class SplitService : Service() {

    companion object {
        private const val CHANNEL = "split"
        private const val NOTIF_ID = 52
        private const val DONE_ID = 53
        const val ACTION_STOP = "com.dave.framegrabber.STOP_SPLIT"
    }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var nm: NotificationManager
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Разделение видео", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { SplitState.stop(); return START_NOT_STICKY }
        val job = SplitState.pending
        SplitState.pending = null
        val n = notification()
        if (Build.VERSION.SDK_INT >= 35) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        else startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (job == null) { stopSelf(); return START_NOT_STICKY }
        Thread({ run(job) }, "split").start()
        return START_NOT_STICKY
    }

    private fun run(job: SplitJob) {
        val st = SplitState
        try {
            val fmt = Exporters.clipFormat(this, job.uri)
            val total = job.parts.size
            for ((i, p) in job.parts.withIndex()) {
                if (st.cancel.get()) break
                st.part = i + 1; st.fraction = 0f
                progress()
                val name = SplitState.partName(job.videoName, i + 1, total, fmt.ext)
                // up to the next part's first frame (exclusive), so nothing is doubled or lost
                val to = p.endAbs - 1
                var res: Exporters.ClipResult? = null
                val uri = Output.write(this, name, fmt.mime, Output.Kind.VIDEO, sub = job.videoName) { t ->
                    val pfd = t.fd()
                    if (pfd != null) pfd.use { fd ->
                        res = Exporters.clip(this, job.uri, p.startAbs, to, { MediaMuxer(fd.fileDescriptor, fmt.muxer) }, absolute = true) { f ->
                            st.fraction = f; progress(); !st.cancel.get()
                        }
                    } else {
                        // a picked folder without direct access: build in app storage, then copy
                        val tmp = File(File(filesDir, "export").apply { mkdirs() }, "part.${fmt.ext}")
                        try {
                            res = Exporters.clip(this, job.uri, p.startAbs, to, tmp, absolute = true) { f -> st.fraction = f; progress(); !st.cancel.get() }
                            t.stream().use { o -> tmp.inputStream().use { it.copyTo(o) } }
                        } finally { tmp.delete() }
                    }
                }
                if (st.cancel.get()) {
                    // half-written part: remove it
                    runCatching { contentResolver.delete(uri, null, null) }
                    break
                }
                st.saved += uri
                res?.droppedAudio?.takeIf { it.isNotEmpty() }?.let { st.droppedAudio = it }
            }
            st.folder = Output.describe(this) + "/" + job.videoName
        } catch (e: Throwable) {
            st.error = e.message ?: e.javaClass.simpleName
        } finally {
            st.running = false
            ui.post {
                st.listener?.onFinished()
                nm.notify(DONE_ID, Notification.Builder(this, CHANNEL)
                    .setSmallIcon(R.drawable.ic_split)
                    .setContentTitle(when {
                        st.error != null -> "Не получилось разделить"
                        st.cancel.get() -> "Разделение остановлено"
                        else -> "Готово: ${st.saved.size} частей"
                    })
                    .setContentText(st.error ?: "Сохранено в ${st.folder}")
                    .setContentIntent(openApp())
                    .setAutoCancel(true)
                    .build())
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun progress() {
        val now = System.currentTimeMillis()
        if (now - lastNotify < 400) return
        lastNotify = now
        ui.post { SplitState.listener?.onProgress(); nm.notify(NOTIF_ID, notification()) }
    }

    private fun notification(): Notification {
        val st = SplitState
        val overall = if (st.parts == 0) 0 else (((st.part - 1).coerceAtLeast(0) + st.fraction) * 100 / st.parts).toInt()
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_split)
            .setContentTitle("Разделяю видео")
            .setContentText(if (st.parts == 0) "Подготовка" else "Часть ${st.part} из ${st.parts} · $overall%")
            .setProgress(100, overall, st.parts == 0)
            .setOngoing(true)
            .setContentIntent(openApp())
            .addAction(Notification.Action.Builder(null, "Стоп",
                PendingIntent.getService(this, 1, Intent(this, SplitService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)).build())
            .build()
    }

    private fun openApp() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
}
