package com.dave.framegrabber.telegram

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * Telegram settings: bot token, channel, the hashtag library (in the user's order) and the
 * studios / models used before (for suggestions). SharedPreferences "telegram".
 */
class TgStore(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("telegram", Context.MODE_PRIVATE)

    var token: String get() = sp.getString("token", "") ?: ""; set(v) = sp.edit().putString("token", v.trim()).apply()
    /** "@channelname" or "-100…" id. */
    var chat: String get() = sp.getString("chat", "") ?: ""; set(v) = sp.edit().putString("chat", v.trim()).apply()
    /** Channel title / bot name from the last successful check (shown in settings). */
    var chatTitle: String get() = sp.getString("chatTitle", "") ?: ""; set(v) = sp.edit().putString("chatTitle", v).apply()
    var botName: String get() = sp.getString("botName", "") ?: ""; set(v) = sp.edit().putString("botName", v).apply()
    /** Public channel username (without @) for "open the post" links, if any. */
    var chatUsername: String get() = sp.getString("chatUser", "") ?: ""; set(v) = sp.edit().putString("chatUser", v).apply()

    val configured get() = token.isNotEmpty() && chat.isNotEmpty()

    /** Hashtags without '#', in the order they appear in posts. */
    var hashtags: List<String>
        get() = list("tags")
        set(v) = putList("tags", v)
    var studios: List<String>
        get() = list("studios")
        set(v) = putList("studios", v)
    var models: List<String>
        get() = list("models")
        set(v) = putList("models", v)

    /** The post being written (kept if you leave the editor; cleared after publishing). */
    var draftStudio: String get() = sp.getString("dStudio", "") ?: ""; set(v) = sp.edit().putString("dStudio", v).apply()
    var draftModels: List<String>
        get() = list("dModels")
        set(v) = putList("dModels", v)
    var draftTags: List<String>
        get() = list("dTags")
        set(v) = putList("dTags", v)

    fun clearDraft() { draftStudio = ""; draftModels = emptyList(); draftTags = emptyList() }

    /** Remember a studio / models after a post (most recent first, no duplicates, case-insensitive). */
    fun rememberUsed(studio: String, models: List<String>) {
        fun merge(old: List<String>, new: List<String>) =
            (new + old).distinctBy { it.lowercase() }.filter { it.isNotBlank() }.take(300)
        if (studio.isNotBlank()) studios = merge(studios, listOf(studio.trim()))
        this.models = merge(this.models, models.map { it.trim() })
    }

    private fun list(k: String): List<String> = runCatching {
        val a = JSONArray(sp.getString(k, "[]"))
        (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList())

    private fun putList(k: String, v: List<String>) =
        sp.edit().putString(k, JSONArray().apply { v.forEach { put(it) } }.toString()).apply()
}
