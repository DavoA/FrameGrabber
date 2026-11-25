package com.dave.framegrabber.data

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import com.dave.framegrabber.core.*
import com.dave.framegrabber.export.*
import com.dave.framegrabber.batch.*
import com.dave.framegrabber.ocr.*
import com.dave.framegrabber.ui.*
import com.dave.framegrabber.ui.widget.*
import com.dave.framegrabber.R

/** File naming: {video} {time} {n} {date} {ms} */
object Naming {
    private val bad = Regex("[\\\\/:*?\"<>|\\n\\r\\t]+")

    fun stem(pattern: String, video: String, us: Long, n: Int): String {
        val date = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val s = pattern.ifBlank { Prefs.DEFAULT_PATTERN }
            .replace("{video}", video)
            .replace("{time}", TimeUtil.stamp(us))
            .replace("{ms}", (us / 1000).toString())
            .replace("{n}", "%03d".format(Locale.US, n))
            .replace("{date}", date)
        return s.replace(bad, "_").trim().take(120).ifEmpty { "frame_${us / 1000}" }
    }
}
