package com.dave.framegrabber.site

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.text.Html
import android.util.LruCache
import android.webkit.CookieManager
import android.webkit.WebSettings
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset

/**
 * pornolab.net read by the app itself (the site's own login, kept by the in-app browser):
 * the tracker search and the torrent pages, turned into plain data for our own screens.
 * The site is TorrentPier (like rutracker): windows-1251 pages, tracker.php / viewtopic.php,
 * pictures as <var class="postImg" title="URL">, «autoscreens» frames from screens_view.php.
 */
object Lab {
    const val HOST = "https://pornolab.net"
    const val BASE = "$HOST/forum/"
    private val CP1251: Charset = Charset.forName("windows-1251")

    class NeedLogin : Exception("Нужно войти на сайт")

    /** The last page read (for «send the page to the developer» when something isn't recognised). */
    @Volatile var lastHtml = ""; private set
    @Volatile var lastUrl = ""; private set

    private var ua: String? = null
    private fun ua(ctx: Context) = ua ?: runCatching { WebSettings.getDefaultUserAgent(ctx) }.getOrDefault("Mozilla/5.0 (Linux; Android)").also { ua = it }

    /** The site's cookies are set for /forum/: always ask for that path (the root gets none of them). */
    fun cookies(url: String = BASE): String? = CookieManager.getInstance().getCookie(if (url.startsWith(BASE)) url else BASE)

    /** A login cookie is there (TorrentPier: bb_data / bb_session; any session-like cookie counts). */
    fun loggedIn(): Boolean = cookies()?.let { c -> Regex("""(?:^|;\s*)bb_[a-z_]+=""").containsMatchIn(c) } == true

    // ================================================================ network

    private fun open(ctx: Context, url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = false
        connectTimeout = 15000; readTimeout = 30000
        setRequestProperty("User-Agent", ua(ctx))
        setRequestProperty("Referer", BASE)
        setRequestProperty("Accept-Language", "ru,en;q=0.8")
        if (url.startsWith(HOST)) cookies(url)?.let { setRequestProperty("Cookie", it) }
    }

    private fun keepCookies(c: HttpURLConnection) {
        c.headerFields["Set-Cookie"]?.forEach { CookieManager.getInstance().setCookie(BASE, it) }
    }

    /** A page of the forum as text; follows redirects; the login page → [NeedLogin]. */
    fun page(ctx: Context, path: String, form: Map<String, String>? = null): String {
        var url = if (path.startsWith("http")) path else BASE + path
        repeat(6) {
            val c = open(ctx, url)
            if (form != null) {
                c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=windows-1251")
                c.setRequestProperty("X-Requested-With", "XMLHttpRequest")
                c.outputStream.use { o -> o.write(form.entries.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "windows-1251") }.toByteArray()) }
            }
            try {
                keepCookies(c)
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: throw IllegalStateException("Сайт: переадресация без адреса")
                    url = URL(URL(url), loc).toString()
                    if ("login.php" in url) throw NeedLogin()
                    return@repeat
                }
                if (code >= 400) throw IllegalStateException("Сайт ответил ошибкой $code")
                val cs = c.contentType?.substringAfter("charset=", "")?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: CP1251
                val text = (c.inputStream.use { it.readBytes() }).toString(cs)
                lastHtml = text; lastUrl = url
                if (form == null && ("login.php" in url || isLoginPage(text))) throw NeedLogin()
                return text
            } finally { c.disconnect() }
        }
        throw IllegalStateException("Сайт: слишком много переадресаций")
    }

    private fun isLoginPage(html: String) = "name=\"login_password\"" in html && "id=\"tor-tbl\"" !in html && "post_body" !in html

    // ================================================================ search

    class Row(
        val id: Long, val title: String, val forum: String,
        val size: Long, val sizeText: String, val seeds: Int, val leech: Int,
        val date: Long, val dateText: String,
    )

    class Results(val rows: List<Row>, val total: Int)

    /** Sort orders of tracker.php (o=…). */
    enum class Sort(val o: Int, val title: String) {
        DATE(1, "По дате"), SEEDS(10, "По сидам"), SIZE(7, "По размеру"), DOWNLOADS(4, "По скачиваниям"), NAME(2, "По названию")
    }

    /** One page (50) of the tracker's results; an empty query = the newest torrents. */
    fun search(ctx: Context, query: String, sort: Sort, desc: Boolean, page: Int): Results {
        val q = buildString {
            append("tracker.php?o=${sort.o}&s=${if (desc) 2 else 1}")
            if (query.isNotBlank()) append("&nm=").append(URLEncoder.encode(query.trim(), "windows-1251"))
            if (page > 0) append("&start=${page * 50}")
        }
        val html = page(ctx, q)
        return Results(parseRows(html), Regex("""Результатов поиска:\s*(\d+)""").find(html)?.groupValues?.get(1)?.toIntOrNull() ?: -1)
    }

    fun parseRows(html: String): List<Row> {
        val out = ArrayList<Row>()
        val seen = HashSet<Long>()
        // rows of the results table
        val table = html.substringAfter("id=\"tor-tbl\"", html)
        for (tr in table.split(Regex("<tr[\\s>]", RegexOption.IGNORE_CASE)).drop(1)) {
            val row = tr.substringBefore("</tr>")
            if ("viewtopic.php?t=" !in row) continue
            val anchors = A.findAll(row).map { Anchor(it.groupValues[1], it.groupValues[2]) }.toList()
            val link = anchors.firstOrNull { it.attrs.contains("tLink") || it.attrs.contains("tt-text") || it.attrs.contains("torTopic") }
                ?: anchors.filter { "viewtopic.php?t=" in it.href }.maxByOrNull { text(it.inner).length } ?: continue
            val id = Regex("""t=(\d+)""").find(link.href)?.groupValues?.get(1)?.toLongOrNull() ?: continue
            if (!seen.add(id)) continue
            val title = text(link.inner)
            if (title.isBlank()) continue
            val forum = anchors.firstOrNull { "tracker.php?f=" in it.href || "viewforum.php?f=" in it.href }?.let { text(it.inner) } ?: ""
            val cells = row.split(Regex("<td[\\s>]", RegexOption.IGNORE_CASE)).drop(1)
            val sizeCell = cells.firstOrNull { "dl.php" in it || "tor-size" in it || "tr-dl" in it } ?: ""
            val size = num(sizeCell)
            val sizeText = anchors.firstOrNull { "dl.php" in it.href || it.attrs.contains("tr-dl") }?.let { text(it.inner) }
                ?.replace("↓", "")?.trim()?.ifBlank { null } ?: text(sizeCell).replace("↓", "").trim()
            val seeds = Regex("""seedmed[^>]*>\s*(?:<b>\s*)?(-?\d+)""").find(row)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""class="[^"]*seed[^"]*"[^>]*>\s*(?:<b>\s*)?(-?\d+)""").find(row)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val leech = Regex("""leechmed[^>]*>\s*(?:<b>\s*)?(\d+)""").find(row)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            // the date: a unix time in <u> or data-ts_text of the last cells
            var date = 0L; var dateText = ""
            for (c in cells.reversed()) {
                val t = Regex("""(?:<u>|data-ts_text=")(1\d{9})""").find(c)?.groupValues?.get(1)?.toLongOrNull()
                if (t != null) { date = t; dateText = Regex("""<p>([^<]+)</p>""").find(c)?.groupValues?.get(1)?.let { text(it) } ?: ""; break }
            }
            out += Row(id, title, forum, size, sizeText, seeds, leech, date, dateText)
        }
        return out
    }

    /** Bytes from <u>123</u> or data-ts_text="123" (the sortable value of a cell). */
    private fun num(cell: String): Long =
        Regex("""(?:<u>|data-ts_text=")(\d+)""").find(cell)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

    // ================================================================ torrent page

    class Img(val thumb: String, val full: String)

    class Topic(
        val id: Long, val title: String, val poster: Img?, val images: List<Img>,
        val text: String, val sizeText: String, val seeds: Int, val leech: Int,
        val magnet: String?, val torrent: String?, val hasFrames: Boolean, val forum: String,
    )

    fun topic(ctx: Context, id: Long): Topic = parseTopic(id, page(ctx, "viewtopic.php?t=$id"))

    fun parseTopic(id: Long, raw: String): Topic {
        val html = Ads.strip(raw)
        val title = Regex("""id="topic-title"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)?.let { text(it) }
            ?: Regex("""<title>(.*?)</title>""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)?.let { text(it).substringBefore(" :: ") } ?: ""
        // the first post = the release description
        val start = html.indexOf("class=\"post_body\"")
        val body = if (start < 0) "" else html.substring(start).let { b ->
            val end = listOf(b.indexOf("class=\"post_body\"", 20), b.indexOf("<!--/post_body"), b.indexOf("class=\"signature")).filter { it > 0 }.minOrNull() ?: b.length
            b.substring(0, end)
        }
        val images = images(body)
        // the poster: an aligned picture or the first one
        val poster = Regex("""<var class="postImg[^"]*Aligned[^"]*"[^>]*title="([^"]+)"""").find(body)?.groupValues?.get(1)
            ?.let { u -> images.firstOrNull { it.thumb == u } ?: Img(u, u) } ?: images.firstOrNull()
        val rest = images.filter { it !== poster && it.thumb != poster?.thumb }
        val sizeText = Regex("""id="tor-size-humn"[^>]*>([^<]+)""").find(html)?.groupValues?.get(1)?.let { text(it) }
            ?: Regex("""Размер:\s*(?:<[^>]+>\s*)*([\d.,]+\s*(?:&nbsp;|\s)?[KMGT]?B)""").find(html)?.groupValues?.get(1)?.let { text(it) } ?: ""
        val seeds = Regex("""class="seed"[^>]*>.*?<b>(\d+)""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""Сиды:\s*(?:<[^>]+>\s*)*(\d+)""").find(html)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        val leech = Regex("""class="leech"[^>]*>.*?<b>(\d+)""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""Личи:\s*(?:<[^>]+>\s*)*(\d+)""").find(html)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        val magnet = Regex("""href="(magnet:[^"]+)"""").find(html)?.groupValues?.get(1)?.let { Html.fromHtml(it, 0).toString() }
        val torrent = Regex("""href="([^"]*dl\.php\?t=\d+[^"]*)"""").find(html)?.groupValues?.get(1)?.let { abs(it) }
        val forum = Regex("""class="nav"[^>]*>(.*?)</""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)
            ?.let { nav -> A.findAll(nav).map { text(it.groupValues[2]) }.lastOrNull() } ?: ""
        return Topic(id, title, poster, rest, bodyText(body), sizeText, seeds, leech, magnet, torrent,
            hasFrames = "ascr" in body && "data-t=" in body, forum = forum)
    }

    /** Pictures of a post: <var class="postImg" title="thumb">, wrapped in a link to the big one if any. */
    fun images(body: String): List<Img> {
        val out = ArrayList<Img>()
        val seen = HashSet<String>()
        // linked: <a href="FULL" class="postLink"><var class="postImg" title="THUMB">
        val linked = HashMap<String, String>()
        Regex("""<a href="([^"]+)"[^>]*>\s*<var class="postImg[^"]*"[^>]*title="([^"]+)"""").findAll(body).forEach {
            linked[it.groupValues[2]] = it.groupValues[1]
        }
        Regex("""<var class="postImg[^"]*"[^>]*title="([^"]+)"""").findAll(body).forEach { m ->
            val thumb = abs(Html.fromHtml(m.groupValues[1], 0).toString())
            if (Ads.isAd(thumb) || !seen.add(thumb)) return@forEach
            val href = linked[m.groupValues[1]]?.let { abs(Html.fromHtml(it, 0).toString()) }
            out += Img(thumb, fullOf(thumb, href))
        }
        Regex("""<img[^>]+src="([^"]+)"""").findAll(body).forEach { m ->
            val u = abs(Html.fromHtml(m.groupValues[1], 0).toString())
            if (u.contains("/smiles/") || u.contains("static.pornolab.net/templates") || Ads.isAd(u) || !seen.add(u)) return@forEach
            out += Img(u, u)
        }
        return out
    }

    /** The big picture: a direct link to an image, or the thumbnail itself (hosts' «view» pages can't be shown). */
    private fun fullOf(thumb: String, href: String?): String {
        if (href != null && Regex("""\.(jpe?g|png|gif|webp)(\?.*)?$""", RegexOption.IGNORE_CASE).containsMatchIn(href)) return href
        // common hosts: thumbs → big
        return thumb.replace("/thumb/", "/big/").takeIf { "fastpic" in thumb } ?: thumb
    }

    /** The «autoscreens» frames of a torrent (shown to logged-in users). */
    fun frames(ctx: Context, id: Long): List<Img> {
        val r = page(ctx, "screens_view.php", mapOf("t" to id.toString()))
        val json = runCatching { org.json.JSONObject(r) }.getOrNull() ?: return emptyList()
        if (!json.optBoolean("ok")) throw IllegalStateException(text(json.optString("error", "кадры недоступны")))
        val html = json.optString("html")
        val out = ArrayList<Img>()
        Regex("""<a[^>]+href="([^"]+)"[^>]*>\s*<img[^>]+src="([^"]+)"""").findAll(html).forEach {
            out += Img(abs(Html.fromHtml(it.groupValues[2], 0).toString()), abs(Html.fromHtml(it.groupValues[1], 0).toString()))
        }
        if (out.isEmpty()) out += images(html)
        return out
    }

    // ================================================================ text helpers

    private class Anchor(val attrs: String, val inner: String) {
        val href get() = Regex("""href="([^"]*)"""").find(attrs)?.groupValues?.get(1)?.replace("&amp;", "&") ?: ""
    }
    private val A = Regex("""<a\s([^>]*)>(.*?)</a>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    fun text(html: String): String =
        Html.fromHtml(html.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n"), Html.FROM_HTML_MODE_LEGACY).toString()
            .replace(' ', ' ').replace(Regex("[ \t]+"), " ").trim()

    /** The description as readable text: line breaks kept, spoiler titles as headings, pictures dropped. */
    private fun bodyText(body: String): String {
        var b = body.substringAfter('>')
        b = b.replace(Regex("""<script.*?</script>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
        b = b.replace(Regex("""<var class="postImg[^>]*>.*?</var>""", RegexOption.DOT_MATCHES_ALL), "")
        b = b.replace(Regex("""<div class="sp-(?:title|head)[^"]*"[^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)) { "\n\n▸ " + text(it.groupValues[1]) + "\n" }
        b = b.replace(Regex("""<(?:/div|/p|hr|span class="post-br")[^>]*>""", RegexOption.IGNORE_CASE), "\n")
        return text(b).lines().map { it.trim() }.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim().take(6000)
    }

    private fun abs(u: String): String = when {
        u.startsWith("//") -> "https:$u"
        u.startsWith("http") -> u
        u.startsWith("/") -> HOST + u
        else -> BASE + u.removePrefix("./")
    }

    // ================================================================ pictures

    private val cache = object : LruCache<String, Bitmap>(40 * 1024 * 1024) {
        override fun sizeOf(k: String, b: Bitmap) = b.byteCount
    }

    fun cached(url: String, max: Int): Bitmap? = cache.get("$max|$url")

    /** A picture from the web, fitted into [max] px (blocking). */
    fun image(ctx: Context, url: String, max: Int): Bitmap? {
        cache.get("$max|$url")?.let { return it }
        val bytes = runCatching {
            var u = url
            var data: ByteArray? = null
            repeat(4) {
                if (data != null) return@repeat
                val c = open(ctx, u)
                try {
                    val code = c.responseCode
                    if (code in 300..399) { u = URL(URL(u), c.getHeaderField("Location") ?: return@runCatching null).toString() }
                    else if (code in 200..299) data = c.inputStream.use { inp -> ByteArrayOutputStream().also { inp.copyTo(it) }.toByteArray() }
                    else return@runCatching null
                } finally { c.disconnect() }
            }
            data
        }.getOrNull() ?: return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0) return null
        var s = 1
        while (o.outWidth / (s * 2) >= max && o.outHeight / (s * 2) >= max) s *= 2
        val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
        cache.put("$max|$url", b)
        return b
    }

    /** Readable size from bytes. */
    fun size(b: Long): String = when {
        b <= 0 -> ""
        b >= 1L shl 30 -> String.format(java.util.Locale.US, "%.2f ГБ", b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> String.format(java.util.Locale.US, "%.0f МБ", b / (1L shl 20).toDouble())
        else -> String.format(java.util.Locale.US, "%.0f КБ", b / 1024.0)
    }
}
