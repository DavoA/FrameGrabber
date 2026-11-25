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
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import com.dave.framegrabber.core.*
import com.dave.framegrabber.data.*
import com.dave.framegrabber.export.*
import com.dave.framegrabber.ocr.*
import com.dave.framegrabber.ui.*
import com.dave.framegrabber.ui.widget.*
import com.dave.framegrabber.R

/** One video of a batch; its frames go into its project ([Projects]). */
class BatchVideo(
    val uri: Uri, val name: String,
    /** Exact moments (one video, «Отрезок» applied); null = use [BatchJob.rule] over the whole video. */
    val times: List<Long>?,
) {
    val ref: String; val isPath: Boolean
    init { Projects.refOf(uri).let { ref = it.first; isPath = it.second } }
    val projectId get() = Projects.idOf(ref)
}

/** "Every N seconds" or "N frames evenly" between [from] and [to]. */
class BatchRule(val byCount: Boolean, val value: Double) {
    companion object { const val MAX = 2000 }

    /** Frame moments, or an error message. */
    fun times(from: Long, to: Long, frameUs: Long): Result<List<Long>> = runCatching {
        require(to >= from) { "Конец отрезка должен быть позже начала" }
        if (!byCount) {
            require(value > 0) { "Укажи интервал больше 0" }
            val step = (value * 1_000_000).toLong().coerceAtLeast(frameUs)
            val count = (to - from) / step + 1
            require(count <= MAX) { "Получится $count кадров — максимум $MAX. Увеличь интервал." }
            (0 until count).map { from + it * step }
        } else {
            val n = value.toInt()
            require(n in 1..MAX) { "Количество: от 1 до $MAX" }
            if (n == 1) listOf(from + (to - from) / 2)
            else (0 until n).map { from + (to - from) * it / (n - 1) }
        }
    }
}

/** Everything a batch needs; built by the screen, run by [BatchService]. */
class BatchJob(
    val videos: List<BatchVideo>, val rule: BatchRule, val spec: OutputSpec,
    val pattern: String, val skipBlurry: Boolean, val skipDupes: Boolean, val sharpest: Boolean,
    /** Several videos: also save every frame to the gallery, a folder per video. */
    val toGallery: Boolean,
)

/** Live state of the running batch, observed by the screen (main thread). */
object BatchState {
    @Volatile var running = false
    val cancel = AtomicBoolean(false)
    var total = 0; var index = 0; var done = 0; var blurry = 0; var dupes = 0; var failed = 0
    /** Several videos: which one is being processed (1-based) and its name. */
    var video = 0; var videos = 0; var videoName = ""
    var firstError: String? = null
    var summary: String? = null

    interface Listener { fun onProgress(); fun onFinished() }
    var listener: Listener? = null

    internal var pending: BatchJob? = null

    fun start(ctx: Context, job: BatchJob) {
        pending = job
        cancel.set(false)
        running = true
        total = job.videos.singleOrNull()?.times?.size ?: 0
        index = 0; done = 0; blurry = 0; dupes = 0; failed = 0
        video = 0; videos = job.videos.size; videoName = ""
        firstError = null; summary = null
        val i = Intent(ctx, BatchService::class.java)
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
    }

    fun stop(ctx: Context) {
        cancel.set(true)
    }
}
