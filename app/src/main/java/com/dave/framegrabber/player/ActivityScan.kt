package com.dave.framegrabber.player

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.dave.framegrabber.core.FrameEngine
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * "How much is going on" along a video, in [PlayerStore.BUCKETS] parts, 0…1:
 * loudness of the sound (a short piece decoded in each part) and how much the picture changes
 * between parts. Takes seconds, not minutes: only small samples are decoded.
 */
object ActivityScan {

    fun compute(ctx: Context, uri: Uri, progress: (Float) -> Boolean = { true }): FloatArray {
        val n = PlayerStore.BUCKETS
        val audio = runCatching { loudness(ctx, uri, n) { progress(it * 0.5f) } }.getOrNull()
        val visual = runCatching { change(ctx, uri, n) { progress(0.5f + it * 0.5f) } }.getOrNull()
        val a = audio?.let { normalize(it) }
        val v = visual?.let { normalize(it) }
        val out = FloatArray(n) { i ->
            when {
                a != null && v != null -> 0.65f * a[i] + 0.35f * v[i]
                a != null -> a[i]
                v != null -> v[i]
                else -> 0f
            }
        }
        // smooth a little: single spikes (a cut, a door slam) shouldn't win
        return normalize(FloatArray(n) { i -> (out[max(0, i - 1)] + 2 * out[i] + out[min(n - 1, i + 1)]) / 4f })
    }

    /** The hottest moments: start times (µs) of the top parts, skipping the first 5 % (intros). */
    fun hotMoments(act: FloatArray, durationUs: Long, top: Float = 0.2f): List<Long> {
        val n = act.size
        val from = (n * 0.05).toInt()
        val idx = (from until n).sortedByDescending { act[it] }
        val take = max(1, ((n - from) * top).toInt())
        return idx.take(take).map { durationUs * it / n }
    }

    // ================================================================ sound

    /** RMS of ~0.8 s of sound at the start of each part. */
    private fun loudness(ctx: Context, uri: Uri, n: Int, progress: (Float) -> Boolean): FloatArray? {
        val ex = MediaExtractor()
        ex.setDataSource(ctx, uri, null)
        val track = (0 until ex.trackCount).firstOrNull {
            ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: run { ex.release(); return null }
        val fmt = ex.getTrackFormat(track)
        val dur = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
        if (dur <= 0) { ex.release(); return null }
        ex.selectTrack(track)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        val out = FloatArray(n)
        try {
            codec.configure(fmt, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            for (i in 0 until n) {
                if (!progress(i.toFloat() / n)) break
                val t0 = dur * i / n
                ex.seekTo(t0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                codec.flush()
                var sum = 0.0; var count = 0L
                var inputDone = false
                var guard = 0
                while (count < 35_000 && guard++ < 400) {
                    if (!inputDone) {
                        val ib = codec.dequeueInputBuffer(5_000)
                        if (ib >= 0) {
                            val buf = codec.getInputBuffer(ib)!!
                            val sz = ex.readSampleData(buf, 0)
                            if (sz < 0 || ex.sampleTime > t0 + 1_500_000) {
                                codec.queueInputBuffer(ib, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                            } else {
                                codec.queueInputBuffer(ib, 0, sz, ex.sampleTime, 0); ex.advance()
                            }
                        }
                    }
                    val ob = codec.dequeueOutputBuffer(info, 5_000)
                    if (ob >= 0) {
                        val buf = codec.getOutputBuffer(ob)!!
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        val sb = buf.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
                        while (sb.hasRemaining()) { val s = sb.get() / 32768.0; sum += s * s; count++ }
                        codec.releaseOutputBuffer(ob, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
                out[i] = if (count > 0) sqrt(sum / count).toFloat() else 0f
            }
        } finally {
            runCatching { codec.stop() }; codec.release(); ex.release()
        }
        return out
    }

    // ================================================================ picture

    /** How different each part's picture is from the previous one (small keyframes). */
    private fun change(ctx: Context, uri: Uri, n: Int, progress: (Float) -> Boolean): FloatArray {
        val out = FloatArray(n)
        FrameEngine(ctx, uri).use { e ->
            var prev: IntArray? = null
            for (i in 0 until n) {
                if (!progress(i.toFloat() / n)) break
                val t = e.durationUs * i / n + e.durationUs / (2 * n)
                val g = e.scaledFrame(t, 48, 48, exact = false)?.let { gray(it) }
                if (g != null && prev != null && g.size == prev.size) {
                    var d = 0L
                    for (k in g.indices) d += abs(g[k] - prev[k])
                    out[i] = d.toFloat() / g.size
                }
                if (g != null) prev = g
            }
        }
        out[0] = if (n > 1) out[1] else 0f
        return out
    }

    private fun gray(b: Bitmap): IntArray {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return IntArray(px.size) { val c = px[it]; ((c shr 16 and 255) * 3 + (c shr 8 and 255) * 6 + (c and 255)) / 10 }
    }

    /** 0…1 between the 5th and the 95th percentile. */
    private fun normalize(a: FloatArray): FloatArray {
        if (a.isEmpty()) return a
        val s = a.sorted()
        val lo = s[(s.size * 0.05).toInt()]
        val hi = s[min(s.size - 1, (s.size * 0.95).toInt())]
        val span = hi - lo
        return FloatArray(a.size) { if (span <= 1e-6f) 0f else ((a[it] - lo) / span).coerceIn(0f, 1f) }
    }
}
