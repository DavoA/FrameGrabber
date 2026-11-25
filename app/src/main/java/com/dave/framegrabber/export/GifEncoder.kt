package com.dave.framegrabber.export

import android.graphics.Bitmap
import java.io.OutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Animated GIF encoder (GIF89a, loops forever).
 *
 * Every frame gets its own 255-colour palette (+1 transparent index):
 *  - [Quality.NORMAL]: palette by popularity, no dithering — fastest, smallest, may show bands.
 *  - [Quality.HIGH]:   median-cut palette + Floyd–Steinberg dithering — smooth gradients.
 *  - [Quality.MAX]:    like HIGH with full-strength dithering and only exactly-unchanged pixels reused.
 *
 * Pixels that look the same as what's already on screen are written as "transparent", so the
 * previous frame shows through — that keeps high-fps GIFs from getting huge.
 */
class GifEncoder(
    private val out: OutputStream,
    private val w: Int,
    private val h: Int,
    private val quality: Quality = Quality.HIGH,
) {
    enum class Quality { NORMAL, HIGH, MAX }

    private companion object {
        const val TRANSPARENT = 255
        const val COLORS = 255
        const val BINS = 32768
    }

    /** What the viewer currently shows (0xRRGGBB), -1 = nothing yet. */
    private val shown = IntArray(w * h) { -1 }
    private val px = IntArray(w * h)
    private val idx = ByteArray(w * h)
    private val ditherStrength = when (quality) { Quality.NORMAL -> 0f; Quality.HIGH -> 0.8f; Quality.MAX -> 1f }
    /** Max per-channel difference to reuse the pixel already on screen. */
    private val reuseThreshold = when (quality) { Quality.NORMAL -> 10; Quality.HIGH -> 4; Quality.MAX -> 0 }

    init {
        out.write("GIF89a".toByteArray())
        short(w); short(h)
        out.write(0x00); out.write(0); out.write(0) // no global colour table
        // NETSCAPE2.0: loop forever
        out.write(byteArrayOf(0x21, 0xFF.toByte(), 0x0B))
        out.write("NETSCAPE2.0".toByteArray())
        out.write(byteArrayOf(3, 1, 0, 0, 0))
    }

    private fun short(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }

    private fun key(r: Int, g: Int, b: Int) = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)

    /** Adds a frame shown for [delayCs] hundredths of a second. */
    fun addFrame(src: Bitmap, delayCs: Int) {
        val b = if (src.width == w && src.height == h) src else Bitmap.createScaledBitmap(src, w, h, true)
        b.getPixels(px, 0, w, 0, 0, w, h)
        if (b !== src) b.recycle()

        val pal = if (quality == Quality.NORMAL) popularityPalette() else medianCutPalette()
        val n = pal.size
        val lut = IntArray(BINS) { -1 }
        fun nearest(r: Int, g: Int, bl: Int): Int {
            val k = key(r, g, bl)
            var m = lut[k]
            if (m < 0) {
                var best = 0; var bd = Int.MAX_VALUE
                for (p in 0 until n) {
                    val q = pal[p]
                    val dr = r - ((q shr 16) and 0xFF); val dg = g - ((q shr 8) and 0xFF); val db = bl - (q and 0xFF)
                    val d = dr * dr * 3 + dg * dg * 4 + db * db * 2
                    if (d < bd) { bd = d; best = p }
                }
                m = best; lut[k] = m
            }
            return m
        }

        // error buffers for Floyd–Steinberg (current and next row), serpentine scan
        val errCur = FloatArray((w + 2) * 3)
        val errNext = FloatArray((w + 2) * 3)
        val dither = ditherStrength > 0f
        for (y in 0 until h) {
            val ltr = y % 2 == 0
            java.util.Arrays.fill(errNext, 0f)
            var x = if (ltr) 0 else w - 1
            val step = if (ltr) 1 else -1
            repeat(w) {
                val i = y * w + x
                val c = px[i]
                val e = (x + 1) * 3
                val r = (((c shr 16) and 0xFF) + if (dither) errCur[e] else 0f).roundToInt().coerceIn(0, 255)
                val g = (((c shr 8) and 0xFF) + if (dither) errCur[e + 1] else 0f).roundToInt().coerceIn(0, 255)
                val bl = ((c and 0xFF) + if (dither) errCur[e + 2] else 0f).roundToInt().coerceIn(0, 255)
                val p = nearest(r, g, bl)
                val q = pal[p]
                // reuse what's already on screen if it's close enough
                val s = shown[i]
                val outColor: Int
                if (s >= 0 && abs(((s shr 16) and 0xFF) - ((q shr 16) and 0xFF)) <= reuseThreshold &&
                    abs(((s shr 8) and 0xFF) - ((q shr 8) and 0xFF)) <= reuseThreshold &&
                    abs((s and 0xFF) - (q and 0xFF)) <= reuseThreshold) {
                    idx[i] = TRANSPARENT.toByte(); outColor = s
                } else {
                    idx[i] = p.toByte(); outColor = q; shown[i] = q
                }
                if (dither) {
                    val er = (r - ((outColor shr 16) and 0xFF)) * ditherStrength
                    val eg = (g - ((outColor shr 8) and 0xFF)) * ditherStrength
                    val eb = (bl - (outColor and 0xFF)) * ditherStrength
                    // 7/16 ahead, 3/16 behind-below, 5/16 below, 1/16 ahead-below
                    val ahead = e + 3 * step; val behind = e - 3 * step
                    errCur[ahead] += er * 7 / 16; errCur[ahead + 1] += eg * 7 / 16; errCur[ahead + 2] += eb * 7 / 16
                    errNext[behind] += er * 3 / 16; errNext[behind + 1] += eg * 3 / 16; errNext[behind + 2] += eb * 3 / 16
                    errNext[e] += er * 5 / 16; errNext[e + 1] += eg * 5 / 16; errNext[e + 2] += eb * 5 / 16
                    errNext[ahead] += er / 16; errNext[ahead + 1] += eg / 16; errNext[ahead + 2] += eb / 16
                }
                x += step
            }
            System.arraycopy(errNext, 0, errCur, 0, errCur.size)
        }

        // graphic control extension: delay, disposal = keep, transparent index
        out.write(byteArrayOf(0x21, 0xF9.toByte(), 4, ((1 shl 2) or 1).toByte()))
        short(delayCs.coerceAtLeast(2))
        out.write(TRANSPARENT); out.write(0)
        // image descriptor + local colour table (256 entries)
        out.write(0x2C); short(0); short(0); short(w); short(h)
        out.write(0x87)
        for (p in 0 until 256) {
            val q = if (p < n) pal[p] else 0
            out.write((q shr 16) and 0xFF); out.write((q shr 8) and 0xFF); out.write(q and 0xFF)
        }
        lzw(idx)
    }

    fun finish() { out.write(0x3B); out.flush() }

    // ================================================================ palettes

    private fun histogram(): Pair<IntArray, Array<LongArray>> {
        val hist = IntArray(BINS)
        val sums = Array(3) { LongArray(BINS) }
        for (c in px) {
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            val k = key(r, g, b)
            hist[k]++; sums[0][k] += r.toLong(); sums[1][k] += g.toLong(); sums[2][k] += b.toLong()
        }
        return hist to sums
    }

    private fun popularityPalette(): IntArray {
        val (hist, sums) = histogram()
        val top = (0 until BINS).filter { hist[it] > 0 }.sortedByDescending { hist[it] }.take(COLORS)
        return IntArray(max(1, top.size)) { i ->
            val k = top.getOrElse(i) { 0 }
            val c = hist[k].coerceAtLeast(1)
            ((sums[0][k] / c).toInt() shl 16) or ((sums[1][k] / c).toInt() shl 8) or (sums[2][k] / c).toInt()
        }
    }

    /** A box in 5-5-5 colour space: inclusive ranges per channel. */
    private class Box(val lo: IntArray, val hi: IntArray, var count: Long = 0)

    private fun medianCutPalette(): IntArray {
        val (hist, sums) = histogram()
        fun bin(r: Int, g: Int, b: Int) = (r shl 10) or (g shl 5) or b
        fun shrink(bx: Box) {
            val lo = intArrayOf(31, 31, 31); val hi = intArrayOf(0, 0, 0); var cnt = 0L
            for (r in bx.lo[0]..bx.hi[0]) for (g in bx.lo[1]..bx.hi[1]) for (b in bx.lo[2]..bx.hi[2]) {
                val c = hist[bin(r, g, b)]
                if (c == 0) continue
                cnt += c
                if (r < lo[0]) lo[0] = r; if (r > hi[0]) hi[0] = r
                if (g < lo[1]) lo[1] = g; if (g > hi[1]) hi[1] = g
                if (b < lo[2]) lo[2] = b; if (b > hi[2]) hi[2] = b
            }
            bx.count = cnt
            if (cnt > 0) { lo.copyInto(bx.lo); hi.copyInto(bx.hi) }
        }
        val boxes = ArrayList<Box>()
        Box(intArrayOf(0, 0, 0), intArrayOf(31, 31, 31)).also { shrink(it); if (it.count > 0) boxes += it }
        while (boxes.size < COLORS) {
            // split the box with the most pixels × widest range
            var pick = -1; var score = 0L
            for ((i, bx) in boxes.withIndex()) {
                val range = max(bx.hi[0] - bx.lo[0], max(bx.hi[1] - bx.lo[1], bx.hi[2] - bx.lo[2]))
                if (range == 0) continue
                val s2 = bx.count * range
                if (s2 > score) { score = s2; pick = i }
            }
            if (pick < 0) break
            val bx = boxes[pick]
            // widest channel (green weighted a bit, the eye is most sensitive to it)
            val ranges = intArrayOf((bx.hi[0] - bx.lo[0]) * 3, (bx.hi[1] - bx.lo[1]) * 4, (bx.hi[2] - bx.lo[2]) * 2)
            val ch = ranges.indices.maxByOrNull { ranges[it] }!!
            // weighted median along that channel
            val proj = LongArray(32)
            for (r in bx.lo[0]..bx.hi[0]) for (g in bx.lo[1]..bx.hi[1]) for (b in bx.lo[2]..bx.hi[2]) {
                val c = hist[bin(r, g, b)]
                if (c == 0) continue
                proj[when (ch) { 0 -> r; 1 -> g; else -> b }] += c.toLong()
            }
            var acc = 0L; var cut = bx.lo[ch]
            for (v in bx.lo[ch] until bx.hi[ch]) { acc += proj[v]; cut = v; if (acc * 2 >= bx.count) break }
            val a = Box(bx.lo.copyOf(), bx.hi.copyOf()); a.hi[ch] = cut
            val c2 = Box(bx.lo.copyOf(), bx.hi.copyOf()); c2.lo[ch] = cut + 1
            shrink(a); shrink(c2)
            boxes.removeAt(pick)
            if (a.count > 0) boxes += a
            if (c2.count > 0) boxes += c2
        }
        // average real colour of each box
        return IntArray(max(1, boxes.size)) { i ->
            val bx = boxes.getOrNull(i) ?: return@IntArray 0
            var n = 0L; var sr = 0L; var sg = 0L; var sb = 0L
            for (r in bx.lo[0]..bx.hi[0]) for (g in bx.lo[1]..bx.hi[1]) for (b in bx.lo[2]..bx.hi[2]) {
                val k = bin(r, g, b)
                n += hist[k]; sr += sums[0][k]; sg += sums[1][k]; sb += sums[2][k]
            }
            if (n == 0L) 0 else ((sr / n).toInt() shl 16) or ((sg / n).toInt() shl 8) or (sb / n).toInt()
        }
    }

    // ================================================================ LZW

    /** Standard GIF LZW with 8-bit min code size, written in 255-byte sub-blocks. */
    private fun lzw(data: ByteArray) {
        val minCode = 8
        out.write(minCode)
        val block = ByteArray(256)
        var blockLen = 0
        var bitBuf = 0; var bitCnt = 0
        fun flushBlock() { if (blockLen > 0) { out.write(blockLen); out.write(block, 0, blockLen); blockLen = 0 } }
        fun emit(code: Int, size: Int) {
            bitBuf = bitBuf or (code shl bitCnt); bitCnt += size
            while (bitCnt >= 8) { block[blockLen++] = (bitBuf and 0xFF).toByte(); bitBuf = bitBuf ushr 8; bitCnt -= 8; if (blockLen == 255) flushBlock() }
        }
        val clear = 1 shl minCode; val eoi = clear + 1
        val dict = HashMap<Int, Int>(8192)
        var next = eoi + 1; var size = minCode + 1
        emit(clear, size)
        var prefix = data[0].toInt() and 0xFF
        for (i in 1 until data.size) {
            val k = data[i].toInt() and 0xFF
            val key = (prefix shl 8) or k
            val found = dict[key]
            if (found != null) { prefix = found; continue }
            emit(prefix, size)
            if (next < 4096) {
                dict[key] = next++
                if (next > (1 shl size) && size < 12) size++
            } else {
                emit(clear, size); dict.clear(); next = eoi + 1; size = minCode + 1
            }
            prefix = k
        }
        emit(prefix, size)
        emit(eoi, size)
        if (bitCnt > 0) { block[blockLen++] = (bitBuf and 0xFF).toByte(); if (blockLen == 255) flushBlock() }
        flushBlock()
        out.write(0)
    }
}
