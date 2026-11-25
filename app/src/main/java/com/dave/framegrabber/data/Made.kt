package com.dave.framegrabber.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
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
        save(ctx, items.take(50))
    }

    /** Removes from the list; with [deleteFile] also deletes the file itself. */
    fun remove(ctx: Context, item: Item, deleteFile: Boolean) {
        if (deleteFile) runCatching {
            if (item.uri.authority == "media") ctx.contentResolver.delete(item.uri, null, null)
            else DocumentsContract.deleteDocument(ctx.contentResolver, item.uri)
        }
        save(ctx, list(ctx).filter { it.uri != item.uri })
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
