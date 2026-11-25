package com.dave.framegrabber.telegram

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

/**
 * Frames as Telegram wants photos: JPEG, under 10 MB, sides ≤ 2560 px (Telegram shrinks bigger
 * photos itself anyway, so sending more only takes longer). PNG/WebP frames are converted.
 */
object PhotoPrep {
    const val MAX_SIDE = 2560
    private const val MAX_BYTES = 9_500_000

    fun jpeg(file: File): ByteArray {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        if (o.outWidth <= 0) throw IllegalStateException("Не удалось прочитать кадр ${file.name}")
        // already a small enough JPEG: send as is (no quality loss)
        if (o.outMimeType == "image/jpeg" && max(o.outWidth, o.outHeight) <= MAX_SIDE && file.length() < MAX_BYTES) {
            return file.readBytes()
        }
        var sample = 1
        while (max(o.outWidth, o.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val raw = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalStateException("Не удалось прочитать кадр ${file.name}")
        val longSide = max(raw.width, raw.height)
        val bmp = if (longSide > MAX_SIDE) {
            val k = MAX_SIDE.toFloat() / longSide
            Bitmap.createScaledBitmap(raw, (raw.width * k).toInt(), (raw.height * k).toInt(), true)
        } else raw
        var q = 93
        while (true) {
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, q, out)
            if (out.size() < MAX_BYTES || q <= 60) return out.toByteArray()
            q -= 8
        }
    }
}
