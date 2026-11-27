package com.dave.framegrabber.ui.main

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.dave.framegrabber.R
import com.dave.framegrabber.data.Prefs
import com.dave.framegrabber.ui.toast
import com.dave.framegrabber.ui.tc

/**
 * Page «Сайт»: pornolab.net in the app's own look — the tracker search with results as cards
 * (poster, size, seeds, date; tap → the torrent page, ui/site/TorrentActivity). Everything else
 * (login, the rest of the forum) opens as the site itself in the built-in browser below: The login stays (cookies are kept), the last page is
 * reopened, «Назад» goes back on the site, .torrent files go to Download/FrameGrabber and
 * magnet links open in your torrent app. Pages are fitted to the phone and drawn dark.
 */
class SiteTab(private val s: MainScreen) {

    companion object {
        const val HOME = "https://pornolab.net/forum/index.php"
        private const val KEY_LAST = "siteLast"
    }

    private val act = s.act
    private val host = s.h<FrameLayout>(R.id.siteHost)
    private val progress = s.h<ProgressBar>(R.id.siteProgress)
    private val title = s.h<TextView>(R.id.txtSiteTitle)
    private val urlText = s.h<TextView>(R.id.txtSiteUrl)
    private val prefs get() = act.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
    /** Made the first time the page is opened (a WebView is heavy). */
    private var web: WebView? = null
    private val nativeBox = s.h<View>(R.id.siteNative)
    private val webBox = s.h<View>(R.id.siteWebBox)
    private val searchEdit = s.h<android.widget.EditText>(R.id.editSiteSearch)
    private val status = s.h<TextView>(R.id.txtSiteStatus)
    private val loginBtn = s.h<View>(R.id.btnSiteLogin)
    private val list = s.h<android.widget.ListView>(R.id.siteList)
    private val rows = ArrayList<com.dave.framegrabber.site.Lab.Row>()
    private var query = ""
    private var page = 0
    private var total = -1
    private var loading = false
    private var end = false
    private var started = false
    /** Opened the site for logging in: once logged in, back to the search. */
    private var backAfterLogin = false
    private var sort = com.dave.framegrabber.site.Lab.Sort.DATE
    private val io = java.util.concurrent.Executors.newFixedThreadPool(3)
    private val adapter = Adapter()
    /** Posters of the results (read from each torrent's page, as they come into view). */
    private val posters = HashMap<Long, String?>()
    private val askedPoster = HashSet<Long>()
    private val d = act.resources.displayMetrics.density

    init {
        s.h<View>(R.id.btnSiteBack).setOnClickListener { if (!back()) showNative() }
        s.h<View>(R.id.btnSiteHome).setOnClickListener { showNative() }
        s.h<View>(R.id.btnSiteWeb).setOnClickListener { showWeb(null) }
        s.h<View>(R.id.btnSiteSort).setOnClickListener { chooseSort() }
        s.h<View>(R.id.btnSiteSaved).setOnClickListener {
            act.startActivityForResult(com.dave.framegrabber.ui.site.SavedActivity.intent(act), com.dave.framegrabber.MainActivity.REQ_TORRENT)
        }
        loginBtn.setOnClickListener { showWeb(com.dave.framegrabber.site.Lab.BASE + "login.php") }
        searchEdit.setOnEditorActionListener { _, _, _ -> search(searchEdit.text.toString()); true }
        list.adapter = adapter
        list.setOnScrollListener(object : android.widget.AbsListView.OnScrollListener {
            override fun onScrollStateChanged(v: android.widget.AbsListView, st: Int) {}
            override fun onScroll(v: android.widget.AbsListView, first: Int, count: Int, totalItems: Int) {
                if (totalItems > 0 && first + count >= totalItems - 3) more()
            }
        })
        list.setOnItemClickListener { _, _, pos, _ ->
            rows.getOrNull(pos)?.let { r ->
                act.startActivityForResult(com.dave.framegrabber.ui.site.TorrentActivity.intent(act, r.id, r.title), com.dave.framegrabber.MainActivity.REQ_TORRENT)
            }
        }
        s.h<View>(R.id.btnSiteRefresh).setOnClickListener { web?.reload() }
        s.h<View>(R.id.btnSiteMenu).setOnClickListener { menu() }
    }

    fun onShown() {
        if (!started) { started = true; search("") }
    }

    /** «Назад»: on the site a page back, then from the site to the search. */
    fun back(): Boolean {
        if (webBox.visibility != View.VISIBLE) return false
        val w = web
        if (w != null && w.canGoBack()) { w.goBack(); return true }
        showNative()
        return true
    }

    private fun showNative() {
        webBox.visibility = View.GONE
        nativeBox.visibility = View.VISIBLE
        // came back (e.g. after logging in): try again
        if (rows.isEmpty() && !loading) search(query)
    }

    /** The site itself (for login and everything the app screens don't have). */
    fun showWeb(url: String?) {
        if (url != null && "login.php" in url) backAfterLogin = true
        nativeBox.visibility = View.GONE
        webBox.visibility = View.VISIBLE
        if (web == null) create(url) else if (url != null) web?.loadUrl(url)
    }

    // ================================================================ search (app screens)

    fun search(q: String) {
        started = true
        if (searchEdit.text.toString() != q.trim()) searchEdit.setText(q.trim())
        query = q.trim(); page = 0; total = -1; end = false
        rows.clear(); posters.clear(); askedPoster.clear()
        searchEdit.hideKeyboardSafe()
        adapter.notifyDataSetChanged()
        status.text = if (query.isEmpty()) "Новые раздачи · ${sort.title.lowercase()}" else "Ищу «$query»…"
        loading = false
        more()
    }

    private fun more() {
        if (loading || end) return
        loading = true
        val q = query; val p = page; val so = sort
        io.execute {
            val r = runCatching { com.dave.framegrabber.site.Lab.search(act.applicationContext, q, so, true, p) }
            s.ui.post {
                loading = false
                if (q != query || so != sort) return@post
                r.onSuccess { res ->
                    loginBtn.visibility = View.GONE
                    rows += res.rows; page++; total = res.total
                    if (res.rows.size < 50) end = true
                    status.text = when {
                        rows.isEmpty() && p == 0 -> if (q.isEmpty()) "Раздач не видно — сайт мог поменять страницу (⋮ на сайте)" else "Ничего не найдено"
                        q.isEmpty() -> "Новые раздачи · ${so.title.lowercase()}"
                        else -> "«$q» · найдено ${if (res.total >= 0) res.total else rows.size} · ${so.title.lowercase()}"
                    }
                    adapter.notifyDataSetChanged()
                }.onFailure { e ->
                    end = true
                    if (e is com.dave.framegrabber.site.Lab.NeedLogin) {
                        status.text = "Поиск и раздачи доступны после входа на сайт. Войди один раз — приложение запомнит."
                        loginBtn.visibility = View.VISIBLE
                    } else status.text = "Не удалось: ${e.message}"
                }
            }
        }
    }

    private fun chooseSort() {
        val all = com.dave.framegrabber.site.Lab.Sort.entries
        AlertDialog.Builder(act).setTitle("Сортировка")
            .setSingleChoiceItems(all.map { it.title }.toTypedArray(), all.indexOf(sort)) { dlg, i ->
                sort = all[i]; dlg.dismiss(); search(query)
            }.show()
    }

    private fun android.widget.EditText.hideKeyboardSafe() = runCatching {
        (act.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(windowToken, 0)
    }

    private inner class Adapter : android.widget.BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(p: Int) = rows[p]
        override fun getItemId(p: Int) = rows[p].id
        override fun getView(p: Int, cv: View?, parent: ViewGroup): View = rowView(rows[p], cv)
    }

    /** A result: poster on the left; title, section; size · ↑seeds · ↓leechers · date. */
    private fun rowView(r: com.dave.framegrabber.site.Lab.Row, cv: View?): View {
        val holder = (cv?.tag as? RowHolder) ?: RowHolder()
        holder.bind(r)
        return holder.root
    }

    private inner class RowHolder {
        val root = android.widget.LinearLayout(act).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding((8 * d).toInt(), (8 * d).toInt(), (10 * d).toInt(), (8 * d).toInt())
            layoutParams = android.widget.AbsListView.LayoutParams(-1, -2)
        }
        val wrap = FrameLayout(act).apply { setPadding(0, (8 * d).toInt(), 0, 0) }
        val poster = android.widget.ImageView(act).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            setBackgroundResource(R.drawable.bg_thumb); clipToOutline = true
        }
        val title = TextView(act).apply { textSize = 14f; setTextColor(act.tc(R.color.text)); maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END }
        val forum = TextView(act).apply { textSize = 11f; setTextColor(act.tc(R.color.textDim)); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        val info = TextView(act).apply { textSize = 12f; setTextColor(act.tc(R.color.textDim)); maxLines = 1 }
        var id = 0L
        init {
            root.addView(poster, android.widget.LinearLayout.LayoutParams((66 * d).toInt(), (88 * d).toInt()))
            val col = android.widget.LinearLayout(act).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding((10 * d).toInt(), 0, 0, 0) }
            col.addView(title); col.addView(forum.apply { setPadding(0, (3 * d).toInt(), 0, 0) }); col.addView(info.apply { setPadding(0, (5 * d).toInt(), 0, 0) })
            root.addView(col, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
            root.tag = this
        }
        fun bind(r: com.dave.framegrabber.site.Lab.Row) {
            id = r.id
            title.text = r.title
            forum.text = r.forum
            forum.visibility = if (r.forum.isBlank()) View.GONE else View.VISIBLE
            val sp = android.text.SpannableStringBuilder()
            val size = r.sizeText.ifBlank { com.dave.framegrabber.site.Lab.size(r.size) }
            if (size.isNotBlank()) sp.append("💾 ").append(size).append("   ")
            sp.append("↑ ${r.seeds}").also { val e = sp.length; sp.setSpan(android.text.style.ForegroundColorSpan(0xFF5CD98A.toInt()), e - "↑ ${r.seeds}".length, e, 0) }
            sp.append("  ")
            sp.append("↓ ${r.leech}").also { val e = sp.length; sp.setSpan(android.text.style.ForegroundColorSpan(0xFFFF7A7A.toInt()), e - "↓ ${r.leech}".length, e, 0) }
            val date = if (r.date > 0) java.text.SimpleDateFormat("d MMM yy", java.util.Locale("ru")).format(java.util.Date(r.date * 1000)) else r.dateText
            if (date.isNotBlank()) sp.append("   ").append(date)
            info.text = sp
            // poster: from the torrent's page (once per result)
            poster.setImageBitmap(null)
            val url = posters[r.id]
            if (url != null) loadPoster(url, r.id) else if (askedPoster.add(r.id)) io.execute {
                val u = runCatching { com.dave.framegrabber.site.Lab.topic(act.applicationContext, r.id).poster?.thumb }.getOrNull()
                s.ui.post { posters[r.id] = u; if (u != null && id == r.id) loadPoster(u, r.id) }
            }
        }
        fun loadPoster(url: String, forId: Long) {
            com.dave.framegrabber.site.Lab.cached(url, 300)?.let { poster.setImageBitmap(it); return }
            io.execute {
                val b = com.dave.framegrabber.site.Lab.image(act.applicationContext, url, 300)
                s.ui.post { if (id == forId && b != null) poster.setImageBitmap(b) }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(first: String?) {
        val w = WebView(act)
        web = w
        CookieManager.getInstance().apply { setAcceptCookie(true); setAcceptThirdPartyCookies(w, true) }
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // the forum is made for computers: open it fitted to the screen, pinch to zoom in
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            textZoom = 110
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            if (Build.VERSION.SDK_INT >= 33) isAlgorithmicDarkeningAllowed = true   // dark pages, like the app
        }
        w.setBackgroundColor(0xFF000000.toInt())
        w.webViewClient = object : WebViewClient() {
            // ads: their requests get an empty answer (nothing loads, nothing is shown)
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): android.webkit.WebResourceResponse? =
                if (com.dave.framegrabber.site.Ads.isAd(req.url.toString()))
                    android.webkit.WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                else null
            override fun onPageCommitVisible(view: WebView, url: String) { hideAds(view) }
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val u = req.url
                if (com.dave.framegrabber.site.Ads.isAd(u.toString())) return true   // an ad link: stay here
                return when (u.scheme) {
                    "http", "https" -> false
                    else -> { openOutside(u); true }   // magnet:, tg:, …
                }
            }
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                urlText.text = url.removePrefix("https://")
                progress.visibility = View.VISIBLE
            }
            override fun onPageFinished(view: WebView, url: String) {
                hideAds(view)
                progress.visibility = View.GONE
                urlText.text = url.removePrefix("https://")
                prefs.edit().putString(KEY_LAST, url).apply()
                CookieManager.getInstance().flush()   // stay logged in
                // logged in? (a «выход» / logout link on the page, or the login cookie)
                if (backAfterLogin && "login.php" !in url) view.evaluateJavascript(
                    "(function(){return !!document.querySelector('a[href*=\"logout\"]') || /Выход/.test(document.body ? document.body.innerText : '');})()"
                ) { r ->
                    if (backAfterLogin && (r == "true" || com.dave.framegrabber.site.Lab.loggedIn())) {
                        backAfterLogin = false
                        act.toast("Вход выполнен ✓")
                        rows.clear(); end = false; showNative(); search(query)
                    }
                }
            }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, p: Int) { progress.progress = p }
            override fun onReceivedTitle(view: WebView, t: String?) {
                title.text = t?.substringBefore(" :: ")?.ifBlank { null } ?: "pornolab.net"
            }
        }
        // .torrent and other files: Download/FrameGrabber, with the site's login
        w.setDownloadListener { url, ua, cd, mime, _ ->
            runCatching {
                val name = URLUtil.guessFileName(url, cd, mime)
                val req = DownloadManager.Request(Uri.parse(url))
                    .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                    .addRequestHeader("User-Agent", ua)
                    .setMimeType(mime)
                    .setTitle(name)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "${Prefs.ALBUM}/$name")
                act.getSystemService(DownloadManager::class.java).enqueue(req)
                act.toast("Скачиваю $name → Download/${Prefs.ALBUM}")
            }.onFailure { act.toast("Не удалось скачать: ${it.message}") }
        }
        host.addView(w, ViewGroup.LayoutParams(-1, -1))
        w.loadUrl(first ?: prefs.getString(KEY_LAST, null) ?: HOME)
    }

    /**
     * The page the app last read (or the site page open now) → Download/FrameGrabber, so it can be
     * sent to fix the app screens when the site changes.
     */
    private fun savePage() {
        val w = web
        val save = { html: String ->
            io.execute {
                val r = runCatching {
                    com.dave.framegrabber.data.Output.write(act, "pornolab-page-${System.currentTimeMillis() / 1000}.html", "text/html",
                        com.dave.framegrabber.data.Output.Kind.DOWNLOAD) { t -> t.stream().use { it.write(html.toByteArray()) } }
                }
                s.ui.post { act.toast(if (r.isSuccess) "Сохранено в Download/${Prefs.ALBUM}" else "Не удалось: ${r.exceptionOrNull()?.message}") }
            }
        }
        if (w != null && webBox.visibility == View.VISIBLE)
            w.evaluateJavascript("document.documentElement.outerHTML") { v ->
                save(runCatching { org.json.JSONArray("[$v]").getString(0) }.getOrDefault(v))
            }
        else save(com.dave.framegrabber.site.Lab.lastHtml)
    }

    /** The site's banner blocks hidden (and kept hidden when the page adds them later). */
    private fun hideAds(v: WebView) {
        val css = com.dave.framegrabber.site.Ads.CSS.replace("'", "\\'")
        v.evaluateJavascript("""(function(){
            if (!document.getElementById('fg-noads')) { var s=document.createElement('style'); s.id='fg-noads'; s.textContent='$css'; (document.head||document.documentElement).appendChild(s); }
            document.querySelectorAll('iframe').forEach(function(f){ if (!/pornolab\.net/.test(f.src||'')) f.remove(); });
            window.open = function(){ return null; };
        })()""", null)
    }

    private fun openOutside(u: Uri) {
        runCatching { act.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure {
                act.toast(if (u.scheme == "magnet") "Нет торрент-приложения для magnet-ссылки" else "Нечем открыть эту ссылку")
            }
    }

    private fun menu() {
        val w = web ?: return
        val items = arrayOf("Открыть в браузере", "Скопировать ссылку", "Крупнее текст", "Мельче текст", "Главная страница", "Сохранить страницу (для исправлений)")
        AlertDialog.Builder(act).setItems(items) { _, i ->
            when (i) {
                0 -> w.url?.let { openOutside(Uri.parse(it)) }
                1 -> w.url?.let {
                    act.getSystemService(android.content.ClipboardManager::class.java)
                        .setPrimaryClip(android.content.ClipData.newPlainText("link", it))
                    act.toast("Ссылка скопирована")
                }
                2 -> w.settings.textZoom = (w.settings.textZoom + 15).coerceAtMost(200)
                3 -> w.settings.textZoom = (w.settings.textZoom - 15).coerceAtLeast(70)
                4 -> w.loadUrl(HOME)
                5 -> savePage()
            }
        }.show()
    }
}
