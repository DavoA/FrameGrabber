package com.dave.framegrabber

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

/** Output image format. */
enum class Fmt(val ext: String, val mime: String) {
    JPG("jpg", "image/jpeg"),
    PNG("png", "image/png"),
    WEBP("webp", "image/webp"),
}

/** What the user chose in the "Размер / Формат / Качество" section. size == null -> original. */
data class OutputSpec(val size: Pair<Int, Int>?, val format: Fmt, val quality: Int)

/**
 * Wraps MediaMetadataRetriever. NOT thread-safe: use one instance per thread
 * (the activity uses a single-thread executor for its instance; batch mode opens its own).
 *
 * Unlike the website (browser decoding), this uses the phone's hardware decoders,
 * so HEVC/H.265, MKV etc. usually work too.
 */
class FrameEngine(context: Context, uri: Uri) : Closeable {
    private val r = MediaMetadataRetriever()

    val durationUs: Long
    val width: Int
    val height: Int
    val fps: Double

    init {
        r.setDataSource(context, uri)
        fun m(key: Int): String? = r.extractMetadata(key)

        if (m(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") {
            r.release()
            throw IllegalArgumentException("В файле нет видеодорожки")
        }

        durationUs = (m(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000L

        val w = m(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val h = m(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val rot = m(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        if (rot == 90 || rot == 270) { width = h; height = w } else { width = w; height = h }

        val frameCount = m(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toLongOrNull()
        fps = when {
            frameCount != null && frameCount > 0 && durationUs > 0 -> frameCount * 1_000_000.0 / durationUs
            else -> m(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toDoubleOrNull()
                ?.takeIf { it > 0 } ?: 30.0
        }
    }

    /** Duration of one frame in microseconds. */
    val frameUs: Long get() = max(1L, (1_000_000.0 / fps).roundToInt().toLong())

    /** Last moment we can reliably grab (the very last timestamp often returns null). */
    val lastUs: Long get() = max(0L, durationUs - frameUs)

    private fun option(exact: Boolean) =
        if (exact) MediaMetadataRetriever.OPTION_CLOSEST else MediaMetadataRetriever.OPTION_CLOSEST_SYNC

    /** Full-resolution frame. exact=true decodes the precise frame (slower). */
    fun frame(us: Long, exact: Boolean = true): Bitmap? {
        val t = us.coerceIn(0L, lastUs)
        return if (Build.VERSION.SDK_INT >= 30) {
            // Ask explicitly for 8-bit ARGB: HDR / 10-bit videos may otherwise come back in formats
            // that JPEG/PNG encoding and Canvas can't handle.
            val p = MediaMetadataRetriever.BitmapParams().apply { preferredConfig = Bitmap.Config.ARGB_8888 }
            r.getFrameAtTime(t, option(exact), p)
        } else r.getFrameAtTime(t, option(exact))
    }

    /** Downscaled frame for previews / filmstrip. */
    fun scaledFrame(us: Long, maxW: Int, maxH: Int, exact: Boolean): Bitmap? =
        r.getScaledFrameAtTime(us.coerceIn(0L, lastUs), option(exact), maxW, maxH)

    override fun close() {
        try { r.release() } catch (_: Exception) {}
    }
}

object FrameRender {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /**
     * Fit [src] into tw x th keeping aspect ratio, centered, with padding
     * (black for JPG, transparent for PNG/WebP) — same behavior as the website.
     */
    fun fit(src: Bitmap, tw: Int, th: Int, transparentPad: Boolean): Bitmap {
        if (src.width == tw && src.height == th) return src
        val out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        if (!transparentPad) c.drawColor(Color.BLACK)
        val s = min(tw.toFloat() / src.width, th.toFloat() / src.height)
        val dw = src.width * s
        val dh = src.height * s
        val left = (tw - dw) / 2f
        val top = (th - dh) / 2f
        c.drawBitmap(src, null, RectF(left, top, left + dw, top + dh), paint)
        return out
    }

    /** Small RGB_565 thumbnail for the captures grid (keeps memory low with hundreds of frames). */
    fun thumb(src: Bitmap, maxSide: Int = 320): Bitmap {
        val s = min(1f, maxSide.toFloat() / max(src.width, src.height))
        val tw = max(1, (src.width * s).roundToInt())
        val th = max(1, (src.height * s).roundToInt())
        val out = Bitmap.createBitmap(tw, th, Bitmap.Config.RGB_565)
        Canvas(out).drawBitmap(src, null, RectF(0f, 0f, tw.toFloat(), th.toFloat()), paint)
        return out
    }

    @Suppress("DEPRECATION")
    fun encode(bmp: Bitmap, fmt: Fmt, quality: Int, out: OutputStream) {
        val cf = when (fmt) {
            Fmt.JPG -> Bitmap.CompressFormat.JPEG
            Fmt.PNG -> Bitmap.CompressFormat.PNG
            Fmt.WEBP -> if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY
                        else Bitmap.CompressFormat.WEBP
        }
        // JPEG has no alpha: flatten onto black first
        val src = if (fmt == Fmt.JPG && bmp.hasAlpha()) {
            val b = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
            Canvas(b).apply { drawColor(Color.BLACK); drawBitmap(bmp, 0f, 0f, null) }
            b
        } else bmp
        if (!src.compress(cf, quality.coerceIn(1, 100), out)) error("Не удалось закодировать изображение")
    }
}

object TimeUtil {
    /** "12.5", "1:23.5", "1:02:03" -> microseconds; null if invalid/empty. */
    fun parse(text: String): Long? {
        val t = text.trim().replace(',', '.')
        if (t.isEmpty()) return null
        val parts = t.split(':')
        if (parts.size > 3) return null
        var sec = 0.0
        for (p in parts) {
            val v = p.toDoubleOrNull() ?: return null
            if (v < 0) return null
            sec = sec * 60 + v
        }
        return (sec * 1_000_000).toLong()
    }

    /** microseconds -> "1:23.456" or "1:02:03.456" */
    fun format(us: Long): String {
        val totalMs = us / 1000
        val ms = totalMs % 1000
        val s = (totalMs / 1000) % 60
        val m = (totalMs / 60_000) % 60
        val h = totalMs / 3_600_000
        return if (h > 0) "%d:%02d:%02d.%03d".format(h, m, s, ms)
        else "%d:%02d.%03d".format(m, s, ms)
    }

    /** Safe for file names: "00-01-23.456" */
    fun stamp(us: Long): String {
        val totalMs = us / 1000
        return "%02d-%02d-%02d.%03d".format(
            totalMs / 3_600_000, (totalMs / 60_000) % 60, (totalMs / 1000) % 60, totalMs % 1000
        )
    }
}

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
