package com.dave.framegrabber.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject

/** GIFs and clips made in the app (shown in «Клипы» → «Готовые»). Newest first. */
object Made {
    class Item(val uri: Uri, val mime: String, val name: String, val time: Long, val lengthUs: Long) {
        val isGif get() = mime == "image/gif"
    }

    fun list(ctx: Context): List<Item> = runCatching {
        val a = JSONArray(Prefs(ctx).madeJson)
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Item(Uri.parse(o.getString("uri")), o.getString("mime"), o.optString("name"), o.optLong("t"), o.optLong("len"))
        }
    }.getOrDefault(emptyList())

    fun add(ctx: Context, uri: Uri, mime: String, name: String, lengthUs: Long) {
        val items = list(ctx).filter { it.uri != uri }.toMutableList()
        items.add(0, Item(uri, mime, name, System.currentTimeMillis(), lengthUs))
        save(ctx, items.take(300))
    }

    /** Removes from the list; with [deleteFile] also deletes the file itself. */
    fun remove(ctx: Context, item: Item, deleteFile: Boolean) {
        if (deleteFile) runCatching {
            if (item.uri.authority == "media") ctx.contentResolver.delete(item.uri, null, null)
            else DocumentsContract.deleteDocument(ctx.contentResolver, item.uri)
        }
        save(ctx, list(ctx).filter { it.uri != item.uri })
    }

    /**
     * GIFs in Pictures/FrameGrabber and clips in Movies/FrameGrabber that aren't in the list yet
     * (made by older versions, or before a reinstall) are added; deleted files are dropped.
     * Background thread. Returns true if the list changed.
     */
    fun syncWithGallery(ctx: Context): Boolean {
        val cur = list(ctx)
        val known = cur.map { it.uri.toString() }.toHashSet()
        val found = ArrayList<Item>()
        fun scan(table: Uri, where: String, args: Array<String>, mimeOf: (String) -> String, video: Boolean) = runCatching {
            val cols = mutableListOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATE_ADDED)
            if (video) cols += MediaStore.Video.VideoColumns.DURATION
            ctx.contentResolver.query(table, cols.toTypedArray(), where, args, null)?.use { c ->
                while (c.moveToNext()) {
                    val uri = android.content.ContentUris.withAppendedId(table, c.getLong(0))
                    if (uri.toString() in known) continue
                    val name = c.getString(1) ?: continue
                    found += Item(uri, mimeOf(c.getString(2) ?: ""), name.substringBeforeLast('.'), c.getLong(3) * 1000,
                        if (video) c.getLong(4) * 1000 else 0L)
                }
            }
        }
        val pics = "${android.os.Environment.DIRECTORY_PICTURES}/${Prefs.ALBUM}/"
        val movies = "${android.os.Environment.DIRECTORY_MOVIES}/${Prefs.ALBUM}/"
        scan(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.MIME_TYPE}=?", arrayOf(pics, "image/gif"),
            { "image/gif" }, video = false)
        // only the folder itself: parts of split videos and Telegram downloads are in subfolders
        scan(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=?", arrayOf(movies),
            { it.ifEmpty { "video/mp4" } }, video = true)
        val alive = cur.filter { exists(ctx, it) }
        if (found.isEmpty() && alive.size == cur.size) return false
        save(ctx, (alive + found).sortedByDescending { it.time }.take(300))
        return true
    }

    /** True if the file is still there. */
    fun exists(ctx: Context, item: Item): Boolean =
        runCatching { ctx.contentResolver.openFileDescriptor(item.uri, "r")?.use { true } ?: false }.getOrDefault(false)

    private fun save(ctx: Context, items: List<Item>) {
        val a = JSONArray()
        items.forEach {
            a.put(JSONObject().put("uri", it.uri.toString()).put("mime", it.mime).put("name", it.name)
                .put("t", it.time).put("len", it.lengthUs))
        }
        Prefs(ctx).madeJson = a.toString()
    }
}
