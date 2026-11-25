package com.dave.framegrabber.telegram

import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Reads a PUBLIC channel's posts from its web page (t.me/s/<name>), the same page anyone sees in a
 * browser: no bot or login needed. ~20 posts per page, going back with ?before=<id>.
 *
 * Captions in the app's shape are split into studio / models / tags:
 *     Studio - Model A & Model B
 *     ————————————————
 *     #tag1 #tag2
 * Posts with other text are kept too (they count in totals; studio/models stay empty).
 */
class ChannelImport(private val channel: String, private val base: String = "https://t.me") {

    /** One message on the page. */
    class Msg(val id: Long, val time: Long, val text: String)

    /**
     * Goes back through the channel from the newest post, until a post with id ≤ [stopAtId]
     * (0 = the whole channel). [onPage] gets the posts found so far and may return false to stop.
     */
    fun readAll(stopAtId: Long, onPage: (total: Int, oldestId: Long) -> Boolean): List<Msg> {
        val out = LinkedHashMap<Long, Msg>()
        var before: Long? = null
        while (true) {
            val html = fetch("$base/s/$channel" + (before?.let { "?before=$it" } ?: ""))
            val page = parse(html)
            if (page.isEmpty()) {
                // no posts: the start of the channel — or no such public channel
                if (before == null && !html.contains("tgme_channel_info")) {
                    // the channel exists (its card is shown) but Telegram hides its posts on the web:
                    // that's what it does for 18+ channels
                    if (html.contains("tgme_page_title") || html.contains("tgme_page_extra"))
                        throw IllegalStateException("Telegram не показывает посты @$channel в веб-версии (так он делает с каналами 18+), поэтому прочитать историю отсюда нельзя.")
                    throw IllegalStateException("Канал @$channel не найден")
                }
                break
            }
            var reachedKnown = false
            page.forEach { m ->
                if (m.id <= stopAtId) reachedKnown = true else out[m.id] = m
            }
            val oldest = page.minOf { it.id }
            if (!onPage(out.size, oldest) || reachedKnown || oldest <= 1) break
            if (before != null && oldest >= before) break   // no progress: stop instead of looping
            before = oldest
            Thread.sleep(250)   // be gentle with t.me
        }
        return out.values.sortedBy { it.id }
    }

    private fun fetch(url: String): String {
        var wait = 2000L
        repeat(4) { attempt ->
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 15_000; c.readTimeout = 20_000
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) FrameGrabber")
                c.setRequestProperty("Accept-Language", "ru,en")
                val code = c.responseCode
                if (code == 200) return c.inputStream.bufferedReader().use { it.readText() }
                if (code == 429 || code >= 500) { Thread.sleep(wait); wait *= 2; return@repeat }
                throw IllegalStateException("t.me ответил кодом $code")
            } finally {
                c.disconnect()
            }
        }
        throw IllegalStateException("t.me не отвечает (слишком много запросов) — попробуй позже")
    }

    companion object {
        private val ISO = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US) }

        /** Messages on one t.me/s page. */
        fun parse(html: String): List<Msg> {
            val out = LinkedHashMap<Long, Msg>()
            // each message starts with data-post="channel/123"
            val starts = Regex("""data-post="[^"/]+/(\d+)"""").findAll(html).toList()
            starts.forEachIndexed { i, m ->
                val end = if (i + 1 < starts.size) starts[i + 1].range.first else html.length
                val chunk = html.substring(m.range.first, end)
                val id = m.groupValues[1].toLongOrNull() ?: return@forEachIndexed
                val time = Regex("""<time[^>]*datetime="([^"]+)"""").find(chunk)?.groupValues?.get(1)
                    ?.let { runCatching { ISO.get()!!.parse(it)!!.time }.getOrNull() } ?: 0L
                val text = messageText(chunk)
                // the same id can appear twice in a message's markup: keep what has text and a date
                val prev = out[id]
                out[id] = Msg(id, if (time != 0L) time else prev?.time ?: 0L, text.ifEmpty { prev?.text ?: "" })
            }
            return out.values.toList()
        }

        /**
         * Page messages -> posts. Messages without text (separate photos of an album, a video
         * posted after the album) are not posts; the same text again within a few messages (the
         * video with the post's caption) counts once.
         */
        fun toPosts(chat: String, msgs: List<Msg>): List<PostLog.Post> {
            val out = ArrayList<PostLog.Post>()
            var lastText = ""; var lastId = -100L
            for (m in msgs.sortedBy { it.id }) {
                if (m.text.isBlank()) continue
                if (m.text == lastText && m.id - lastId <= 15) continue
                lastText = m.text; lastId = m.id
                val (studio, models, tags) = parseCaption(m.text)
                out += PostLog.Post(
                    m.time, "@$chat", m.id, "https://t.me/$chat/${m.id}", studio, models, tags,
                    "", "", emptyList(), source = "channel",
                )
            }
            return out
        }

        /** The message's own text (not a quoted reply), as plain text with line breaks. */
        private fun messageText(chunk: String): String {
            val open = Regex("""<div class="tgme_widget_message_text[^"]*"[^>]*>""").findAll(chunk)
                .lastOrNull() ?: return ""   // a reply quote comes first; the post's own text is the last one
            // find the matching </div> (the text may contain nested <div>s rarely)
            var depth = 1
            var pos = open.range.last + 1
            val tag = Regex("""<(/?)div\b[^>]*>""")
            var endAt = chunk.length
            while (depth > 0) {
                val t = tag.find(chunk, pos) ?: break
                if (t.groupValues[1].isEmpty()) depth++ else depth--
                if (depth == 0) endAt = t.range.first
                pos = t.range.last + 1
            }
            return toPlain(chunk.substring(open.range.last + 1, endAt))
        }

        fun toPlain(h: String): String = h
            .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("""<[^>]+>"""), "")
            .replace("&nbsp;", " ").replace("&quot;", "\"").replace("&#39;", "'")
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace(Regex("""&#(\d+);""")) { it.groupValues[1].toInt().toChar().toString() }
            .replace(Regex("""&#x([0-9a-fA-F]+);""")) { String(Character.toChars(it.groupValues[1].toInt(16))) }
            .replace("&amp;", "&")
            .trim()

        /** Studio, models, tags from a caption (see the class comment). */
        fun parseCaption(text: String): Triple<String, List<String>, List<String>> {
            val tags = Regex("""#([\p{L}\p{N}_]+)""").findAll(text).map { it.groupValues[1] }.distinct().toList()
            val first = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: ""
            // "Studio - A & B" (also – and —); a line of dashes is not a title
            if (first.all { it == '-' || it == '—' || it == '–' || it == ' ' }) return Triple("", emptyList(), tags)
            val sep = Regex("""\s+[-–—]\s+""").find(first)
                ?: return Triple("", emptyList(), tags)
            val studio = first.substring(0, sep.range.first).trim()
            val models = first.substring(sep.range.last + 1).split("&").map { it.trim() }.filter { it.isNotEmpty() }
            return Triple(studio, models, tags)
        }

        /** Channel name from "@name", "t.me/name", "https://t.me/s/name" or "name". */
        fun channelName(raw: String): String? {
            val s = raw.trim().removePrefix("https://").removePrefix("http://").removePrefix("www.")
                .removePrefix("t.me/").removePrefix("telegram.me/").removePrefix("s/").removePrefix("@")
                .substringBefore('/').substringBefore('?')
            return s.takeIf { it.matches(Regex("[A-Za-z0-9_]{4,64}")) }
        }
    }
}
