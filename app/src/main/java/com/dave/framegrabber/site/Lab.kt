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

    private fun open(ctx: Context, url: String, referer: String? = BASE): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = false
        connectTimeout = 15000; readTimeout = 30000
        setRequestProperty("User-Agent", ua(ctx))
        if (!referer.isNullOrEmpty()) setRequestProperty("Referer", referer)
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

    /** A file of the site with the login (e.g. a .torrent), as bytes. */
    fun bytes(ctx: Context, url: String): ByteArray {
        var u = url
        repeat(5) {
            val c = open(ctx, u)
            try {
                keepCookies(c)
                val code = c.responseCode
                if (code in 300..399) {
                    u = URL(URL(u), c.getHeaderField("Location") ?: throw IllegalStateException("переадресация без адреса")).toString()
                    if ("login.php" in u) throw NeedLogin()
                    return@repeat
                }
                if (code >= 400) throw IllegalStateException("Сайт ответил ошибкой $code")
                val b = c.inputStream.use { it.readBytes() }
                if (b.isNotEmpty() && b[0] == '<'.code.toByte()) throw IllegalStateException("Вместо торрента пришла страница (нужен вход?)")
                return b
            } finally { c.disconnect() }
        }
        throw IllegalStateException("слишком много переадресаций")
    }

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

    /** A picture: its small version, a direct big one if known, and the host's page of it (fastpic «view»…). */
    class Img(val thumb: String, val full: String, val page: String? = null)

    class Topic(
        val id: Long, val title: String, val poster: Img?, val images: List<Img>,
        val text: String, val sizeText: String, val seeds: Int, val leech: Int,
        val magnet: String?, val torrent: String?, val hasFrames: Boolean, val forum: String,
        /** The release info above the screenshots: «В ролях: …», «Жанр: …» (label "" = a plain line). */
        val info: List<Pair<String, String>> = emptyList(),
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
            hasFrames = "ascr" in body && "data-t=" in body, forum = forum, info = info(body))
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
            out += Img(thumb, fullOf(thumb, href), href?.takeIf { !isImageUrl(it) })
        }
        Regex("""<img[^>]+src="([^"]+)"""").findAll(body).forEach { m ->
            val u = abs(Html.fromHtml(m.groupValues[1], 0).toString())
            if (u.contains("/smiles/") || u.contains("static.pornolab.net/templates") || Ads.isAd(u) || !seen.add(u)) return@forEach
            out += Img(u, u)
        }
        return out
    }

    /** The big picture: a direct link to an image, or the thumbnail itself (hosts' «view» pages can't be shown). */
    private fun isImageUrl(u: String) = Regex("""\.(jpe?g|png|gif|webp)(\?.*)?$""", RegexOption.IGNORE_CASE).containsMatchIn(u)

    private fun fullOf(thumb: String, href: String?): String {
        if (href != null && isImageUrl(href)) return href
        // hosts where the big picture's address follows from the small one
        Regex("""^https?://thumbs(\d*)\.imgbox\.com/(.+)_t\.(\w+)$""").find(thumb)?.let { m ->
            return "https://images${m.groupValues[1]}.imgbox.com/${m.groupValues[2]}_o.${m.groupValues[3]}"
        }
        Regex("""^https?://t(\d+)\.pixhost\.to/thumbs/(.+)$""").find(thumb)?.let { m ->
            return "https://img${m.groupValues[1]}.pixhost.to/images/${m.groupValues[2]}"
        }
        return thumb
    }

    private val bigCache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()

    /** What happened with the last big-picture lookups (for the report). */
    val bigLog = java.util.concurrent.ConcurrentLinkedDeque<String>()

    /**
     * A page of another site (an image host), following its redirects ourselves — also
     * http → https and fastpic.ru → fastpic.org, which Java won't follow on its own.
     */
    /** Cookies of image hosts (fastpic gives the picture page only to the visitor it greeted). */
    private val hostCookies = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<String, String>>()

    private fun siteOf(host: String) = host.split('.').takeLast(2).joinToString(".")

    fun fetchText(ctx: Context, url: String, referer: String = BASE): String? {
        var u = url.replaceFirst(Regex("^http://"), "https://")
        repeat(6) {
            val site = siteOf(URL(u).host)
            val jar = hostCookies.getOrPut(site) { java.util.concurrent.ConcurrentHashMap() }
            val c = (URL(u).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15000; readTimeout = 20000
                setRequestProperty("User-Agent", ua(ctx)); setRequestProperty("Referer", referer)
                if (jar.isNotEmpty()) setRequestProperty("Cookie", jar.entries.joinToString("; ") { "${it.key}=${it.value}" })
                setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
                setRequestProperty("Accept-Language", "ru,en;q=0.8")
            }
            try {
                val code = c.responseCode
                c.headerFields["Set-Cookie"]?.forEach { h ->
                    h.substringBefore(';').split('=', limit = 2).takeIf { it.size == 2 }?.let { (k, v) -> jar[k.trim()] = v.trim() }
                }
                if (code in 300..399) {
                    u = URL(URL(u), c.getHeaderField("Location") ?: return null).toString().replaceFirst(Regex("^http://"), "https://")
                    return@repeat
                }
                if (code !in 200..299) { bigLog.add("$url → HTTP $code"); return null }
                val cs = c.contentType?.substringAfter("charset=", "")?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
                return c.inputStream.use { it.readBytes() }.toString(cs).also { if (bigLog.size < 40) bigLog.add("$url → ${it.length} симв.") }
            } finally { c.disconnect() }
        }
        return null
    }

    /**
     * The real full-size picture and the page to send as Referer. Image hosts like fastpic.org
     * only give the big picture through their «view» page (a signed address): that page is read
     * and the big picture's address taken from it. Blocking.
     */
    fun bigOf(ctx: Context, img: Img): Pair<String, String> {
        val page = img.page ?: return img.full to BASE
        bigCache[page]?.let { return it }
        val found = runCatching {
            var html = fetchText(ctx, page) ?: return@runCatching null
            val un = { u: String -> Html.fromHtml(u, 0).toString().let { if (it.startsWith("//")) "https:$it" else it } }
            // fastpic first shows «Изображение готово к просмотру» with a «Перейти к изображению» link
            // (the same page + a one-time ?xxxx=… key): follow it, with the cookies it just set
            // the picture's own name (fastpic pages also carry decoy pictures of other people)
            val name = URL(page).path.substringAfterLast('/').substringBefore('.')
            val bigRe = Regex("""(https?:)?//i\d+\.fastpic\.(?:org|ru)/big/[^"'\s<>]+""")
            fun ownBig(h: String) = bigRe.findAll(h).map { it.value }.firstOrNull { name.isNotEmpty() && name in it }
            if (ownBig(html) == null) {
                val path = URL(page).path
                Regex("""href="([^"]*${Regex.escape(path)}\?[^"]+)"""").find(html)?.groupValues?.get(1)?.let(un)?.let { next ->
                    fetchText(ctx, URL(URL(page), next).toString(), referer = page)?.let { html = it }
                }
            }
            // fastpic: https://iNN.fastpic.org/big/…?md5=…&expires=…
            ownBig(html)?.let(un)
                ?: Regex("""<meta[^>]+property="og:image"[^>]+content="([^"]+)"""").find(html)?.groupValues?.get(1)?.let(un)
                ?: Regex("""<img[^>]+(?:id|class)="[^"]*(?:main|image|picture|big|full|show)[^"]*"[^>]+src="([^"]+)"""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.let(un)
                ?: Regex("""<img[^>]+src="([^"]+)"[^>]+(?:id|class)="[^"]*(?:main|image|picture|big|full|show)[^"]*"""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.let(un)
        }.getOrNull()?.takeIf { it.startsWith("http") && !Ads.isAd(it) }
        // the big picture loads without a Referer (with the page as Referer fastpic answers 404)
        val r = (found ?: img.full) to (if (found != null) "" else BASE)
        bigLog.add("$page ⇒ ${found ?: "не найдено, беру " + img.full}")
        while (bigLog.size > 60) bigLog.pollFirst()
        bigCache[page] = r
        return r
    }

    /** The «autoscreens» frames of a torrent (shown to logged-in users). */
    fun frames(ctx: Context, id: Long): List<Img> {
        val r = page(ctx, "screens_view.php", mapOf("t" to id.toString()))
        val json = runCatching { org.json.JSONObject(r) }.getOrNull() ?: return emptyList()
        if (!json.optBoolean("ok")) throw IllegalStateException(text(json.optString("error", "кадры недоступны")))
        val html = json.optString("html")
        val out = ArrayList<Img>()
        Regex("""<a[^>]+href="([^"]+)"[^>]*>\s*<img[^>]+src="([^"]+)"""").findAll(html).forEach {
            val thumb = abs(Html.fromHtml(it.groupValues[2], 0).toString())
            val href = abs(Html.fromHtml(it.groupValues[1], 0).toString())
            if (!Ads.isAd(thumb)) out += Img(thumb, fullOf(thumb, href), href.takeIf { h -> !isImageUrl(h) })
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
        Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString()
            .replace(' ', ' ').replace(Regex("[ \t]+"), " ").trim()

    /**
     * The info of the release up to the screenshots, line by line: «Label: value» pairs
     * (labels are the bold part before «:»), other lines as they are.
     */
    fun info(body: String): List<Pair<String, String>> {
        var b = body.substringAfter("post-user-message", body).substringAfter('>')
        b = b.substringBefore("class=\"sp-wrap\"").substringBefore("<div class=\"sp-")
        b = b.replace(Regex("""<var class="postImg[^>]*>.*?</var>""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""<script.*?</script>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
        val out = ArrayList<Pair<String, String>>()
        for (piece in b.split(Regex("""<br\s*/?>|<span class="post-br">|</div>|<div[^>]*>|<hr[^>]*>""", RegexOption.IGNORE_CASE))) {
            val t = text(piece).trim().trim(':').trim()
            if (t.isBlank() || t.length < 2) continue
            val bold = Regex("""<span class="post-b">(.*?)</span>""", RegexOption.DOT_MATCHES_ALL).find(piece)?.groupValues?.get(1)?.let { text(it).trim() }
            val colon = t.indexOf(':')
            if (bold != null && colon in 1..60 && t.startsWith(bold.trimEnd(':'))) {
                out += t.substring(0, colon).trim() to t.substring(colon + 1).trim()
            } else out += "" to t
        }
        return out.filter { it.second.isNotBlank() || it.first.isNotBlank() }.take(40)
    }

    /** The description as readable text: line breaks kept, spoiler titles as headings, pictures dropped. */
    private fun bodyText(body: String): String {
        var b = body.substringAfter('>')
        b = b.replace(Regex("""<script.*?</script>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
        b = b.replace(Regex("""<var class="postImg[^>]*>.*?</var>""", RegexOption.DOT_MATCHES_ALL), "")
        b = b.replace(Regex("""<(?:div|h3) class="sp-(?:title|head)[^"]*"[^>]*>(.*?)</(?:div|h3)>""", RegexOption.DOT_MATCHES_ALL)) { "<br><br>▸ " + it.groupValues[1] + "<br>" }
        // (fromHtml turns plain line breaks into spaces: keep them as <br>)
        b = b.replace(Regex("""<(?:/div|/p|hr)[^>]*>""", RegexOption.IGNORE_CASE), "<br>")
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

    /** The real picture if it's already loaded for a grid cell. */
    fun cachedBig(img: Img, max: Int): Bitmap? = (img.page?.let { bigCache[it]?.first } ?: img.full).let { cache.get("$max|$it") }

    /** A picture from the web, fitted into [max] px (blocking). */
    /** [referer] "" = send none. */
    fun image(ctx: Context, url: String, max: Int, referer: String = BASE): Bitmap? {
        cache.get("$max|$url")?.let { return it }
        val bytes = runCatching {
            var u = url.replaceFirst(Regex("^http://"), "https://")
            var data: ByteArray? = null
            repeat(4) {
                if (data != null) return@repeat
                val c = open(ctx, u, referer)
                try {
                    val code = c.responseCode
                    if (code in 300..399) { u = URL(URL(u), c.getHeaderField("Location") ?: return@runCatching null).toString().replaceFirst(Regex("^http://"), "https://") }
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
