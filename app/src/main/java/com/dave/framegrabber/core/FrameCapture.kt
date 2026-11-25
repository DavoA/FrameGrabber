package com.dave.framegrabber.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import java.io.Closeable
import java.io.File
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import com.dave.framegrabber.data.*
import com.dave.framegrabber.export.*
import com.dave.framegrabber.batch.*
import com.dave.framegrabber.ocr.*
import com.dave.framegrabber.ui.*
import com.dave.framegrabber.ui.widget.*
import com.dave.framegrabber.R

/** Grabs a frame, renders it to the output spec and writes it to a file. Used by single capture and batch. */
object FrameCapture {
    class Shot(val file: File, val thumb: Bitmap, val width: Int, val height: Int, val exact: Boolean)

    /** Any bitmap -> plain software ARGB_8888 (safe for Canvas and every encoder). */
    private fun normalize(b: Bitmap): Bitmap =
        if (b.config == Bitmap.Config.ARGB_8888) b
        else b.copy(Bitmap.Config.ARGB_8888, false) ?: error("Не удалось преобразовать кадр (${b.config})")

    /** A grabbed frame and how it was obtained. */
    class Grab(val bitmap: Bitmap, val method: String, val exact: Boolean)

    /**
     * Tries the precise frame, then the nearest keyframe, then a scaled decode at full size.
     * A result smaller than the video's real resolution is NOT accepted silently (some decoders hand
     * back reduced frames): the next method is tried and the largest frame wins.
     * The error lists why each attempt failed.
     */
    fun grab(e: FrameEngine, us: Long): Grab {
        val full = max(e.width, e.height)
        val attempts = listOf<Triple<String, Boolean, () -> Bitmap?>>(
            Triple("точный", true, { e.frame(us, exact = true) }),
            Triple("ключевой", false, { e.frame(us, exact = false) }),
            // bounding box = long side in both directions, so rotation can never shrink the frame
            Triple("масштаб", false, { e.scaledFrame(us, max(16, full), max(16, full), exact = false) }),
        )
        val why = mutableListOf<String>()
        var best: Grab? = null
        for ((name, exact, attempt) in attempts) {
            try {
                val b = attempt()
                if (b == null) { why += "$name: пусто"; continue }
                val g = Grab(normalize(b), name, exact)
                val long = max(g.bitmap.width, g.bitmap.height)
                if (full <= 0 || long >= full * 0.95f) return g          // full resolution: done
                why += "$name: только ${g.bitmap.width}×${g.bitmap.height}"
                if (best == null || long > max(best.bitmap.width, best.bitmap.height)) best = g
            } catch (t: Throwable) {
                why += "$name: ${t.javaClass.simpleName} ${t.message ?: ""}".trim()
            }
        }
        return best ?: error("Нет кадра на ${TimeUtil.format(us)} (${why.joinToString("; ")})")
    }

    /**
     * "Sharpest frame nearby": looks at [steps] moments within ±[windowUs] and returns the time of the
     * least blurry one. The original moment gets a small bonus so a still scene keeps the exact time.
     */
    fun sharpestNear(e: FrameEngine, us: Long, windowUs: Long = 500_000, steps: Int = 9): Long {
        val from = (us - windowUs).coerceAtLeast(0L)
        val to = (us + windowUs).coerceAtMost(e.lastUs)
        if (to <= from) return us
        val times = (0 until steps).map { from + (to - from) * it / (steps - 1) }.toMutableList()
        if (us !in times) times += us
        var bestT = us
        var best = -1.0
        for (t in times.distinct()) {
            val b = runCatching { e.scaledFrame(t, 360, 360, exact = true) }.getOrNull() ?: continue
            var score = ImageMetrics.sharpness(b)
            if (t == us) score *= 1.08
            if (score > best) { best = score; bestT = t }
        }
        return bestT
    }

    /** Small probe for batch filters: sharpness + perceptual hash. */
    fun probe(e: FrameEngine, us: Long): Pair<Double, Long>? {
        val b = runCatching { e.scaledFrame(us, 200, 200, exact = true) }.getOrNull() ?: return null
        return ImageMetrics.sharpness(b) to ImageMetrics.dHash(b)
    }

    fun capture(e: FrameEngine, us: Long, spec: OutputSpec, dir: File, baseName: String, stem: String? = null): Shot {
        val g = grab(e, us)
        val src = g.bitmap
        val out = spec.size?.let { (w, h) -> FrameRender.fit(src, w, h, transparentPad = spec.format != Fmt.JPG) } ?: src
        dir.mkdirs()
        val base = stem ?: "${baseName}_${TimeUtil.stamp(us)}"
        var file = File(dir, "$base.${spec.format.ext}")
        var n = 2
        while (file.exists()) file = File(dir, "${base}_${n++}.${spec.format.ext}")
        try {
            file.outputStream().buffered().use { FrameRender.encode(out, spec.format, spec.quality, it) }
        } catch (t: Throwable) {
            file.delete()
            throw IllegalStateException(
                "Ошибка сохранения ${out.width}×${out.height} ${out.config} → ${spec.format}: ${t.javaClass.simpleName} ${t.message ?: ""}", t
            )
        }
        if (file.length() == 0L) { file.delete(); error("Пустой файл после сохранения") }
        return Shot(file, FrameRender.thumb(out), out.width, out.height, g.exact)
    }
}
