package com.dave.framegrabber.batch

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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.dave.framegrabber.MainActivity
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import com.dave.framegrabber.core.*
import com.dave.framegrabber.data.*
import com.dave.framegrabber.export.*
import com.dave.framegrabber.ocr.*
import com.dave.framegrabber.ui.*
import com.dave.framegrabber.ui.widget.*
import com.dave.framegrabber.R

/**
 * Foreground service: the batch keeps running with a progress notification even when
 * the user leaves the app. Frames go into [CaptureStore].
 */
class BatchService : Service() {

    companion object {
        private const val CHANNEL = "batch"
        private const val NOTIF_ID = 42
        private const val DONE_ID = 43
        const val ACTION_STOP = "com.dave.framegrabber.STOP_BATCH"
    }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var nm: NotificationManager
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Пакетная съёмка", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Прогресс съёмки кадров в фоне"
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            BatchState.cancel.set(true)
            return START_NOT_STICKY
        }
        val job = BatchState.pending
        BatchState.pending = null
        val n = progressNotification(0, job?.times?.size ?: 0)
        if (Build.VERSION.SDK_INT >= 35) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else startForeground(NOTIF_ID, n)
        if (job == null) { stopSelf(); return START_NOT_STICKY }
        Thread({ run(job) }, "batch").start()
        return START_NOT_STICKY
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun progressNotification(i: Int, total: Int): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BatchService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_burst)
            .setContentTitle("Снимаю кадры…")
            .setContentText(if (total > 0) "Кадр $i из $total" else "Подготовка")
            .setProgress(total, i, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .addAction(Notification.Action.Builder(null, "Остановить", stop).build())
            .build()
    }

    private fun run(job: BatchJob) {
        val st = BatchState
        val engine = runCatching { FrameEngine(this, job.uri) }.getOrElse {
            st.firstError = it.message ?: it.javaClass.simpleName
            finish(); return
        }
        val sharpHistory = ArrayList<Double>()
        var lastHash: Long? = null
        var shotIndex = CaptureStore.captures.size
        try {
            for ((i, t0) in job.times.withIndex()) {
                if (st.cancel.get()) break
                var t = t0
                // filters work on a small probe frame (fast)
                if (job.skipBlurry || job.skipDupes) {
                    val p = FrameCapture.probe(engine, t)
                    if (p != null) {
                        val (sharp, hash) = p
                        val sorted = sharpHistory.sorted()
                        val median = if (sorted.isEmpty()) 0.0 else sorted[sorted.size / 2]
                        when {
                            job.skipBlurry && sharpHistory.size >= 3 && sharp < median * 0.4 -> {
                                st.blurry++; sharpHistory += sharp; progress(i + 1); continue
                            }
                            job.skipDupes && lastHash != null && ImageMetrics.hamming(lastHash!!, hash) <= 5 -> {
                                st.dupes++; progress(i + 1); continue
                            }
                        }
                        sharpHistory += sharp
                        lastHash = hash
                    }
                }
                if (job.sharpest) {
                    // stay inside this frame's slot so neighbours don't collapse onto the same moment
                    val gap = if (job.times.size > 1) (job.times[1] - job.times[0]) / 3 else 500_000L
                    t = FrameCapture.sharpestNear(engine, t, windowUs = gap.coerceIn(0L, 500_000L), steps = 5)
                }
                val r = runCatching {
                    val stem = Naming.stem(job.pattern, job.videoName, t, ++shotIndex)
                    val shot = FrameCapture.capture(engine, t, job.spec, job.dir, job.videoName, stem)
                    Capture(shot.file, shot.thumb, t, job.spec.format, shot.width, shot.height, shot.exact, t != t0)
                }
                r.onSuccess { c -> st.done++; ui.post { CaptureStore.add(c) } }
                    .onFailure { st.failed++; if (st.firstError == null) st.firstError = it.message ?: it.javaClass.simpleName }
                progress(i + 1)
            }
        } finally {
            engine.close()
        }
        finish()
    }

    private fun progress(i: Int) {
        BatchState.index = i
        ui.post { BatchState.listener?.onProgress() }
        val now = SystemClock.uptimeMillis()
        if (now - lastNotify > 400) {
            lastNotify = now
            nm.notify(NOTIF_ID, progressNotification(i, BatchState.total))
        }
    }

    private fun finish() {
        val st = BatchState
        val text = buildString {
            append(if (st.cancel.get()) "Остановлено: снято ${st.done}" else "Готово: снято ${st.done}")
            if (st.blurry > 0) append(", размытых пропущено ${st.blurry}")
            if (st.dupes > 0) append(", повторов пропущено ${st.dupes}")
            if (st.failed > 0) append(", ошибок ${st.failed}")
        }
        st.summary = text + (st.firstError?.let { "\n($it)" } ?: "")
        st.running = false
        ui.post {
            st.listener?.onFinished()
            // a "done" notification only matters if the user isn't looking at the app
            if (st.listener == null) {
                nm.notify(DONE_ID, Notification.Builder(this, CHANNEL)
                    .setSmallIcon(R.drawable.ic_burst)
                    .setContentTitle("Кадры готовы")
                    .setContentText(text)
                    .setAutoCancel(true)
                    .setContentIntent(openAppIntent())
                    .build())
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }
}
