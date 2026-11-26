package com.dave.framegrabber.data

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings backup: everything you set up (hashtags, names used before, Telegram bot and keys,
 * player folders and resume points, color scheme, published posts for the duplicate check, model
 * photos) in one file in Download/FrameGrabber. Restoring it on a new install brings it all back.
 * Not included: the Telegram login itself (log in again), frames of projects, caches.
 */
object Backup {

    private val SKIP_PREFS = setOf("thumbmeta")
    /** Files and folders (in the app's storage) that go into the backup. */
    private val FILES = listOf("tg_posts.json", "player", "models")

    fun export(ctx: Context): Uri {
        val root = JSONObject().put("app", "FrameGrabber").put("version", 1).put("created", System.currentTimeMillis())
        val prefs = JSONObject()
        prefsDir(ctx).listFiles()?.filter { it.name.endsWith(".xml") }?.forEach { f ->
            val name = f.name.removeSuffix(".xml")
            if (name in SKIP_PREFS) return@forEach
            val o = JSONObject()
            ctx.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (k, v) ->
                val e = when (v) {
                    is Boolean -> JSONObject().put("t", "b").put("v", v)
                    is Int -> JSONObject().put("t", "i").put("v", v)
                    is Long -> JSONObject().put("t", "l").put("v", v)
                    is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
                    is String -> JSONObject().put("t", "s").put("v", v)
                    is Set<*> -> JSONObject().put("t", "set").put("v", JSONArray(v.map { it.toString() }))
                    else -> null
                }
                if (e != null) o.put(k, e)
            }
            prefs.put(name, o)
        }
        root.put("prefs", prefs)
        val files = JSONObject()
        FILES.forEach { rel ->
            val f = File(ctx.filesDir, rel)
            val list = if (f.isDirectory) f.walkTopDown().filter { it.isFile }.toList() else listOf(f).filter { it.exists() }
            list.forEach { x -> files.put(x.relativeTo(ctx.filesDir).path, Base64.encodeToString(x.readBytes(), Base64.NO_WRAP)) }
        }
        root.put("files", files)
        val name = "FrameGrabber-копия-" + SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date()) + ".json"
        return Output.write(ctx, name, "application/json", Output.Kind.DOWNLOAD) { t ->
            t.stream().use { it.write(root.toString().toByteArray()) }
        }
    }

    /** Puts everything from the file back. Returns how many settings were restored. */
    fun restore(ctx: Context, uri: Uri): Int {
        val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IllegalStateException("Не открывается файл")
        val root = runCatching { JSONObject(text) }.getOrNull()
        if (root == null || root.optString("app") != "FrameGrabber") throw IllegalStateException("Это не резервная копия FrameGrabber")
        var count = 0
        val prefs = root.optJSONObject("prefs") ?: JSONObject()
        prefs.keys().forEach { name ->
            val o = prefs.getJSONObject(name)
            val ed = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            o.keys().forEach { k ->
                val e = o.getJSONObject(k)
                when (e.getString("t")) {
                    "b" -> ed.putBoolean(k, e.getBoolean("v"))
                    "i" -> ed.putInt(k, e.getInt("v"))
                    "l" -> ed.putLong(k, e.getLong("v"))
                    "f" -> ed.putFloat(k, e.getDouble("v").toFloat())
                    "s" -> ed.putString(k, e.getString("v"))
                    "set" -> e.getJSONArray("v").let { a -> ed.putStringSet(k, (0 until a.length()).map { a.getString(it) }.toSet()) }
                }
                count++
            }
            ed.commit()
        }
        val files = root.optJSONObject("files") ?: JSONObject()
        files.keys().forEach { rel ->
            val f = File(ctx.filesDir, rel)
            if (!f.canonicalPath.startsWith(ctx.filesDir.canonicalPath)) return@forEach   // only inside the app's folder
            f.parentFile?.mkdirs()
            f.writeBytes(Base64.decode(files.getString(rel), Base64.NO_WRAP))
        }
        return count
    }

    private fun prefsDir(ctx: Context) = File(ctx.dataDir, "shared_prefs")
}
