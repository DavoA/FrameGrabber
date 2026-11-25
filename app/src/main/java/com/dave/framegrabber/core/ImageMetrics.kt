package com.dave.framegrabber.core

import android.graphics.Bitmap
import kotlin.math.max
import com.dave.framegrabber.data.*
import com.dave.framegrabber.export.*
import com.dave.framegrabber.batch.*
import com.dave.framegrabber.ocr.*
import com.dave.framegrabber.ui.*
import com.dave.framegrabber.ui.widget.*
import com.dave.framegrabber.R

/** Cheap image measurements for "sharpest frame" and "skip blurry / duplicate" features. */
object ImageMetrics {

    /** Grayscale luminance of a (small) bitmap. */
    private fun gray(b: Bitmap): Triple<IntArray, Int, Int> {
        val w = b.width; val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            px[i] = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
        }
        return Triple(px, w, h)
    }

    /**
     * Sharpness = variance of the Laplacian. Blurry frames have few strong edges -> low value.
     * Only comparable between frames of the same video at the same size.
     */
    fun sharpness(b: Bitmap): Double {
        val (g, w, h) = gray(b)
        if (w < 3 || h < 3) return 0.0
        var sum = 0.0; var sum2 = 0.0; var n = 0
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                val lap = (4 * g[i] - g[i - 1] - g[i + 1] - g[i - w] - g[i + w]).toDouble()
                sum += lap; sum2 += lap * lap; n++
            }
        }
        val mean = sum / n
        return sum2 / n - mean * mean
    }

    /** 64-bit difference hash: near-identical frames have a small Hamming distance. */
    fun dHash(b: Bitmap): Long {
        val s = Bitmap.createScaledBitmap(b, 9, 8, true)
        val (g, _, _) = gray(s)
        var h = 0L
        var bit = 0
        for (y in 0 until 8) for (x in 0 until 8) {
            if (g[y * 9 + x] > g[y * 9 + x + 1]) h = h or (1L shl bit)
            bit++
        }
        return h
    }

    fun hamming(a: Long, b: Long) = java.lang.Long.bitCount(a xor b)

    /** Downscale for measuring (keeps it fast and comparable). */
    fun small(b: Bitmap, side: Int = 320): Bitmap {
        val s = side.toFloat() / max(b.width, b.height)
        if (s >= 1f) return b
        return Bitmap.createScaledBitmap(b, max(1, (b.width * s).toInt()), max(1, (b.height * s).toInt()), true)
    }
}
