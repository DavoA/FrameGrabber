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

/** Everything a batch needs; built by the screen, run by [BatchService]. */
class BatchJob(
    val uri: Uri, val videoName: String, val times: List<Long>, val spec: OutputSpec,
    val dir: File, val pattern: String, val skipBlurry: Boolean, val skipDupes: Boolean,
    val sharpest: Boolean
)

/** Live state of the running batch, observed by the screen (main thread). */
object BatchState {
    @Volatile var running = false
    val cancel = AtomicBoolean(false)
    var total = 0; var index = 0; var done = 0; var blurry = 0; var dupes = 0; var failed = 0
    var firstError: String? = null
    var summary: String? = null

    interface Listener { fun onProgress(); fun onFinished() }
    var listener: Listener? = null

    internal var pending: BatchJob? = null

    fun start(ctx: Context, job: BatchJob) {
        pending = job
        cancel.set(false)
        running = true
        total = job.times.size; index = 0; done = 0; blurry = 0; dupes = 0; failed = 0
        firstError = null; summary = null
        val i = Intent(ctx, BatchService::class.java)
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
    }

    fun stop(ctx: Context) {
        cancel.set(true)
    }
}
