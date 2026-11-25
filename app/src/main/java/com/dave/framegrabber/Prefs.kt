package com.dave.framegrabber

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** All remembered settings in one place (SharedPreferences "settings"). */
class Prefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun str(k: String, d: String) = sp.getString(k, d) ?: d
    private fun put(block: SharedPreferences.Editor.() -> Unit) = sp.edit().apply(block).apply()

    // capture output
    var format: String get() = str("fmt", "JPG"); set(v) = put { putString("fmt", v) }
    var size: String get() = str("size", "orig"); set(v) = put { putString("size", v) }
    var customW: String get() = str("cw", "1920"); set(v) = put { putString("cw", v) }
    var customH: String get() = str("ch", "1080"); set(v) = put { putString("ch", v) }
    var quality: Int get() = sp.getInt("q", 92); set(v) = put { putInt("q", v) }
    var sharpest: Boolean get() = sp.getBoolean("sharp", true); set(v) = put { putBoolean("sharp", v) }

    // batch
    var batchCount: Boolean get() = sp.getBoolean("bCount", false); set(v) = put { putBoolean("bCount", v) }
    var batchInterval: String get() = str("bInt", "5"); set(v) = put { putString("bInt", v) }
    var batchN: String get() = str("bN", "10"); set(v) = put { putString("bN", v) }
    var skipBlurry: Boolean get() = sp.getBoolean("skipBlur", false); set(v) = put { putBoolean("skipBlur", v) }
    var skipDupes: Boolean get() = sp.getBoolean("skipDup", false); set(v) = put { putBoolean("skipDup", v) }

    // export
    var gifFps: Int get() = sp.getInt("gifFps", 10); set(v) = put { putInt("gifFps", v) }
    var gifWidth: Int get() = sp.getInt("gifW", 480); set(v) = put { putInt("gifW", v) }

    // ui
    var tab: Int get() = sp.getInt("tab", 0); set(v) = put { putInt("tab", v) }
    var lastDir: String? get() = sp.getString("lastDir", null); set(v) = put { putString("lastDir", v) }

    // general
    var namePattern: String get() = str("pattern", DEFAULT_PATTERN); set(v) = put { putString("pattern", v) }
    /** SAF tree Uri of a folder picked by the user, or null = Pictures/FrameGrabber. */
    var folderTree: String? get() = sp.getString("tree", null); set(v) = put { putString("tree", v) }
    var folderTreeName: String? get() = sp.getString("treeName", null); set(v) = put { putString("treeName", v) }
    var ocrLangs: String get() = str("ocr", "eng+rus"); set(v) = put { putString("ocr", v) }

    // recent videos (JSON array, newest first)
    var recentJson: String get() = str("recent", "[]"); set(v) = put { putString("recent", v) }

    companion object {
        const val DEFAULT_PATTERN = "{video}_{time}"
        const val ALBUM = "FrameGrabber"
    }
}

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

/**
 * Where results go: the folder the user picked (SAF tree) or the default
 * Pictures/FrameGrabber, Movies/FrameGrabber, Download/FrameGrabber via MediaStore.
 */
object Output {
    enum class Kind { IMAGE, VIDEO, DOWNLOAD }

    /** Copies [src] to the destination, returns its Uri. */
    fun save(ctx: Context, src: File, name: String, mime: String, kind: Kind): Uri {
        val prefs = Prefs(ctx)
        val tree = prefs.folderTree
        val cr = ctx.contentResolver
        if (tree != null && kind != Kind.DOWNLOAD) {
            runCatching {
                val treeUri = Uri.parse(tree)
                val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
                val uri = DocumentsContract.createDocument(cr, parent, mime, name) ?: error("createDocument")
                cr.openOutputStream(uri)!!.use { o -> src.inputStream().use { it.copyTo(o) } }
                return uri
            }
            // folder no longer accessible -> fall back to the default location
        }
        val (col, dir) = when (kind) {
            Kind.IMAGE -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_PICTURES
            Kind.VIDEO -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_MOVIES
            Kind.DOWNLOAD -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_DOWNLOADS
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/${Prefs.ALBUM}")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = cr.insert(col, values) ?: error("Не удалось создать файл")
        cr.openOutputStream(uri)!!.use { o -> src.inputStream().use { it.copyTo(o) } }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        cr.update(uri, values, null, null)
        return uri
    }

    /** Human description of where images go. */
    fun describe(ctx: Context): String {
        val p = Prefs(ctx)
        return if (p.folderTree != null) (p.folderTreeName ?: "выбранная папка") else "Pictures/${Prefs.ALBUM}"
    }
}

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
