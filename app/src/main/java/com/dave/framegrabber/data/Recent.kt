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

/** Recently opened videos (shown on the start screen). */
object Recent {
    class Item(val ref: String, val isPath: Boolean, val name: String, val info: String, val thumb: String?, val time: Long)

    fun list(ctx: Context): List<Item> = runCatching {
        val a = JSONArray(Prefs(ctx).recentJson)
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Item(o.getString("ref"), o.optBoolean("path"), o.optString("name"), o.optString("info"),
                o.optString("thumb").ifEmpty { null }, o.optLong("t"))
        }
    }.getOrDefault(emptyList())

    fun add(ctx: Context, ref: String, isPath: Boolean, name: String, info: String, thumb: android.graphics.Bitmap?) {
        val dir = File(ctx.filesDir, "recent").apply { mkdirs() }
        val thumbName = "r_${ref.hashCode().toUInt()}.jpg"
        thumb?.let { b -> runCatching { File(dir, thumbName).outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) } } }
        val items = list(ctx).filter { it.ref != ref }.toMutableList()
        items.add(0, Item(ref, isPath, name, info, if (thumb != null) thumbName else null, System.currentTimeMillis()))
        val keep = items.take(12)
        // drop thumbnails of removed entries
        val keepThumbs = keep.mapNotNull { it.thumb }.toSet()
        dir.listFiles()?.filter { it.name !in keepThumbs }?.forEach { it.delete() }
        save(ctx, keep)
    }

    fun remove(ctx: Context, ref: String) = save(ctx, list(ctx).filter { it.ref != ref })

    fun thumbFile(ctx: Context, it: Item) = it.thumb?.let { File(File(ctx.filesDir, "recent"), it) }

    private fun save(ctx: Context, items: List<Item>) {
        val a = JSONArray()
        items.forEach {
            a.put(JSONObject().put("ref", it.ref).put("path", it.isPath).put("name", it.name)
                .put("info", it.info).put("thumb", it.thumb ?: "").put("t", it.time))
        }
        Prefs(ctx).recentJson = a.toString()
    }
}
