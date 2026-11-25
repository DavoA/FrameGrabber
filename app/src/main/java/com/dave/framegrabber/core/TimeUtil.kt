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

object TimeUtil {
    /** "12.5", "1:23.5", "1:02:03" -> microseconds; null if invalid/empty. */
    fun parse(text: String): Long? {
        val t = text.trim().replace(',', '.')
        if (t.isEmpty()) return null
        val parts = t.split(':')
        if (parts.size > 3) return null
        var sec = 0.0
        for (p in parts) {
            val v = p.toDoubleOrNull() ?: return null
            if (v < 0) return null
            sec = sec * 60 + v
        }
        return (sec * 1_000_000).toLong()
    }

    /** microseconds -> "1:23.456" or "1:02:03.456" */
    fun format(us: Long): String {
        val totalMs = us / 1000
        val ms = totalMs % 1000
        val s = (totalMs / 1000) % 60
        val m = (totalMs / 60_000) % 60
        val h = totalMs / 3_600_000
        return if (h > 0) "%d:%02d:%02d.%03d".format(h, m, s, ms)
        else "%d:%02d.%03d".format(m, s, ms)
    }

    /** Safe for file names: "00-01-23.456" */
    fun stamp(us: Long): String {
        val totalMs = us / 1000
        return "%02d-%02d-%02d.%03d".format(
            totalMs / 3_600_000, (totalMs / 60_000) % 60, (totalMs / 1000) % 60, totalMs % 1000
        )
    }
}
