package com.dave.framegrabber

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

/** One image with every captured frame in a grid and its timestamp underneath. */
object ContactSheet {
    fun build(captures: List<Capture>, title: String, subtitle: String): Bitmap {
        require(captures.isNotEmpty())
        val n = captures.size
        val cols = ceil(sqrt(n.toDouble())).toInt().coerceIn(2, 6).coerceAtMost(max(2, n))
        val rows = ceil(n / cols.toDouble()).toInt()
        val aspect = captures[0].height.toFloat() / max(1, captures[0].width)
        // keep the sheet under ~24 MP so it can be built and shared on any phone
        var cellW = min(640, 4096 / cols)
        while (cellW > 120 && cols * cellW * rows * (cellW * aspect + cellW * 0.16f) > 24_000_000) cellW -= 20
        val cellH = (cellW * aspect).roundToInt()
        val gap = (cellW * 0.04f).roundToInt().coerceAtLeast(6)
        val label = (cellW * 0.12f).roundToInt().coerceAtLeast(22)
        val header = (cellW * 0.38f).roundToInt().coerceAtLeast(90)
        val w = cols * cellW + (cols + 1) * gap
        val h = header + rows * (cellH + label + gap) + gap

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.parseColor("#07130D"))
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E9F7EF"); typeface = Typeface.DEFAULT_BOLD; textSize = header * 0.36f }
        c.drawText(title, gap.toFloat(), header * 0.48f, tp)
        tp.apply { typeface = Typeface.DEFAULT; textSize = header * 0.22f; color = Color.parseColor("#94BCA6") }
        c.drawText(subtitle, gap.toFloat(), header * 0.82f, tp)

        val lp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#5CF5AE"); typeface = Typeface.MONOSPACE; textSize = label * 0.62f; textAlign = Paint.Align.CENTER
        }
        val bp = Paint(Paint.FILTER_BITMAP_FLAG)
        for ((i, cap) in captures.withIndex()) {
            val col = i % cols; val row = i / cols
            val x = gap + col * (cellW + gap)
            val y = header + row * (cellH + label + gap)
            val img = decode(cap.file, cellW) ?: cap.thumb
            // fit keeping aspect
            val s = min(cellW / img.width.toFloat(), cellH / img.height.toFloat())
            val dw = img.width * s; val dh = img.height * s
            val dx = x + (cellW - dw) / 2; val dy = y + (cellH - dh) / 2
            c.drawRect(x.toFloat(), y.toFloat(), (x + cellW).toFloat(), (y + cellH).toFloat(), Paint().apply { color = Color.BLACK })
            c.drawBitmap(img, null, RectF(dx, dy, dx + dw, dy + dh), bp)
            if (img !== cap.thumb) img.recycle()
            c.drawText(TimeUtil.format(cap.timeUs), x + cellW / 2f, y + cellH + label * 0.75f, lp)
        }
        return out
    }

    private fun decode(f: File, targetW: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var sample = 1
        while (o.outWidth / (sample * 2) >= targetW) sample *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}

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
