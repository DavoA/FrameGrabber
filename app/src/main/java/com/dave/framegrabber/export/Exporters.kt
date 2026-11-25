package com.dave.framegrabber.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import com.dave.framegrabber.core.*
import com.dave.framegrabber.data.*
import com.dave.framegrabber.batch.*
import com.dave.framegrabber.ocr.*
import com.dave.framegrabber.ui.*
import com.dave.framegrabber.ui.widget.*
import com.dave.framegrabber.R

object Exporters {
    /** GIF from [fromUs]..[toUs] at [fps], [width] px wide. Calls [progress] (0..1). */
    fun gif(ctx: Context, uri: Uri, fromUs: Long, toUs: Long, fps: Int, width: Int, dest: File, progress: (Float) -> Boolean) =
        BufferedOutputStream(dest.outputStream()).use { gif(ctx, uri, fromUs, toUs, fps, width, it, progress) }

    /** Writes the GIF straight into [os] (e.g. the gallery file) — no temp file. */
    fun gif(ctx: Context, uri: Uri, fromUs: Long, toUs: Long, fps: Int, width: Int, os: OutputStream, progress: (Float) -> Boolean) {
        FrameEngine(ctx, uri).use { e ->
            val w = min(width, max(e.width, 16)) and 1.inv()
            val h = (w * e.height / max(1, e.width).toFloat()).roundToInt().coerceAtLeast(2) and 1.inv()
            val step = 1_000_000L / fps
            val count = ((toUs - fromUs) / step + 1).toInt().coerceAtLeast(1)
            var frames = 0
            run {
                val g = GifEncoder(os, w, h, 1000 / fps)
                for (i in 0 until count) {
                    val t = fromUs + i * step
                    val b = e.scaledFrame(t, w, h, exact = true) ?: e.scaledFrame(t, w, h, exact = false) ?: continue
                    g.addFrame(b); frames++
                    if (!progress((i + 1f) / count)) break
                }
                g.finish()
            }
            if (frames == 0) error("Не удалось получить ни одного кадра из этого отрезка")
        }
    }

    /**
     * MP4 clip by remuxing (no re-encoding = original quality and sound). Starts at the keyframe
     * at or before [fromUs] (that's how video files work without re-encoding).
     */
    fun clip(ctx: Context, uri: Uri, fromUs: Long, toUs: Long, dest: File, progress: (Float) -> Boolean) =
        clip(ctx, uri, fromUs, toUs, { MediaMuxer(dest.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4) }, progress)

    /** [makeMuxer] lets the clip be written straight into the destination file descriptor. */
    fun clip(ctx: Context, uri: Uri, fromUs: Long, toUs: Long, makeMuxer: () -> MediaMuxer, progress: (Float) -> Boolean) {
        val ex = MediaExtractor()
        ex.setDataSource(ctx, uri, null)
        val muxer = makeMuxer()
        try {
            val map = HashMap<Int, Int>()
            var maxIn = 1 shl 20
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                if (mime.startsWith("video/") && f.containsKey(MediaFormat.KEY_ROTATION)) {
                    muxer.setOrientationHint(f.getInteger(MediaFormat.KEY_ROTATION))
                }
                if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) maxIn = max(maxIn, f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                ex.selectTrack(i)
                map[i] = muxer.addTrack(f)
            }
            require(map.isNotEmpty()) { "Нет дорожек для экспорта" }
            val active = map.keys.toMutableSet()
            muxer.start()
            ex.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val start = ex.sampleTime.coerceAtLeast(0L)
            val buf = ByteBuffer.allocate(maxIn)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val track = ex.sampleTrackIndex
                if (track < 0) break
                val t = ex.sampleTime
                if (t > toUs) {                    // this track is done; others may still have samples
                    ex.unselectTrack(track); active.remove(track)
                    if (active.isEmpty()) break else continue
                }
                if (t < start) { if (!ex.advance()) break else continue }
                info.size = ex.readSampleData(buf, 0)
                if (info.size < 0) break
                info.offset = 0
                info.presentationTimeUs = (t - start).coerceAtLeast(0L)
                info.flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                map[track]?.let { muxer.writeSampleData(it, buf, info) }
                if (!progress(((t - start).toFloat() / max(1L, toUs - start)).coerceIn(0f, 1f))) break
                if (!ex.advance()) break
            }
        } finally {
            runCatching { muxer.stop() }
            runCatching { muxer.release() }
            ex.release()
        }
    }
}
