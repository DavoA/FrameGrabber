package com.dave.framegrabber.site

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * What you keep from the tracker: bookmarked torrents («на потом») and followed models
 * (the app checks the tracker for their new torrents once a day — site/WatchJob).
 */
class SiteStore(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("site", Context.MODE_PRIVATE)

    // ================================================================ hidden words (search filter)

    companion object {
        /** Hidden by default: trans content (title tags and section names, Latin and Russian). */
        val DEFAULT_HIDDEN = listOf(
            "transsexual", "shemale", "transgender", "tranny", "tgirl", "t-girl", "ladyboy", "trans", "ts",
            "транссексуал", "транссексуалы", "трансгендер", "шимейл", "трансы", "транс",
        )
    }

    var hideOn: Boolean
        get() = sp.getBoolean("hideOn", true)
        set(v) = sp.edit().putBoolean("hideOn", v).apply()

    var hidden: List<String>
        get() = sp.getString("hidden", null)?.let { s -> runCatching { JSONArray(s).let { a -> List(a.length()) { a.getString(it) } } }.getOrNull() }
            ?: DEFAULT_HIDDEN
        set(v) = sp.edit().putString("hidden", JSONArray(v.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()).toString()).apply()

    /**
     * Does a torrent show a hidden word (in its title or section)? Short words (≤ 4 letters, like
     * «ts», «транс») only as whole words, longer ones anywhere («Transsexuals», «Шимейлы»).
     */
    fun isHidden(title: String, forum: String): Boolean {
        if (!hideOn) return false
        val t = (title + " " + forum).lowercase()
        return hidden.any { w ->
            if (w.length <= 4) Regex("""(?<![\p{L}\p{N}])${Regex.escape(w)}(?![\p{L}\p{N}])""").containsMatchIn(t)
            else w in t
        }
    }

    // ================================================================ bookmarks

    class Bookmark(val id: Long, val title: String, val poster: String?, val size: String, val added: Long)

    val bookmarks: List<Bookmark>
        get() = runCatching {
            val a = JSONArray(sp.getString("bookmarks", "[]"))
            List(a.length()) { i ->
                val o = a.getJSONObject(i)
                Bookmark(o.getLong("id"), o.optString("t"), o.optString("p").ifBlank { null }, o.optString("s"), o.optLong("at"))
            }
        }.getOrDefault(emptyList())

    fun isBookmarked(id: Long) = bookmarks.any { it.id == id }

    /** Adds or removes; returns true if it is bookmarked now. */
    fun toggleBookmark(b: Bookmark): Boolean {
        val list = bookmarks.toMutableList()
        val had = list.removeAll { it.id == b.id }
        if (!had) list.add(0, b)
        val a = JSONArray()
        list.forEach { x -> a.put(JSONObject().put("id", x.id).put("t", x.title).put("p", x.poster ?: "").put("s", x.size).put("at", x.added)) }
        sp.edit().putString("bookmarks", a.toString()).apply()
        return !had
    }

    // ================================================================ followed models

    /** [lastId] = the newest torrent already seen; [fresh] = new torrents found and not looked at yet. */
    class Followed(val name: String, var lastId: Long, var fresh: List<Long>, var freshTitles: List<String>, var checked: Long)

    val followed: List<Followed>
        get() = runCatching {
            val a = JSONArray(sp.getString("followed", "[]"))
            List(a.length()) { i ->
                val o = a.getJSONObject(i)
                val f = o.optJSONArray("f") ?: JSONArray()
                val ft = o.optJSONArray("ft") ?: JSONArray()
                Followed(o.getString("n"), o.optLong("last"), List(f.length()) { f.getLong(it) }, List(ft.length()) { ft.getString(it) }, o.optLong("c"))
            }
        }.getOrDefault(emptyList())

    fun saveFollowed(list: List<Followed>) {
        val a = JSONArray()
        list.forEach { x ->
            a.put(JSONObject().put("n", x.name).put("last", x.lastId).put("f", JSONArray(x.fresh)).put("ft", JSONArray(x.freshTitles)).put("c", x.checked))
        }
        sp.edit().putString("followed", a.toString()).apply()
    }

    fun isFollowed(name: String) = followed.any { it.name.equals(name.trim(), ignoreCase = true) }

    /** Follow / unfollow; returns true if followed now. [lastId] = what is already known (no alert for old torrents). */
    fun toggleFollow(name: String, lastId: Long = 0): Boolean {
        val n = name.trim()
        val list = followed.toMutableList()
        val had = list.removeAll { it.name.equals(n, ignoreCase = true) }
        if (!had) list.add(0, Followed(n, lastId, emptyList(), emptyList(), 0))
        saveFollowed(list)
        return !had
    }

    /** The model's new torrents were looked at. */
    fun seen(name: String) {
        val list = followed
        list.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { it.fresh = emptyList(); it.freshTitles = emptyList() }
        saveFollowed(list)
    }
}
