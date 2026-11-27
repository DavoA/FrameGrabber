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

/**
 * Page «Сайт»: pornolab.net inside the app. The login stays (cookies are kept), the last page is
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

    init {
        s.h<View>(R.id.btnSiteBack).setOnClickListener { if (!back()) act.toast("Это первая страница") }
        s.h<View>(R.id.btnSiteHome).setOnClickListener { web?.loadUrl(HOME) }
        s.h<View>(R.id.btnSiteRefresh).setOnClickListener { web?.reload() }
        s.h<View>(R.id.btnSiteMenu).setOnClickListener { menu() }
    }

    fun onShown() {
        if (web == null) create()
    }

    /** One step back on the site; false if there's nowhere to go. */
    fun back(): Boolean {
        val w = web ?: return false
        if (!w.canGoBack()) return false
        w.goBack()
        return true
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create() {
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
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val u = req.url
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
                progress.visibility = View.GONE
                urlText.text = url.removePrefix("https://")
                prefs.edit().putString(KEY_LAST, url).apply()
                CookieManager.getInstance().flush()   // stay logged in
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
        w.loadUrl(prefs.getString(KEY_LAST, null) ?: HOME)
    }

    private fun openOutside(u: Uri) {
        runCatching { act.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure {
                act.toast(if (u.scheme == "magnet") "Нет торрент-приложения для magnet-ссылки" else "Нечем открыть эту ссылку")
            }
    }

    private fun menu() {
        val w = web ?: return
        val items = arrayOf("Открыть в браузере", "Скопировать ссылку", "Крупнее текст", "Мельче текст", "Главная страница")
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
            }
        }.show()
    }
}
