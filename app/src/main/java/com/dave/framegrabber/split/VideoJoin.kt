package com.dave.framegrabber.split

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import com.dave.framegrabber.data.Output
import java.io.File
import java.nio.ByteBuffer

/**
 * Joins the parts of a video back into one file — the reverse of the splitter. Nothing is
 * re-encoded: the frames and sound are copied one after another (fast, same quality), each part's
 * timestamps shifted to follow the previous one. Parts must be of the same video (codec, size).
 */
object VideoJoin {

    /** Writes «name.mp4» to Movies/FrameGrabber/Telegram and returns it. [progress] 0..1. */
    fun join(ctx: Context, parts: List<Uri>, name: String, progress: (Float) -> Unit): Uri {
        require(parts.size >= 2) { "Нужно минимум 2 части" }
        val first = MediaExtractor().apply { setDataSource(ctx, parts[0], null) }
        // the tracks of part 1 decide the result: video + sound
        val formats = ArrayList<MediaFormat>()
        for (i in 0 until first.trackCount) {
            val f = first.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/") || mime.startsWith("audio/")) formats += f
        }
        first.release()
        if (formats.none { it.getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }) throw IllegalStateException("В первой части нет видео")
        val rotation = formats.firstOrNull { it.containsKey(MediaFormat.KEY_ROTATION) }?.getInteger(MediaFormat.KEY_ROTATION) ?: 0

        // same video in every part?
        parts.drop(1).forEachIndexed { pi, u ->
            val ex = MediaExtractor().apply { setDataSource(ctx, u, null) }
            try {
                formats.forEach { f ->
                    val mime = f.getString(MediaFormat.KEY_MIME)!!
                    val t = (0 until ex.trackCount).map { ex.getTrackFormat(it) }.firstOrNull { it.getString(MediaFormat.KEY_MIME) == mime }
                        ?: throw IllegalStateException("Часть ${pi + 2} отличается от первой (нет $mime) — соединить без пережатия нельзя")
                    if (mime.startsWith("video/") && (t.getInteger(MediaFormat.KEY_WIDTH) != f.getInteger(MediaFormat.KEY_WIDTH) ||
                            t.getInteger(MediaFormat.KEY_HEIGHT) != f.getInteger(MediaFormat.KEY_HEIGHT)))
                        throw IllegalStateException("Часть ${pi + 2} другого размера — соединить без пережатия нельзя")
                }
            } finally { ex.release() }
        }

        val sizes = parts.map { u -> runCatching { ctx.contentResolver.openAssetFileDescriptor(u, "r")?.use { it.length } }.getOrNull() ?: 1L }
        val totalBytes = sizes.sum().coerceAtLeast(1)
        val maxIn = formats.maxOf { if (it.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) it.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0 }
        val buf = ByteBuffer.allocateDirect(maxOf(maxIn, 8 shl 20))
        val info = MediaCodec.BufferInfo()

        fun build(muxer: MediaMuxer) {
            val outTrack = formats.map { muxer.addTrack(it) }
            if (rotation != 0) muxer.setOrientationHint(rotation)
            muxer.start()
            var offset = 0L          // where this part starts on the joined timeline
            var doneBytes = 0L
            parts.forEachIndexed { pi, u ->
                val ex = MediaExtractor().apply { setDataSource(ctx, u, null) }
                try {
                    // part track -> output track, by kind (video / audio)
                    val map = HashMap<Int, Int>()
                    for (i in 0 until ex.trackCount) {
                        val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                        val k = formats.indexOfFirst { it.getString(MediaFormat.KEY_MIME) == mime }
                        if (k >= 0 && k !in map.values) { map[i] = k; ex.selectTrack(i) }
                    }
                    var start = Long.MAX_VALUE
                    var end = 0L
                    var lastVideo = -1L; var videoStep = 33_333L
                    val partBytes = sizes[pi]
                    var read = 0L
                    while (true) {
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) break
                        val ti = ex.sampleTrackIndex
                        val k = map[ti]
                        val pts = ex.sampleTime
                        if (k != null && pts >= 0) {
                            if (start == Long.MAX_VALUE) start = pts
                            val t = pts - start + offset
                            info.set(0, n, maxOf(0L, t), if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                            muxer.writeSampleData(outTrack[k], buf, info)
                            if (formats[k].getString(MediaFormat.KEY_MIME)!!.startsWith("video/")) {
                                if (lastVideo >= 0 && pts > lastVideo) videoStep = pts - lastVideo
                                lastVideo = pts
                            }
                            if (t > end) end = t
                        }
                        read += n
                        progress(((doneBytes + read.coerceAtMost(partBytes)).toFloat() / totalBytes).coerceIn(0f, 1f))
                        ex.advance()
                    }
                    doneBytes += partBytes
                    // the next part starts one frame after this one's last
                    offset = end + videoStep
                } finally { ex.release() }
            }
            muxer.stop()
        }

        val fileName = "$name.mp4"
        return Output.write(ctx, fileName, "video/mp4", Output.Kind.VIDEO, sub = "Telegram") { t ->
            val pfd = t.fd()
            if (pfd != null) pfd.use { fd ->
                val m = MediaMuxer(fd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                try { build(m) } finally { runCatching { m.release() } }
            } else {
                // a picked folder without direct access: build in app storage, then copy
                val tmp = File(File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "export").apply { mkdirs() }, "joined.mp4")
                try {
                    val m = MediaMuxer(tmp.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    try { build(m) } finally { runCatching { m.release() } }
                    t.stream().use { o -> tmp.inputStream().use { it.copyTo(o, 1 shl 20) } }
                } finally { tmp.delete() }
            }
        }
    }
}
