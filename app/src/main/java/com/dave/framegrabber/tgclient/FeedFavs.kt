package com.dave.framegrabber.tgclient

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * «Любимые» posts of the feed, per channel: every message id of the post (an album = several),
 * so the post can be fetched again as a whole. Kept in prefs → goes into the backup.
 */
class FeedFavs(ctx: Context) {
    private val p = ctx.getSharedPreferences("feed_favs", Context.MODE_PRIVATE)

    class Fav(val ids: List<Long>, val added: Long)

    fun list(chat: Long): List<Fav> {
        val a = runCatching { JSONArray(p.getString("c$chat", "[]")) }.getOrDefault(JSONArray())
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val ids = o.optJSONArray("ids") ?: return@mapNotNull null
            Fav((0 until ids.length()).map { ids.getLong(it) }, o.optLong("t"))
        }
    }

    private fun save(chat: Long, l: List<Fav>) {
        val a = JSONArray()
        l.forEach { f -> a.put(JSONObject().put("ids", JSONArray(f.ids)).put("t", f.added)) }
        p.edit().putString("c$chat", a.toString()).apply()
    }

    fun isFav(chat: Long, ids: List<Long>) = list(chat).any { f -> f.ids.any { it in ids } }

    /** Adds or removes the post; true = it is a favorite now. */
    fun toggle(chat: Long, ids: List<Long>): Boolean {
        val l = list(chat)
        val rest = l.filter { f -> f.ids.none { it in ids } }
        return if (rest.size != l.size) { save(chat, rest); false }
        else { save(chat, rest + Fav(ids, System.currentTimeMillis())); true }
    }

    /** Forgets posts that no longer exist in the channel. */
    fun keepOnly(chat: Long, alive: Set<Long>) {
        val l = list(chat)
        val k = l.filter { f -> f.ids.any { it in alive } }
        if (k.size != l.size) save(chat, k)
    }

    fun count(chat: Long) = list(chat).size
}
