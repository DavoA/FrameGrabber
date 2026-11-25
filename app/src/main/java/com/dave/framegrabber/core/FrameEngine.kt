package com.dave.framegrabber.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import wseemann.media.FFmpegMediaMetadataRetriever
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
 * Gives frames of a video. Two decoders behind one API:
 *  - Android's own (MediaMetadataRetriever): hardware decoding, fast; MP4, MOV, MKV, WebM, 3GP…
 *  - FFmpeg (FFmpegMediaMetadataRetriever): used when Android can't open the file or returns no
 *    frames from it — AVI, FLV, MPG/VOB, WMV, some TS/M2TS…
 * NOT thread-safe: use one instance per thread (the screen has one engine thread; batch opens its own).
 */
class FrameEngine(context: Context, uri: Uri) : Closeable {
    private var r: MediaMetadataRetriever? = null
    private var ff: FFmpegMediaMetadataRetriever? = null

    val durationUs: Long
    val width: Int
    val height: Int
    val fps: Double
    /** Number of frames in the video (0 = unknown). */
    val frameCount: Int
    /** Opened with FFmpeg (Android itself can't read this file): no playback, clips may not work. */
    val viaFfmpeg: Boolean

    init {
        val native = runCatching { openNative(context, uri) }.getOrNull()
        if (native != null) {
            r = native.first
            val m = native.second
            durationUs = m.durationUs; width = m.width; height = m.height; fps = m.fps; frameCount = m.frames
            viaFfmpeg = false
        } else {
            val (f, m) = openFfmpeg(context, uri)
            ff = f
            durationUs = m.durationUs; width = m.width; height = m.height; fps = m.fps; frameCount = 0
            viaFfmpeg = true
        }
    }

    private class Meta(val durationUs: Long, val width: Int, val height: Int, val fps: Double, val frames: Int)

    /** Android's decoder, or null if it can't open the file or gives no pictures from it. */
    private fun openNative(context: Context, uri: Uri): Pair<MediaMetadataRetriever, Meta>? {
        val mr = MediaMetadataRetriever()
        try {
            mr.setDataSource(context, uri)
            fun m(key: Int): String? = mr.extractMetadata(key)
            if (m(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") { mr.release(); return null }
            val dur = (m(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000L
            val w = m(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = m(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = m(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (w <= 0 || h <= 0) { mr.release(); return null }
            // some containers open but give no frames (e.g. some MPEG-TS): let FFmpeg try
            val probe = mr.getScaledFrameAtTime(min(dur / 2, 1_000_000L), MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 64, 64)
            if (probe == null) { mr.release(); return null }
            val frames = m(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toLongOrNull() ?: 0L
            val fps = when {
                frames > 0 && dur > 0 -> frames * 1_000_000.0 / dur
                else -> extractorFps(context, uri)
                    ?: m(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toDoubleOrNull()?.takeIf { it > 0 }
                    ?: 30.0
            }
            val (ww, hh) = if (rot == 90 || rot == 270) h to w else w to h
            return mr to Meta(dur, ww, hh, fps, frames.toInt())
        } catch (e: Exception) {
            runCatching { mr.release() }
            return null
        }
    }

    /**
     * Frame rate when the file doesn't say how many frames it has (MKV, WebM, TS): the declared
     * rate, or measured from the timestamps of the first frames.
     */
    private fun extractorFps(context: Context, uri: Uri): Double? {
        val ex = android.media.MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return null
            val f = ex.getTrackFormat(track)
            if (f.containsKey(android.media.MediaFormat.KEY_FRAME_RATE)) {
                val v = runCatching { f.getInteger(android.media.MediaFormat.KEY_FRAME_RATE).toDouble() }
                    .getOrElse { runCatching { f.getFloat(android.media.MediaFormat.KEY_FRAME_RATE).toDouble() }.getOrNull() }
                if (v != null && v in 1.0..480.0) return v
            }
            ex.selectTrack(track)
            val ts = ArrayList<Long>()
            while (ts.size < 60 && ex.sampleTime >= 0) { ts += ex.sampleTime; if (!ex.advance()) break }
            if (ts.size < 3) return null
            ts.sort()   // B-frames come out of order
            val d = ts.zipWithNext { a, b -> b - a }.filter { it > 0 }.sorted()
            if (d.isEmpty()) return null
            val fps = 1_000_000.0 / d[d.size / 2]
            return fps.takeIf { it in 1.0..480.0 }
        } catch (e: Exception) {
            return null
        } finally {
            ex.release()
        }
    }

    private fun openFfmpeg(context: Context, uri: Uri): Pair<FFmpegMediaMetadataRetriever, Meta> {
        val f = FFmpegMediaMetadataRetriever()
        try {
            if (uri.scheme == "file") f.setDataSource(uri.path) else f.setDataSource(context, uri)
            fun m(key: String): String? = f.extractMetadata(key)
            val dur = (m(FFmpegMediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000L
            val w = m(FFmpegMediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = m(FFmpegMediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = m(FFmpegMediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (w <= 0 || h <= 0) throw IllegalArgumentException("В файле нет видеодорожки (или такой формат не поддерживается)")
            val fps = m(FFmpegMediaMetadataRetriever.METADATA_KEY_FRAMERATE)?.toDoubleOrNull()?.takeIf { it in 1.0..480.0 } ?: 30.0
            val (ww, hh) = if (rot == 90 || rot == 270) h to w else w to h
            return f to Meta(dur, ww, hh, fps, 0)
        } catch (e: Exception) {
            runCatching { f.release() }
            if (e is IllegalArgumentException && e.message?.startsWith("В файле") == true) throw e
            throw IllegalArgumentException("Не удалось открыть: такой формат или кодек не поддерживается", e)
        }
    }

    /** Duration of one frame in microseconds. */
    val frameUs: Long get() = max(1L, (1_000_000.0 / fps).roundToInt().toLong())

    /** Last moment we can reliably grab (the very last timestamp often returns null). */
    val lastUs: Long get() = max(0L, durationUs - frameUs)

    private fun option(exact: Boolean) =
        if (exact) MediaMetadataRetriever.OPTION_CLOSEST else MediaMetadataRetriever.OPTION_CLOSEST_SYNC

    private fun ffOption(exact: Boolean) =
        if (exact) FFmpegMediaMetadataRetriever.OPTION_CLOSEST else FFmpegMediaMetadataRetriever.OPTION_CLOSEST_SYNC

    /** Full-resolution frame. exact=true decodes the precise frame (slower). */
    fun frame(us: Long, exact: Boolean = true): Bitmap? {
        val t = us.coerceIn(0L, lastUs)
        ff?.let { f ->
            return argb(runCatching { f.getFrameAtTime(t, ffOption(exact)) }.getOrNull()
                ?: if (!exact) runCatching { f.getFrameAtTime(t, ffOption(true)) }.getOrNull() else null)
        }
        val r = r ?: return null
        return if (Build.VERSION.SDK_INT >= 30) {
            // Ask explicitly for 8-bit ARGB: HDR / 10-bit videos may otherwise come back in formats
            // that JPEG/PNG encoding and Canvas can't handle.
            val p = MediaMetadataRetriever.BitmapParams().apply { preferredConfig = Bitmap.Config.ARGB_8888 }
            r.getFrameAtTime(t, option(exact), p)
        } else r.getFrameAtTime(t, option(exact))
    }

    /**
     * [count] consecutive frames starting at frame [index], decoded in one go (much faster than
     * asking for each frame separately). Full resolution — keep [count] small for big videos.
     * Android decoder only ([frameCount] > 0).
     */
    fun framesAt(index: Int, count: Int): List<Bitmap> {
        val r = r ?: error("framesAt needs Android's decoder")
        val p = MediaMetadataRetriever.BitmapParams().apply { preferredConfig = Bitmap.Config.ARGB_8888 }
        return r.getFramesAtIndex(index, count, p)
    }

    /** Downscaled frame (fits in maxW×maxH, keeps the aspect ratio) for previews / filmstrip. */
    fun scaledFrame(us: Long, maxW: Int, maxH: Int, exact: Boolean): Bitmap? {
        val t = us.coerceIn(0L, lastUs)
        ff?.let { f ->
            val k = min(maxW.toDouble() / max(1, width), maxH.toDouble() / max(1, height)).coerceAtMost(1.0)
            val w = max(2, (width * k).roundToInt()); val h = max(2, (height * k).roundToInt())
            // some files (FLV without an index) give nothing at "nearest keyframe": fall back to the
            // exact frame, and scale it here if FFmpeg's own scaling fails
            val b = runCatching { f.getScaledFrameAtTime(t, ffOption(exact), w, h) }.getOrNull()
                ?: runCatching { f.getScaledFrameAtTime(t, ffOption(true), w, h) }.getOrNull()
                ?: frame(t, exact = true)?.let { full -> Bitmap.createScaledBitmap(full, w, h, true) }
            return argb(b)
        }
        return r?.getScaledFrameAtTime(t, option(exact), maxW, maxH)
    }

    /** FFmpeg may hand back RGB_565; the rest of the app expects ARGB_8888. */
    private fun argb(b: Bitmap?): Bitmap? =
        if (b == null || b.config == Bitmap.Config.ARGB_8888) b else b.copy(Bitmap.Config.ARGB_8888, false)

    override fun close() {
        runCatching { r?.release() }
        runCatching { ff?.release() }
    }
}
