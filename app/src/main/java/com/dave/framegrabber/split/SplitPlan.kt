package com.dave.framegrabber.split

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * Splitting a video into parts of about equal SIZE (not time), each under a limit (Telegram: 2 GB),
 * without re-encoding: cuts are on keyframes, so the sizes are known before cutting — from the
 * sizes of the video and audio samples in the file.
 */
object SplitPlan {

    /** Telegram without Premium: 4000 pieces × 512 KB. */
    const val TELEGRAM_LIMIT = 2_097_152_000L
    const val TELEGRAM_PREMIUM_LIMIT = 4_194_304_000L
    /** Kept free under the limit, in case the container comes out a bit bigger than estimated. */
    private const val SAFETY = 0.99

    /** Every sample of the file (sorted by time) and where the keyframes are. */
    class Scan(
        /** Sample times, ascending (file's own time, not starting at 0 for some containers). */
        val times: LongArray,
        /** cum[i] = bytes of samples 0 until i  (size times.size + 1). */
        val cum: LongArray,
        /** Video keyframe times, ascending. */
        val keys: LongArray,
        /** First sample time: the "0:00" of the video. */
        val base: Long,
        val durationUs: Long,
        val fileBytes: Long,
    ) {
        val mediaBytes get() = cum.last()
        /** Samples before time t (index). */
        fun indexAt(t: Long): Int {
            var lo = 0; var hi = times.size
            while (lo < hi) { val m = (lo + hi) ushr 1; if (times[m] < t) lo = m + 1 else hi = m }
            return lo
        }
        fun bytesBefore(t: Long) = cum[indexAt(t)]
    }

    /**
     * One part: [fromUs, toUs) from the start of the video (for showing), and the same in the
     * file's own timestamps (for cutting: [startAbs] is a keyframe, [endAbs] the next part's).
     */
    class Part(val number: Int, val fromUs: Long, val toUs: Long, val bytes: Long, val startAbs: Long, val endAbs: Long)

    class Plan(val parts: List<Part>, val limit: Long) {
        val fits get() = parts.all { it.bytes <= limit }
    }

    /** Reads sample sizes (fast for MP4/MOV: only the index; MKV/TS read through the file). */
    fun scan(ctx: Context, uri: Uri, fileBytes: Long, progress: (Float) -> Boolean): Scan {
        val ex = MediaExtractor()
        try {
            runCatching { ex.setDataSource(ctx, uri, null) }.onFailure {
                throw IllegalStateException("Разделить этот формат без перекодирования нельзя: Android не умеет читать такой файл.")
            }
            var videoTrack = -1
            var durationUs = 0L
            var maxIn = 1 shl 20
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                if (mime.startsWith("video/") && videoTrack < 0) videoTrack = i
                if (f.containsKey(MediaFormat.KEY_DURATION)) durationUs = max(durationUs, f.getLong(MediaFormat.KEY_DURATION))
                if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) maxIn = max(maxIn, f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                ex.selectTrack(i)
            }
            if (videoTrack < 0) throw IllegalStateException("В файле нет видеодорожки")
            var times = LongArray(1 shl 16); var sizes = IntArray(1 shl 16); var n = 0
            var keys = LongArray(1 shl 12); var k = 0
            var buf: ByteBuffer? = null
            var step = 0
            while (true) {
                val track = ex.sampleTrackIndex
                if (track < 0) break
                val t = ex.sampleTime
                var size = ex.sampleSize.toInt()
                if (size < 0) {   // container doesn't say: read the sample
                    if (buf == null) buf = ByteBuffer.allocate(maxIn)
                    size = max(0, ex.readSampleData(buf!!, 0))
                }
                if (n == times.size) { times = times.copyOf(n * 2); sizes = sizes.copyOf(n * 2) }
                times[n] = t; sizes[n] = size; n++
                if (track == videoTrack && ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                    if (k == keys.size) keys = keys.copyOf(k * 2)
                    keys[k++] = t
                }
                if (++step % 2000 == 0 && durationUs > 0 && !progress((t.toFloat() / durationUs).coerceIn(0f, 1f)))
                    throw InterruptedException()
                if (!ex.advance()) break
            }
            if (n == 0) throw IllegalStateException("Не удалось прочитать видео")
            // sort samples by time (audio and video are interleaved by file position)
            val order = (0 until n).sortedBy { times[it] }
            val st = LongArray(n) { times[order[it]] }
            val cum = LongArray(n + 1)
            for (i in 0 until n) cum[i + 1] = cum[i] + sizes[order[i]]
            val ks = keys.copyOf(k).also { it.sort() }
            val base = st[0]
            if (durationUs <= 0) durationUs = st[n - 1] - base
            return Scan(st, cum, ks, base, durationUs, fileBytes)
        } finally {
            ex.release()
        }
    }

    /** Container overhead of a part: index entries for its samples + headers. */
    private fun overhead(samples: Int) = samples * 16L + 16 * 1024

    /** [n] parts of about equal size, cut at the keyframes closest to the equal-size points. */
    fun plan(s: Scan, n: Int, limit: Long): Plan {
        val bounds = ArrayList<Long>()   // file time of each part's start
        bounds += s.base
        var keyIdx = 1   // the first keyframe is where the video starts: never a cut
        for (i in 1 until n) {
            val target = s.mediaBytes * i / n
            // first keyframe after the previous cut
            while (keyIdx < s.keys.size && s.keys[keyIdx] <= bounds.last()) keyIdx++
            if (keyIdx >= s.keys.size) break
            var best = keyIdx
            var j = keyIdx
            while (j < s.keys.size) {
                val d = abs(s.bytesBefore(s.keys[j]) - target)
                if (d <= abs(s.bytesBefore(s.keys[best]) - target)) best = j else if (s.bytesBefore(s.keys[j]) > target) break
                j++
            }
            bounds += s.keys[best]
            keyIdx = best + 1
        }
        val end = s.times.last() + 1
        val parts = bounds.mapIndexed { i, from ->
            val to = if (i + 1 < bounds.size) bounds[i + 1] else end
            val a = s.indexAt(from); val b = s.indexAt(to)
            Part(i + 1, from - s.base, if (i + 1 < bounds.size) to - s.base else max(s.durationUs, end - s.base),
                s.cum[b] - s.cum[a] + overhead(b - a), from, to)
        }
        return Plan(parts, limit)
    }

    /** Fewest parts that all fit under [limit] (with a small safety margin). */
    fun auto(s: Scan, limit: Long): Plan {
        val safe = (limit * SAFETY).toLong()
        var n = max(1, ceil(s.mediaBytes.toDouble() / safe).toInt())
        while (true) {
            val p = plan(s, n, limit)
            if (p.parts.all { it.bytes <= safe }) return p
            if (p.parts.size < n || n >= 500) return p   // not enough keyframes to cut finer
            n++
        }
    }
}
