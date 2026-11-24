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
    fun frame(us: Long, exact: Boolean = true): Bitmap? =
        r.getFrameAtTime(us.coerceIn(0L, lastUs), option(exact))

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
    fun thumb(src: Bitmap, maxSide: Int = 220): Bitmap {
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
