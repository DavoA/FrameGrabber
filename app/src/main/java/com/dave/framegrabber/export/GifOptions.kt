package com.dave.framegrabber.export

import com.dave.framegrabber.core.FrameEngine
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * GIF settings from the «Клипы» tab.
 * @param fps smoothness (frames per second in the GIF)
 * @param width output width in px, 0 = original
 * @param speed playback speed (0.5 = slow motion, 2 = twice as fast)
 * @param boomerang play forward, then backward
 */
class GifOptions(
    val fps: Int,
    val width: Int,
    val quality: GifEncoder.Quality,
    val speed: Float = 1f,
    val boomerang: Boolean = false,
) {
    companion object {
        /** Frames in one GIF (keeps time and file size sane on a phone). */
        const val MAX_FRAMES = 600
    }

    /** Moments in the video to take, in order. */
    fun frameTimes(fromUs: Long, toUs: Long): List<Long> {
        val step = (1_000_000.0 * speed / fps).toLong().coerceAtLeast(1)
        val n = ((toUs - fromUs) / step + 1).toInt().coerceAtLeast(1)
        return List(n) { fromUs + it * step }
    }

    /** Frames in the finished GIF (boomerang adds the way back without repeating the ends). */
    fun outputFrames(fromUs: Long, toUs: Long): Int {
        val n = frameTimes(fromUs, toUs).size
        return if (boomerang && n > 2) n + n - 2 else n
    }

    /** GIF size in px for this video (keeps the aspect ratio). */
    fun size(e: FrameEngine): Pair<Int, Int> {
        val w = if (width <= 0) e.width else min(width, max(e.width, 16))
        val h = (w * e.height / max(1, e.width).toFloat()).roundToInt().coerceAtLeast(2)
        return max(2, w) to h
    }

    /**
     * Frame delays in 1/100 s. GIF can't store 3.33 cs, so they alternate (3, 3, 4, …) to keep
     * the exact speed instead of drifting faster or slower.
     */
    fun delaysCs(frames: Int): IntArray {
        val per = 100.0 / fps
        return IntArray(frames) { k -> ((k + 1) * per).roundToInt() - (k * per).roundToInt() }
    }
}
