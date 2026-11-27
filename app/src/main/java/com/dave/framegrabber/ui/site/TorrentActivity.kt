package com.dave.framegrabber.ui.site

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.webkit.CookieManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.dave.framegrabber.R
import com.dave.framegrabber.data.AppTheme
import com.dave.framegrabber.data.Prefs
import com.dave.framegrabber.site.Lab
import com.dave.framegrabber.ui.tc
import com.dave.framegrabber.ui.toast
import com.dave.framegrabber.ui.widget.PhotoPager
import com.dave.framegrabber.ui.widget.SynthwaveView
import java.util.concurrent.Executors

/**
 * A torrent of the site in the app's own look: the poster, title, size / seeds / leechers,
 * «⬇ Торрент» and «🧲 Magnet», the frames and pictures (swipe through them), the description.
 */
class TorrentActivity : Activity() {

    companion object {
        private const val EXTRA_ID = "id"
        private const val EXTRA_TITLE = "title"
        /** Result: open this address in the site browser («Открыть на сайте», login). */
        const val EXTRA_OPEN_URL = "openUrl"

        fun intent(ctx: Context, id: Long, title: String) =
            Intent(ctx, TorrentActivity::class.java).putExtra(EXTRA_ID, id).putExtra(EXTRA_TITLE, title)

        /** .torrent → Download/FrameGrabber with the site's login (DownloadManager). */
        fun downloadTorrent(ctx: Context, url: String, name: String) {
            runCatching {
                val file = name.replace(Regex("""[\\/:*?"<>|]"""), " ").take(120).trim() + ".torrent"
                val req = DownloadManager.Request(Uri.parse(url))
                    .addRequestHeader("Cookie", Lab.cookies(url) ?: "")
                    .addRequestHeader("Referer", Lab.BASE)
                    .setMimeType("application/x-bittorrent")
                    .setTitle(file)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "${Prefs.ALBUM}/$file")
                ctx.getSystemService(DownloadManager::class.java).enqueue(req)
                ctx.toast("Торрент скачивается → Download/${Prefs.ALBUM}")
            }.onFailure { ctx.toast("Не удалось: ${it.message}") }
        }
    }

    private val d get() = resources.displayMetrics.density
    private val io = Executors.newFixedThreadPool(3)
    private var id = 0L
    private lateinit var body: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        id = intent.getLongExtra(EXTRA_ID, 0)
        val root = FrameLayout(this).apply { setBackgroundColor(tc(R.color.bg)) }
        val bg = SynthwaveView(this).apply { visibility = View.GONE }
        root.addView(bg, FrameLayout.LayoutParams(-1, -1))
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        AppTheme.apply(this, bg, AppTheme.get(this), animate = false)
        if (Build.VERSION.SDK_INT >= 30) content.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars()); v.setPadding(b.left, b.top, b.right, b.bottom); insets
        }
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding((10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt(), (4 * d).toInt()) }
        fun icon(res: Int, desc: String, click: () -> Unit) = ImageButton(this, null, 0, R.style.IconBtn).apply {
            setImageResource(res); contentDescription = desc
            layoutParams = LinearLayout.LayoutParams((44 * d).toInt(), (44 * d).toInt()).apply { marginStart = (6 * d).toInt() }
            setOnClickListener { click() }
        }
        bar.addView(icon(R.drawable.ic_arrow_back, "Назад") { finish() })
        bar.addView(TextView(this).apply {
            text = "Раздача"; textSize = 17f; setTextColor(tc(R.color.text)); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding((12 * d).toInt(), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        // ☆ bookmark («на потом»)
        star = icon(R.drawable.ic_star, "В закладки") { toggleBookmark() }
        bar.addView(star)
        paintStar()
        bar.addView(icon(R.drawable.ic_web, "Открыть на сайте") { openOnSite("viewtopic.php?t=$id") })
        bar.addView(icon(R.drawable.ic_more, "Ещё") {
            android.app.AlertDialog.Builder(this).setItems(arrayOf("Поделиться ссылкой", "Отчёт для исправлений (страница + картинки)")) { _, i ->
                if (i == 0) startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, "${Lab.BASE}viewtopic.php?t=$id"), "Ссылка на раздачу"))
                else saveReport()
            }.show()
        })
        content.addView(bar)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((14 * d).toInt(), (4 * d).toInt(), (14 * d).toInt(), (28 * d).toInt()) }
        content.addView(ScrollView(this).apply { addView(body); clipToPadding = false }, LinearLayout.LayoutParams(-1, 0, 1f))
        status = TextView(this).apply {
            text = "Загружаю раздачу…"; textSize = 14f; setTextColor(tc(R.color.textDim)); gravity = Gravity.CENTER
            setPadding(0, (40 * d).toInt(), 0, 0)
        }
        body.addView(status)
        load()
    }

    override fun onDestroy() { io.shutdownNow(); super.onDestroy() }

    private fun openOnSite(path: String) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_OPEN_URL, Lab.BASE + path)); finish()
    }

    private var pageHtml = ""
    private lateinit var star: ImageButton
    private val store by lazy { com.dave.framegrabber.site.SiteStore(this) }

    private fun paintStar() {
        val on = store.isBookmarked(id)
        star.setColorFilter(if (on) 0xFFFFC94D.toInt() else tc(R.color.textDim))
        star.contentDescription = if (on) "Убрать из закладок" else "В закладки"
    }

    private fun toggleBookmark() {
        val t = topic
        val on = store.toggleBookmark(com.dave.framegrabber.site.SiteStore.Bookmark(
            id, t?.title ?: intent.getStringExtra(EXTRA_TITLE) ?: "Раздача $id", t?.poster?.thumb, t?.sizeText ?: "", System.currentTimeMillis()))
        paintStar()
        toast(if (on) "★ В закладках — «Сайт» → ★" else "Убрано из закладок")
    }
    private var topic: Lab.Topic? = null

    /**
     * Everything needed to fix the reading of this page: the page itself, the pictures found
     * (small / big / host page) and what the hosts answered → Download/FrameGrabber, then share.
     */
    private fun saveReport() {
        toast("Собираю отчёт…")
        io.execute {
            val t = topic
            val sb = StringBuilder()
            sb.append("=== FrameGrabber: отчёт о раздаче t=$id\n")
            if (t != null) {
                sb.append("poster: ${t.poster?.thumb} | ${t.poster?.full} | ${t.poster?.page}\n")
                (listOfNotNull(t.poster) + t.images).take(6).forEach { img ->
                    val (big, ref) = runCatching { Lab.bigOf(applicationContext, img) }.getOrElse { "ошибка: ${it.message}" to "" }
                    val ok = runCatching { Lab.image(applicationContext, big, 400, ref) != null }.getOrDefault(false)
                    sb.append("img: thumb=${img.thumb}\n     full=${img.full}\n     page=${img.page}\n     big=$big (загрузилась: $ok)\n")
                    img.page?.let { p -> Lab.fetchText(applicationContext, p)?.let { h -> sb.append("     --- страница хостинга (начало):\n").append(h.take(6000)).append("\n") } }
                }
            }
            sb.append("\n=== журнал:\n").append(Lab.bigLog.joinToString("\n"))
            sb.append("\n\n=== страница раздачи:\n").append(pageHtml)
            val r = runCatching {
                com.dave.framegrabber.data.Output.write(this, "pornolab-report-$id.txt", "text/plain", com.dave.framegrabber.data.Output.Kind.DOWNLOAD) { o ->
                    o.stream().use { it.write(sb.toString().toByteArray()) }
                }
            }
            runOnUiThread {
                r.onSuccess { uri ->
                    android.app.AlertDialog.Builder(this).setTitle("Отчёт сохранён")
                        .setMessage("Download/${Prefs.ALBUM}/pornolab-report-$id.txt — пришли его мне в чат.")
                        .setPositiveButton("Отправить") { _, _ ->
                            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Отчёт"))
                        }.setNegativeButton("OK", null).show()
                }.onFailure { toast("Не удалось: ${it.message}") }
            }
        }
    }

    private fun load() {
        io.execute {
            val r = runCatching { Lab.topic(applicationContext, id).also { pageHtml = Lab.lastHtml } }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                r.onSuccess { topic = it; show(it); paintStar() }.onFailure { e ->
                    status.text = if (e is Lab.NeedLogin) "Нужно войти на сайт" else "Не удалось открыть: ${e.message}"
                    body.addView(Button(this, null, 0, R.style.BtnPrimary).apply {
                        text = if (e is Lab.NeedLogin) "Войти на сайте" else "Открыть на сайте"; gravity = Gravity.CENTER
                        setOnClickListener { openOnSite(if (e is Lab.NeedLogin) "login.php" else "viewtopic.php?t=$id") }
                    })
                }
            }
        }
    }

    private fun show(t: Lab.Topic) {
        body.removeAllViews()
        val w = resources.displayMetrics.widthPixels - (28 * d).toInt()
        // poster
        t.poster?.let { p ->
            val box = FrameLayout(this).apply { setBackgroundResource(R.drawable.bg_thumb); clipToOutline = true }
            val iv = ImageView(this).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER }
            box.addView(iv, FrameLayout.LayoutParams(-1, -2))
            val spin = ProgressBar(this)
            box.addView(spin, FrameLayout.LayoutParams((36 * d).toInt(), (36 * d).toInt(), Gravity.CENTER))
            body.addView(box, LinearLayout.LayoutParams(-1, -2).apply { height = -2 })
            box.minimumHeight = (w * 0.6f).toInt()
            box.setOnClickListener { gallery(listOf(p) + t.images, 0) }
            io.execute {
                val (big, ref) = Lab.bigOf(applicationContext, p)
                val b = Lab.image(applicationContext, big, 1400, ref) ?: Lab.image(applicationContext, p.thumb, 1400)
                runOnUiThread { if (!isDestroyed) { spin.visibility = View.GONE; if (b != null) { iv.setImageBitmap(b); box.minimumHeight = 0 } } }
            }
        }
        body.addView(TextView(this).apply {
            text = t.title; textSize = 18f; setTextColor(tc(R.color.text)); setLineSpacing(0f, 1.1f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextIsSelectable(true)
            setPadding(0, (12 * d).toInt(), 0, (2 * d).toInt())
        })
        if (t.forum.isNotBlank()) body.addView(TextView(this).apply { text = t.forum; textSize = 12f; setTextColor(tc(R.color.textDim)) })
        // size · seeds · leechers
        val chips = LinearLayout(this).apply { setPadding(0, (10 * d).toInt(), 0, 0) }
        fun chip(s: String, color: Int) = chips.addView(TextView(this).apply {
            text = s; textSize = 13f; setTextColor(color); typeface = Typeface.DEFAULT_BOLD
            setBackgroundResource(R.drawable.bg_chip)
            setPadding((10 * d).toInt(), (4 * d).toInt(), (10 * d).toInt(), (4 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = (6 * d).toInt() }
        })
        if (t.sizeText.isNotBlank()) chip("💾 ${t.sizeText}", tc(R.color.text))
        if (t.seeds >= 0) chip("↑ ${t.seeds}", 0xFF5CD98A.toInt())
        if (t.leech >= 0) chip("↓ ${t.leech}", 0xFFFF7A7A.toInt())
        body.addView(chips)
        // download
        val btns = LinearLayout(this).apply { setPadding(0, (12 * d).toInt(), 0, 0) }
        btns.addView(Button(this, null, 0, R.style.BtnPrimary).apply {
            text = "⬇ Торрент"; gravity = Gravity.CENTER
            isEnabled = t.torrent != null
            layoutParams = LinearLayout.LayoutParams(0, (50 * d).toInt(), 1f).apply { topMargin = 0 }
            text = if (com.dave.framegrabber.torrent.TorrentMode.get(this@TorrentActivity) == com.dave.framegrabber.torrent.TorrentMode.APP) "⬇ Скачать видео" else "⬇ Торрент"
            setOnClickListener { download(t, magnet = false) }
        })
        btns.addView(Button(this, null, 0, R.style.BtnSecondary).apply {
            text = "🧲 Magnet"; gravity = Gravity.CENTER
            isEnabled = t.magnet != null
            layoutParams = LinearLayout.LayoutParams(0, (50 * d).toInt(), 1f).apply { marginStart = (8 * d).toInt() }
            setOnClickListener { download(t, magnet = true) }
        })
        body.addView(btns)
        if (t.torrent == null && t.magnet == null) body.addView(TextView(this).apply {
            text = "Ссылки на скачивание не нашлись (возможно, нужен вход на сайте)"; textSize = 12f; setTextColor(tc(R.color.warn))
            setPadding(0, (6 * d).toInt(), 0, 0)
        })
        // pictures + frames
        val pics = ArrayList(t.images)
        val picsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val picsTitle = section("Кадры и картинки")
        body.addView(picsTitle); body.addView(picsBox)
        fun fill() {
            picsBox.removeAllViews()
            picsTitle.text = if (pics.isEmpty()) "Картинок нет" else "Кадры и картинки · ${pics.size}"
            val gap = (5 * d).toInt()
            pics.chunked(3).forEachIndexed { ri, row ->
                val line = LinearLayout(this).apply { weightSum = 3f; layoutParams = LinearLayout.LayoutParams(-1, -2).apply { if (ri > 0) topMargin = gap } }
                for (i in 0 until 3) {
                    val img = row.getOrNull(i)
                    val cell = Square(this).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = gap } }
                    if (img != null) {
                        cell.setBackgroundResource(R.drawable.bg_thumb); cell.clipToOutline = true
                        val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                        cell.addView(iv, FrameLayout.LayoutParams(-1, -1))
                        val index = ri * 3 + i
                        cell.setOnClickListener { gallery(pics, index) }
                        io.execute {
                            // the small one at once, then the real picture (sharper in the cell, ready for the gallery)
                            Lab.image(applicationContext, img.thumb, 360)?.let { b -> runOnUiThread { if (!isDestroyed) iv.setImageBitmap(b) } }
                            val (big, ref) = Lab.bigOf(applicationContext, img)
                            if (big != img.thumb) Lab.image(applicationContext, big, 720, ref)?.let { b -> runOnUiThread { if (!isDestroyed) iv.setImageBitmap(b) } }
                        }
                    }
                    line.addView(cell)
                }
                picsBox.addView(line)
            }
        }
        fill()
        if (t.hasFrames) io.execute {
            val r = runCatching { Lab.frames(applicationContext, id) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                r.onSuccess { f -> if (f.isNotEmpty()) { pics.addAll(0, f); fill() } }
                    .onFailure { e -> body.addView(TextView(this).apply { text = "Кадры: ${e.message}"; textSize = 12f; setTextColor(tc(R.color.textDim)) }, body.indexOfChild(picsBox) + 1) }
            }
        }
        // the release info (above the screenshots): label / value rows
        if (t.info.isNotEmpty()) {
            body.addView(section("О раздаче"), body.indexOfChild(picsTitle))
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_row)
                setPadding((14 * d).toInt(), (6 * d).toInt(), (14 * d).toInt(), (6 * d).toInt())
            }
            t.info.forEachIndexed { i, (label, value) ->
                if (i > 0) card.addView(View(this).apply { setBackgroundColor(tc(R.color.stroke)) },
                    LinearLayout.LayoutParams(-1, maxOf(1, (0.7f * d).toInt())))
                card.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
                    if (label.isNotBlank()) addView(TextView(this@TorrentActivity).apply {
                        text = label; textSize = 12f; setTextColor(tc(R.color.accent))
                    })
                    addView(TextView(this@TorrentActivity).apply {
                        text = value; textSize = 15f; setTextColor(tc(R.color.text)); setLineSpacing(0f, 1.1f)
                        setTextIsSelectable(true)
                        setPadding(0, if (label.isNotBlank()) (2 * d).toInt() else 0, 0, 0)
                    })
                })
            }
            body.addView(card, body.indexOfChild(picsTitle))
            // the cast: follow a model (a notification when she has a new torrent)
            val cast = t.info.firstOrNull { (l, _) -> l.contains("ролях", true) || l.contains("модел", true) || l.contains("актрис", true) || l.contains("cast", true) }
                ?.second?.split(Regex("""\s*(?:,|&|/|\band\b|\bи\b)\s*"""))?.map { it.trim() }?.filter { it.length in 2..40 }.orEmpty()
            if (cast.isNotEmpty()) {
                val box = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
                val row = LinearLayout(this).apply { setPadding(0, (8 * d).toInt(), 0, 0) }
                cast.forEach { name ->
                    row.addView(TextView(this).apply {
                        fun paint() {
                            val on = store.isFollowed(name)
                            text = (if (on) "★ " else "☆ ") + name
                            setTextColor(if (on) 0xFFFFC94D.toInt() else tc(R.color.text))
                        }
                        paint()
                        textSize = 13f
                        setBackgroundResource(R.drawable.bg_chip)
                        setPadding((12 * d).toInt(), (6 * d).toInt(), (12 * d).toInt(), (6 * d).toInt())
                        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = (6 * d).toInt() }
                        setOnClickListener { follow(this@TorrentActivity, name); paint() }
                    })
                }
                box.addView(row)
                body.addView(TextView(this).apply {
                    text = "Следить за моделями — уведомлю о новых раздачах:"; textSize = 12f; setTextColor(tc(R.color.textDim))
                    setPadding(0, (10 * d).toInt(), 0, 0)
                }, body.indexOfChild(picsTitle))
                body.addView(box, body.indexOfChild(picsTitle))
            }
        }
        // full description only when the info couldn't be read
        if (t.info.isEmpty() && t.text.isNotBlank()) {
            body.addView(section("Описание"))
            body.addView(TextView(this).apply {
                text = t.text; textSize = 14f; setTextColor(tc(R.color.text)); setLineSpacing(0f, 1.15f)
                setTextIsSelectable(true)
                setBackgroundResource(R.drawable.bg_row)
                setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
            })
        }
    }

    // ================================================================ downloading (Настройки → Торренты)

    private fun download(t: Lab.Topic, magnet: Boolean) {
        val mode = com.dave.framegrabber.torrent.TorrentMode.get(this)
        when {
            mode == com.dave.framegrabber.torrent.TorrentMode.APP -> inApp(t, magnet)
            magnet -> t.magnet?.let { m ->
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(m))) }.onFailure { toast("Нет торрент-приложения для magnet-ссылки") }
            }
            mode == com.dave.framegrabber.torrent.TorrentMode.CLIENT -> toClient(t)
            else -> t.torrent?.let { downloadTorrent(this, it, t.title) }
        }
    }

    /** The .torrent → your torrent app; its Download folder shows up in «Плеер». */
    private fun toClient(t: Lab.Topic) {
        val url = t.torrent ?: return
        toast("Открываю в торрент-приложении…")
        io.execute {
            val r = runCatching {
                val bytes = Lab.bytes(applicationContext, url)
                val name = t.title.replace(Regex("""[\\/:*?"<>|]"""), " ").take(100).trim() + ".torrent"
                com.dave.framegrabber.data.Output.write(this, name, "application/x-bittorrent", com.dave.framegrabber.data.Output.Kind.DOWNLOAD) { o ->
                    o.stream().use { it.write(bytes) }
                }
            }
            runOnUiThread {
                r.onSuccess { uri ->
                    com.dave.framegrabber.player.Library(this).addFolder(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path)
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/x-bittorrent")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    }.onFailure { toast("Нет торрент-приложения. Файл сохранён в Download/${Prefs.ALBUM}") }
                }.onFailure { e -> toast(if (e is Lab.NeedLogin) "Нужно войти на сайте" else "Не удалось: ${e.message}") }
            }
        }
    }

    /** In the app: the .torrent (or the magnet's), pick the files (videos ticked), download. */
    private fun inApp(t: Lab.Topic, magnet: Boolean) {
        val wait = android.app.ProgressDialog(this).apply {
            setMessage(if (magnet) "Получаю данные по magnet-ссылке…" else "Получаю торрент…"); setCancelable(true); show()
        }
        io.execute {
            val r = runCatching {
                if (magnet) com.dave.framegrabber.torrent.TorrentEngine.magnetBytes(applicationContext, t.magnet ?: error("нет magnet"))
                    ?: error("Не удалось получить данные (нет раздающих?)")
                else Lab.bytes(applicationContext, t.torrent ?: error("нет торрента"))
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                wait.dismiss()
                r.onSuccess { bytes -> chooseFiles(t, bytes) }
                    .onFailure { e -> toast(if (e is Lab.NeedLogin) "Нужно войти на сайте" else "Не удалось: ${e.message}") }
            }
        }
    }

    private fun chooseFiles(t: Lab.Topic, bytes: ByteArray) = TorrentsActivity.choose(this, bytes, t.title)

    private fun section(t: String) = TextView(this).apply {
        text = t; textSize = 15f; setTextColor(tc(R.color.accent)); typeface = Typeface.DEFAULT_BOLD
        setPadding(0, (20 * d).toInt(), 0, (8 * d).toInt())
    }

    private fun gallery(list: List<Lab.Img>, start: Int) {
        PhotoPager.show(this, list.size, start,
            preview = { i -> Lab.cachedBig(list[i], 720) ?: Lab.cachedBig(list[i], 1400) ?: Lab.cached(list[i].thumb, 360) ?: Lab.cached(list[i].thumb, 1400) },
            // the real big picture (via the host's page for fastpic & co), else the small one
            full = { i ->
                val (url, ref) = Lab.bigOf(applicationContext, list[i])
                Lab.image(applicationContext, url, 2400, ref) ?: Lab.image(applicationContext, list[i].full, 2400) ?: Lab.image(applicationContext, list[i].thumb, 2400)
            })
    }

    private class Square(c: Context) : FrameLayout(c) {
        override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, w)
    }
}
