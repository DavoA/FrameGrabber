package com.dave.framegrabber.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import com.dave.framegrabber.ui.browser.VideoSource
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * The player's library (like VLC): every video inside the folders chosen in the settings
 * (subfolders too), with cached thumbnails and durations.
 */
class Library(ctx: Context) {

    class Item(val file: File, val folder: File) {
        val name: String get() = file.name
        val size: Long = file.length()
        val date: Long = file.lastModified()
        /** For the player's memory ([PlayerStore]). */
        val id: String get() = PlayerStore.idOf(android.net.Uri.fromFile(file))
    }

    private val app = ctx.applicationContext
    private val sp = app.getSharedPreferences("player", Context.MODE_PRIVATE)

    // ================================================================ folders

    var folders: List<String>
        get() = runCatching { JSONArray(sp.getString("folders", "[]")).let { a -> (0 until a.length()).map { a.getString(it) } } }
            .getOrDefault(emptyList())
        set(v) = sp.edit().putString("folders", JSONArray(v.distinct()).toString()).apply()

    fun addFolder(path: String) {
        // a folder inside one that's already there adds nothing; one that contains others replaces them
        val cur = folders
        if (cur.any { path == it || path.startsWith("$it/") }) return
        folders = cur.filterNot { it.startsWith("$path/") } + path
    }

    fun removeFolder(path: String) { folders = folders - path }

    /** "date" | "name" | "size" | "duration" | "watched" */
    var sort: String
        get() = sp.getString("sort", "date") ?: "date"
        set(v) = sp.edit().putString("sort", v).apply()

    // ================================================================ scanning

    /** All videos in the folders (blocking; a few hundred files take well under a second). */
    fun scan(): List<Item> {
        val out = ArrayList<Item>()
        val seen = HashSet<String>()
        folders.map { File(it) }.filter { it.isDirectory }.forEach { root ->
            walk(root, root, 0, out, seen)
        }
        return out
    }

    private fun walk(dir: File, root: File, depth: Int, out: MutableList<Item>, seen: MutableSet<String>) {
        if (depth > 8) return
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (f.name.startsWith(".")) continue
            if (f.isDirectory) walk(f, root, depth + 1, out, seen)
            else if (f.extension.lowercase() in VideoSource.VIDEO_EXT && f.length() > 0 && seen.add(f.path)) out += Item(f, dir)
        }
    }

    // ================================================================ thumbnails + durations

    private val thumbDir = File(app.filesDir, "thumbs").apply { mkdirs() }
    private val meta = app.getSharedPreferences("thumbmeta", Context.MODE_PRIVATE)

    fun key(f: File) = MessageDigest.getInstance("SHA-1").digest("${f.path}|${f.length()}".toByteArray())
        .joinToString("") { "%02x".format(it) }.take(20)

    /** Duration in ms if known already (0 = not yet). */
    fun cachedDuration(f: File): Long = meta.getLong(key(f), 0)

    /** Thumbnail from memory / disk / the video (blocking). Also learns the duration. */
    fun thumb(f: File): Bitmap? {
        val k = key(f)
        mem.get(k)?.let { return it }
        val file = File(thumbDir, "$k.jpg")
        if (file.exists() && meta.contains(k)) {
            BitmapFactory.decodeFile(file.path)?.let { mem.put(k, it); return it }
        }
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(f.path)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            meta.edit().putLong(k, dur.coerceAtLeast(1)).apply()
            // a bit into the video: the very first frame is often black or a logo
            val b = r.getScaledFrameAtTime(dur * 1000 / 6, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 360, 360) ?: return null
            runCatching { file.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 82, it) } }
            mem.put(k, b)
            return b
        } catch (e: Exception) {
            meta.edit().putLong(k, 1).apply()   // don't try again and again
            return null
        } finally {
            runCatching { r.release() }
        }
    }

    companion object {
        private val mem = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount
        }
        /** Thumbnails are made two at a time, in the background. */
        val loader = Executors.newFixedThreadPool(2)
    }
}
