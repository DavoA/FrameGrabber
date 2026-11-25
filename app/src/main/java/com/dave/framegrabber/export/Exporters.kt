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
    /** GIF into a file (used by tests). */
    fun gif(ctx: Context, uri: Uri, fromUs: Long, toUs: Long, opts: GifOptions, dest: File, progress: (Float) -> Boolean) =
        BufferedOutputStream(dest.outputStream()).use { gif(ctx, uri, fromUs, toUs, opts, it, progress) }

    /**
     * GIF from [fromUs]..[toUs], written straight into [os] (e.g. the gallery file).
     * Frames are read in runs (fast even at 30 fps); [progress] gets 0..1 and can return false to stop.
     */
    fun gif(ctx: Context, uri: Uri, fromUs: Long, toUs: Long, opts: GifOptions, os: OutputStream, progress: (Float) -> Boolean) {
        FrameEngine(ctx, uri).use { e ->
            val (w, h) = opts.size(e)
            val times = opts.frameTimes(fromUs, toUs)
            val total = opts.outputFrames(fromUs, toUs)
            require(total <= GifOptions.MAX_FRAMES) { "Слишком много кадров: $total (максимум ${GifOptions.MAX_FRAMES})" }
            val g = GifEncoder(os, w, h, opts.quality)
            val delays = opts.delaysCs(total)
            var written = 0
            // keep small frames for the way back (boomerang) if they fit in memory
            val keep = opts.boomerang && w.toLong() * h * 4 * times.size < 160L * 1024 * 1024
            val kept = ArrayList<Bitmap>()

            fun put(b: Bitmap): Boolean {
                g.addFrame(b, delays[written]); written++
                return progress(written.toFloat() / total)
            }

            FrameReader.forEach(e, times, w, h) { _, b ->
                val go = put(b)
                if (keep) kept += b else b.recycle()
                go
            }
            if (opts.boomerang && times.size > 2 && written < total) {
                val back = times.subList(1, times.size - 1).reversed()
                if (keep) {
                    if (kept.size > 2) for (b in kept.subList(1, kept.size - 1).reversed()) if (!put(b)) break
                } else {
                    for (t in back) {
                        val b = e.scaledFrame(t, w, h, exact = true) ?: continue
                        val go = put(b); b.recycle()
                        if (!go) break
                    }
                }
            }
            kept.forEach { it.recycle() }
            g.finish()
            if (written == 0) error("Не удалось получить ни одного кадра из этого отрезка")
        }
    }

    /**
     * MP4 clip by remuxing (no re-encoding = original quality and sound). Starts at the keyframe
     * at or before [fromUs] (that's how video files work without re-encoding).
     */
    /** Clip container: WebM for VP8/VP9 video (MP4 can't hold them), MP4 for everything else. */
    class ClipFormat(val ext: String, val mime: String, val muxer: Int)

    /** Which container the clip will be; throws a clear error if Android can't read the file. */
    fun clipFormat(ctx: Context, uri: Uri): ClipFormat {
        val ex = MediaExtractor()
        try {
            runCatching { ex.setDataSource(ctx, uri, null) }.onFailure {
                throw IllegalStateException("Клип из этого формата сделать нельзя: Android не умеет читать такой файл. GIF и кадры работают.")
            }
            val video = (0 until ex.trackCount).map { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME) ?: "" }
                .firstOrNull { it.startsWith("video/") }
                ?: throw IllegalStateException("Клип из этого файла сделать нельзя: Android не видит в нём видеодорожку. GIF и кадры работают.")
            return if (video == MediaFormat.MIMETYPE_VIDEO_VP8 || video == MediaFormat.MIMETYPE_VIDEO_VP9)
                ClipFormat("webm", "video/webm", MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM)
            else ClipFormat("mp4", "video/mp4", MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } finally { ex.release() }
    }

    /** What happened to the sound. */
    class ClipResult(val droppedAudio: List<String>, val samples: Int = 0, val firstUs: Long = 0, val lastUs: Long = 0) {
        override fun toString() = "dropped=$droppedAudio samples=$samples first=$firstUs last=$lastUs"
    }

    fun clip(ctx: Context, uri: Uri, fromUs: Long, toUs: Long, dest: File, progress: (Float) -> Boolean): ClipResult {
        val fmt = clipFormat(ctx, uri)
        return clip(ctx, uri, fromUs, toUs, { MediaMuxer(dest.path, fmt.muxer) }, progress)
    }

    /**
     * Cuts without re-encoding. [makeMuxer] lets the clip be written straight into the destination
     * file descriptor. Audio the container can't hold (MP3, AC3, Vorbis in MP4…) is left out, see
     * [ClipResult.droppedAudio].
     */
    fun clip(ctx: Context, uri: Uri, fromUs: Long, toUs: Long, makeMuxer: () -> MediaMuxer, progress: (Float) -> Boolean): ClipResult {
        val ex = MediaExtractor()
        ex.setDataSource(ctx, uri, null)
        val muxer = makeMuxer()
        val dropped = mutableListOf<String>()
        var samples = 0; var first = -1L; var last = -1L
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
                val added = runCatching { muxer.addTrack(f) }
                if (added.isFailure) {
                    if (mime.startsWith("audio/")) { dropped += mime.removePrefix("audio/"); continue }
                    throw IllegalStateException("Видео в кодеке ${mime.removePrefix("video/")} нельзя сохранить клипом без перекодирования. GIF и кадры работают.")
                }
                val out = added.getOrThrow()
                if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) maxIn = max(maxIn, f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                ex.selectTrack(i)
                map[i] = out
            }
            require(map.isNotEmpty()) { "Нет дорожек для экспорта" }
            val active = map.keys.toMutableSet()
            muxer.start()
            // Some containers (MPEG-TS) don't start at 0: the times on screen count from the first frame
            ex.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val base = ex.sampleTime.coerceAtLeast(0L)
            val from = base + fromUs
            val endUs = base + toUs
            ex.seekTo(from, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            if (ex.sampleTime < 0 || ex.sampleTime > from) {
                // seeking doesn't work in this file: start from the beginning (the clip starts earlier)
                ex.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }
            val start = ex.sampleTime.coerceAtLeast(0L)
            val buf = ByteBuffer.allocate(maxIn)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val track = ex.sampleTrackIndex
                if (track < 0) break
                val t = ex.sampleTime
                if (t > endUs) {                    // this track is done; others may still have samples
                    ex.unselectTrack(track); active.remove(track)
                    if (active.isEmpty()) break else continue
                }
                if (t < start) { if (!ex.advance()) break else continue }
                info.size = ex.readSampleData(buf, 0)
                if (info.size < 0) break
                info.offset = 0
                info.presentationTimeUs = (t - start).coerceAtLeast(0L)
                info.flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                map[track]?.let { muxer.writeSampleData(it, buf, info); samples++; if (first < 0) first = t; last = t }
                if (!progress(((t - start).toFloat() / max(1L, endUs - start)).coerceIn(0f, 1f))) break
                if (!ex.advance()) break
            }
        } finally {
            runCatching { muxer.stop() }
            runCatching { muxer.release() }
            ex.release()
        }
        return ClipResult(dropped, samples, first, last)
    }
}
