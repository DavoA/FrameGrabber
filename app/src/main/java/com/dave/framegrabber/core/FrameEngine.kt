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
