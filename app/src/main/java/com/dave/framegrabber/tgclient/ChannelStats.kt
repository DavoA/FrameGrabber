package com.dave.framegrabber.tgclient

import android.content.Context
import org.drinkless.tdlib.TdApi
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Every post of the channel, read through the app's Telegram login (so 18+ channels work too),
 * split into studio / models / tags from the caption:
 *     Studio - Model A & Model B
 *     ————————————————
 *     #tag1 #tag2
 * A post = a captioned message or album; a video posted right after it without a caption counts
 * as that post's video. Saved on the phone, so the next update reads only the new messages.
 */
object ChannelStats {

    class Post(
        val id: Long, val time: Long, val album: Long,
        val studio: String, val models: List<String>, val tags: List<String>,
        var video: Boolean,
    )

    private fun file(ctx: Context, chatId: Long) = File(ctx.filesDir, "channel_stats_$chatId.json")

    fun load(ctx: Context, chatId: Long): MutableList<Post> = runCatching {
        val a = JSONArray(file(ctx, chatId).readText())
        MutableList(a.length()) { i ->
            val o = a.getJSONObject(i)
            Post(
                o.getLong("id"), o.getLong("t"), o.optLong("al"),
                o.optString("s"), o.optJSONArray("m").strings(), o.optJSONArray("g").strings(),
                o.optBoolean("v"),
            )
        }
    }.getOrDefault(mutableListOf())

    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else List(length()) { getString(it) }

    private fun save(ctx: Context, chatId: Long, posts: List<Post>) {
        val a = JSONArray()
        posts.forEach { p ->
            a.put(JSONObject().put("id", p.id).put("t", p.time).put("al", p.album).put("s", p.studio)
                .put("m", JSONArray(p.models)).put("g", JSONArray(p.tags)).put("v", p.video))
        }
        val f = file(ctx, chatId)
        val tmp = File(f.path + ".tmp"); tmp.writeText(a.toString()); tmp.renameTo(f)
    }

    /**
     * Reads the messages newer than the saved ones (all of them the first time). Blocking —
     * background thread only. [progress] gets the number of messages read so far.
     */
    fun update(ctx: Context, chatId: Long, progress: (Int) -> Unit): List<Post> {
        val posts = load(ctx, chatId)
        val known = posts.lastOrNull()?.id ?: 0L
        val fresh = ArrayList<TdApi.Message>()
        var from = 0L
        var emptyTries = 0
        loop@ while (true) {
            val r = Tg.call(TdApi.GetChatHistory(chatId, from, 0, 100, false), 60)
            val ms = (r as? TdApi.Messages)?.messages ?: throw IllegalStateException(Tg.errorOf(r) ?: "Telegram не ответил")
            if (ms.isEmpty()) { if (++emptyTries >= 2) break else continue }   // TDLib sometimes needs a second ask
            emptyTries = 0
            for (m in ms) {
                if (m.id <= known) break@loop
                fresh += m
            }
            from = ms.last().id
            progress(fresh.size)
        }
        var cur = posts.lastOrNull()
        fresh.sortedBy { it.id }.forEach { m ->
            val caption = caption(m.content)
            val isVideo = isVideo(m.content)
            if (!caption.isNullOrBlank()) {
                if (m.mediaAlbumId != 0L && cur?.album == m.mediaAlbumId) { if (isVideo) cur?.video = true; return@forEach }
                val (s, mo, t) = parse(caption)
                cur = Post(m.id, m.date * 1000L, m.mediaAlbumId, s, mo, t, isVideo).also { posts += it }
            } else if (isVideo) cur?.video = true
        }
        save(ctx, chatId, posts)
        return posts
    }

    private fun caption(c: TdApi.MessageContent): String? = when (c) {
        is TdApi.MessageText -> c.text.text
        is TdApi.MessagePhoto -> c.caption.text
        is TdApi.MessageVideo -> c.caption.text
        is TdApi.MessageAnimation -> c.caption.text
        is TdApi.MessageDocument -> c.caption.text
        else -> null
    }

    private fun isVideo(c: TdApi.MessageContent) =
        c is TdApi.MessageVideo || (c is TdApi.MessageDocument && c.document.mimeType.startsWith("video/"))

    /** Studio, models, tags from a caption. */
    fun parse(text: String): Triple<String, List<String>, List<String>> {
        val tags = Regex("""#([\p{L}\p{N}_]+)""").findAll(text).map { it.groupValues[1] }.distinct().toList()
        val first = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: ""
        if (first.all { it == '-' || it == '—' || it == '–' || it == ' ' }) return Triple("", emptyList(), tags)
        val sep = Regex("""\s+[-–—]\s+""").find(first) ?: return Triple("", emptyList(), tags)
        val studio = first.substring(0, sep.range.first).trim()
        val models = first.substring(sep.range.last + 1).split("&").map { it.trim() }.filter { it.isNotEmpty() }
        return Triple(studio, models, tags)
    }
}
