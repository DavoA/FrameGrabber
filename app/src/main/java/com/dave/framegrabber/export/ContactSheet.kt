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
