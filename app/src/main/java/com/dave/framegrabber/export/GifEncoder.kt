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

/**
 * Minimal animated GIF encoder (GIF89a, looping). Each frame gets its own 256-colour palette
 * picked by popularity from a 15-bit histogram, with nearest-colour mapping.
 */
class GifEncoder(private val out: OutputStream, private val w: Int, private val h: Int, private val delayMs: Int) {
    private var first = true

    init {
        out.write("GIF89a".toByteArray())
        short(w); short(h)
        out.write(0x00); out.write(0); out.write(0) // no global colour table
        // NETSCAPE2.0 loop forever
        out.write(byteArrayOf(0x21, 0xFF.toByte(), 0x0B))
        out.write("NETSCAPE2.0".toByteArray())
        out.write(byteArrayOf(3, 1, 0, 0, 0))
    }

    private fun short(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }

    fun addFrame(src: Bitmap) {
        val b = if (src.width == w && src.height == h) src else Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        // palette by popularity of 5-5-5 colours
        val hist = IntArray(32768)
        for (c in px) hist[((c shr 9) and 0x7C00) or ((c shr 6) and 0x3E0) or ((c shr 3) and 0x1F)]++
        val top = (0 until 32768).filter { hist[it] > 0 }.sortedByDescending { hist[it] }.take(256)
        val pal = IntArray(256)
        for ((i, k) in top.withIndex()) pal[i] = ((k shr 10) and 31) * 255 / 31 shl 16 or (((k shr 5) and 31) * 255 / 31 shl 8) or ((k and 31) * 255 / 31)
        val nColors = max(2, top.size)
        // map every 15-bit colour to the nearest palette entry (lazy cache)
        val lut = IntArray(32768) { -1 }
        val idx = ByteArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            val k = ((c shr 9) and 0x7C00) or ((c shr 6) and 0x3E0) or ((c shr 3) and 0x1F)
            var m = lut[k]
            if (m < 0) {
                val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val bl = c and 0xFF
                var best = 0; var bd = Int.MAX_VALUE
                for (p in 0 until nColors) {
                    val q = pal[p]
                    val dr = r - ((q shr 16) and 0xFF); val dg = g - ((q shr 8) and 0xFF); val db = bl - (q and 0xFF)
                    val d = dr * dr * 3 + dg * dg * 4 + db * db * 2
                    if (d < bd) { bd = d; best = p }
                }
                m = best; lut[k] = m
            }
            idx[i] = m.toByte()
        }
        // graphic control extension (delay)
        out.write(byteArrayOf(0x21, 0xF9.toByte(), 4, 0x04))
        short((delayMs / 10).coerceAtLeast(2))
        out.write(0); out.write(0)
        // image descriptor with local colour table of 256
        out.write(0x2C); short(0); short(0); short(w); short(h)
        out.write(0x87)
        for (p in 0 until 256) { val q = pal[p]; out.write((q shr 16) and 0xFF); out.write((q shr 8) and 0xFF); out.write(q and 0xFF) }
        lzw(idx)
        if (b !== src) b.recycle()
        first = false
    }

    fun finish() { out.write(0x3B); out.flush() }

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
