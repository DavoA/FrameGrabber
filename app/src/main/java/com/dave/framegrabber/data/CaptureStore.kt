package com.dave.framegrabber.data

import android.graphics.Bitmap
import android.net.Uri
import java.io.File
import com.dave.framegrabber.core.*
import com.dave.framegrabber.export.*
import com.dave.framegrabber.batch.*
import com.dave.framegrabber.ocr.*
import com.dave.framegrabber.ui.*
import com.dave.framegrabber.ui.widget.*
import com.dave.framegrabber.R

class Capture(
    val file: File, val thumb: Bitmap, val timeUs: Long, val fmt: Fmt,
    val width: Int, val height: Int, val exact: Boolean, val sharpened: Boolean = false
) {
    var galleryUri: Uri? = null
}

/**
 * Captured frames live here (process-wide), not in the Activity, so a batch running in the
 * background service keeps adding frames even when the screen is closed and reopened.
 * Main-thread only.
 */
object CaptureStore {
    /** Frames of the open project. */
    val captures = mutableListOf<Capture>()
    /** All project file writes go through this one thread, in order. */
    val disk: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newSingleThreadExecutor()
    /** The open project ([Projects]); its frames are [captures]. */
    var project: Projects.Project? = null

    interface Listener { fun onAdded(c: Capture); fun onReplaced() {} }
    var listener: Listener? = null

    fun add(c: Capture) {
        captures.add(c)
        listener?.onAdded(c)
    }

    /**
     * A frame from the background batch: into the grid if its project is open, otherwise
     * straight into that project's saved file (so it's there when the video is opened again).
     */
    fun addTo(ctx: android.content.Context, projectId: String, ref: String, isPath: Boolean, name: String, c: Capture) {
        if (project?.id == projectId) add(c)
        else {
            val f = Projects.captureToFrame(c)
            disk.execute { Projects.appendFrame(ctx, projectId, ref, isPath, name, f) }
        }
    }

    /** Another project opened: its frames replace the grid. */
    fun replace(p: Projects.Project?, list: List<Capture>) {
        project = p
        captures.clear()
        captures.addAll(list)
        listener?.onReplaced()
    }
}
