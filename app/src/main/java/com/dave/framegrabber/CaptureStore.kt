package com.dave.framegrabber

import android.graphics.Bitmap
import android.net.Uri
import java.io.File

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
    val captures = mutableListOf<Capture>()

    interface Listener { fun onAdded(c: Capture) }
    var listener: Listener? = null

    fun add(c: Capture) {
        captures.add(c)
        listener?.onAdded(c)
    }
}
