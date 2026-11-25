package com.dave.framegrabber.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaMetadataRetriever
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

object FrameRender {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /**
     * Fit [src] into tw x th keeping aspect ratio, centered, with padding
     * (black for JPG, transparent for PNG/WebP) — same behavior as the website.
     */
    fun fit(src: Bitmap, tw: Int, th: Int, transparentPad: Boolean): Bitmap {
        if (src.width == tw && src.height == th) return src
        val out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        if (!transparentPad) c.drawColor(Color.BLACK)
        val s = min(tw.toFloat() / src.width, th.toFloat() / src.height)
        val dw = src.width * s
        val dh = src.height * s
        val left = (tw - dw) / 2f
        val top = (th - dh) / 2f
        c.drawBitmap(src, null, RectF(left, top, left + dw, top + dh), paint)
        return out
    }

    /** Small RGB_565 thumbnail for the captures grid (keeps memory low with hundreds of frames). */
    fun thumb(src: Bitmap, maxSide: Int = 320): Bitmap {
        val s = min(1f, maxSide.toFloat() / max(src.width, src.height))
        val tw = max(1, (src.width * s).roundToInt())
        val th = max(1, (src.height * s).roundToInt())
        val out = Bitmap.createBitmap(tw, th, Bitmap.Config.RGB_565)
        Canvas(out).drawBitmap(src, null, RectF(0f, 0f, tw.toFloat(), th.toFloat()), paint)
        return out
    }

    @Suppress("DEPRECATION")
    fun encode(bmp: Bitmap, fmt: Fmt, quality: Int, out: OutputStream) {
        val cf = when (fmt) {
            Fmt.JPG -> Bitmap.CompressFormat.JPEG
            Fmt.PNG -> Bitmap.CompressFormat.PNG
            Fmt.WEBP -> if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY
                        else Bitmap.CompressFormat.WEBP
        }
        // JPEG has no alpha: flatten onto black first
        val src = if (fmt == Fmt.JPG && bmp.hasAlpha()) {
            val b = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
            Canvas(b).apply { drawColor(Color.BLACK); drawBitmap(bmp, 0f, 0f, null) }
            b
        } else bmp
        if (!src.compress(cf, quality.coerceIn(1, 100), out)) error("Не удалось закодировать изображение")
    }

    /** Decode a saved image, halving until its long side is below [maxSide] (keeps memory low). */
    fun decodeFile(file: java.io.File, maxSide: Int): Bitmap? {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.path, o)
        var sample = 1
        while (max(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return android.graphics.BitmapFactory.decodeFile(file.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
