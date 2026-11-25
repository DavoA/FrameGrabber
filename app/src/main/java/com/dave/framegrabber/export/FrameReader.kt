package com.dave.framegrabber.export

import android.graphics.Bitmap
import com.dave.framegrabber.core.FrameEngine
import kotlin.math.roundToInt

/**
 * Reads many frames fast: consecutive frames are decoded in one run (the video is decoded once,
 * front to back) instead of seeking for every single frame. Falls back to one-by-one if the phone
 * can't do runs for this video.
 */
object FrameReader {
    /** Memory for one run of full-size frames. */
    private const val RUN_BYTES = 120L * 1024 * 1024

    /**
     * Calls [onFrame] with (index in [times], frame scaled to [w]×[h]) in order.
     * [onFrame] owns the bitmap and returns false to stop.
     */
    fun forEach(e: FrameEngine, times: List<Long>, w: Int, h: Int, onFrame: (Int, Bitmap) -> Boolean) {
        if (times.isEmpty()) return
        if (e.frameCount <= 0) { oneByOne(e, times, 0, w, h, onFrame); return }

        val frameBytes = e.width.toLong() * e.height * 4
        val maxRun = (RUN_BYTES / frameBytes).toInt().coerceIn(1, 60)
        val idx = times.map { (it * e.fps / 1_000_000.0).roundToInt().coerceIn(0, e.frameCount - 1) }
        var i = 0
        while (i < times.size) {
            // one run: from this frame up to maxRun source frames ahead
            val start = idx[i]
            var j = i
            while (j + 1 < times.size && idx[j + 1] - start < maxRun) j++
            val run = runCatching { e.framesAt(start, idx[j] - start + 1) }.getOrNull()
            if (run == null || run.isEmpty()) { oneByOne(e, times, i, w, h, onFrame); return }
            try {
                for (k in i..j) {
                    val src = run.getOrNull(idx[k] - start) ?: continue
                    val b = Bitmap.createScaledBitmap(src, w, h, true)
                    val copy = if (b === src) src.copy(Bitmap.Config.ARGB_8888, false) else b
                    if (!onFrame(k, copy)) return
                }
            } finally {
                run.forEach { it.recycle() }
            }
            i = j + 1
        }
    }

    private fun oneByOne(e: FrameEngine, times: List<Long>, from: Int, w: Int, h: Int, onFrame: (Int, Bitmap) -> Boolean) {
        for (k in from until times.size) {
            val b = e.scaledFrame(times[k], w, h, exact = true) ?: e.scaledFrame(times[k], w, h, exact = false) ?: continue
            if (!onFrame(k, b)) return
        }
    }
}
