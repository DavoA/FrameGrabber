package com.dave.framegrabber

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.animation.OvershootInterpolator
import com.dave.framegrabber.data.AppTheme
import com.dave.framegrabber.ui.anim
import com.dave.framegrabber.ui.dp
import com.dave.framegrabber.ui.browser.FileBrowserActivity
import com.dave.framegrabber.ui.main.MainScreen
import com.dave.framegrabber.ui.main.NavController
import com.dave.framegrabber.ui.widget.SynthwaveView
import java.io.File

/**
 * Entry point. Only wiring lives here: the window, opening videos (file browser / other apps /
 * "Share to"), the folder picker result and lifecycle. Everything on the screen is in [MainScreen].
 */
class MainActivity : Activity() {

    companion object {
        private const val REQ_PICK = 1
        private const val REQ_TREE = 2
        private const val REQ_MULTI = 3
        private const val REQ_POST = 4
        private const val REQ_PLAY = 5
        private const val REQ_PLAYER_DIR = 6
        private const val REQ_DELETE = 7
        /** What the pickers ask other apps for. */
        val VIDEO_MIME_TYPES = arrayOf(
            "video/*", "application/x-matroska", "application/mp4", "application/x-mpegts",
            "application/vnd.rn-realmedia", "application/x-flash-video", "application/ogg"
        )
    }

    lateinit var bgView: SynthwaveView
        private set
    /** The screen's parts (public for the UI tests). */
    lateinit var screen: MainScreen
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false) // background under the bars
        setContentView(R.layout.activity_main)
        bgView = findViewById(R.id.bgView)
        AppTheme.apply(this, bgView, AppTheme.get(this), animate = false)

        screen = MainScreen(this)
        applyInsets()

        findViewById<View>(R.id.btnPick).apply {
            setOnClickListener { openBrowser() }
            setOnLongClickListener { pickWithOtherApp(); true } // long press: pick with another app
        }
        findViewById<View>(R.id.btnPickBig).setOnClickListener { openBrowser() }
        findViewById<View>(R.id.btnSettings).setOnClickListener { v ->
            v.anim().rotationBy(120f).setDuration(450).setInterpolator(OvershootInterpolator()).start()
            screen.settings.show()
        }

        handleIncoming(intent)
    }

    /**
     * Android 15 draws edge-to-edge: the content starts below the status bar, the bottom bar grows
     * under the navigation bar, and the full-screen viewer keeps its buttons clear of both.
     */
    private fun applyInsets() {
        if (Build.VERSION.SDK_INT < 30) return
        val nav = findViewById<View>(R.id.bottomNav)
        val navHeight = nav.layoutParams.height
        val viewerTop = findViewById<View>(R.id.viewerTop)
        val viewerBottom = findViewById<View>(R.id.viewerBottom)
        val pad = (12 * nav.dp).toInt()
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            findViewById<View>(R.id.content).setPadding(bars.left, bars.top, bars.right, 0)
            nav.setPadding(0, 0, 0, bars.bottom)
            nav.layoutParams = nav.layoutParams.apply { height = navHeight + bars.bottom }
            viewerTop.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad)
            viewerBottom.setPadding(pad + bars.left, pad, pad + bars.right, pad + bars.bottom)
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        bgView.running = AppTheme.get(this) == AppTheme.WAVE
        screen.onResume()
    }

    override fun onPause() {
        bgView.running = false
        screen.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        screen.onDestroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (screen.viewer.selected != null) {
            screen.viewer.close()
        } else if (screen.nav.page != NavController.Page.CAPTURE) {
            screen.nav.show(NavController.Page.CAPTURE)
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    // ================================================================ opening videos

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    /** "Share to" / "Open with" from other apps. */
    private fun handleIncoming(intent: Intent?) {
        if (intent == null) return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_SEND -> streamExtra(intent)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        uri?.let { screen.video.load(it) }
    }

    @Suppress("DEPRECATION")
    private fun streamExtra(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_STREAM)

    /** Our own ZArchiver-style file browser. */
    private fun openBrowser() {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(this, FileBrowserActivity::class.java), REQ_PICK)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    /**
     * ACTION_GET_CONTENT + chooser (not ACTION_OPEN_DOCUMENT): the system "storage" picker only lists
     * built-in document providers, while GET_CONTENT lets ANY app hand out a file (ZArchiver, Gallery…).
     */
    private fun pickWithOtherApp() {
        val get = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            // some apps label MKV/AVI/TS files as "application/…", so ask for those too
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, VIDEO_MIME_TYPES)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(Intent.createChooser(get, "Выбрать видео через…"), REQ_PICK)
    }

    /** In-app browser; the picked video opens in the player. */
    fun openBrowserForPlayer() {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(this, FileBrowserActivity::class.java), REQ_PLAY)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    /** A system question (e.g. "delete this video?"); the answer goes back to the player tab. */
    fun askSystem(sender: android.content.IntentSender) {
        @Suppress("DEPRECATION")
        startIntentSenderForResult(sender, REQ_DELETE, null, 0, 0, 0)
    }

    /** In-app browser in folder mode: a folder for the player's library. */
    fun pickPlayerFolder() {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(this, FileBrowserActivity::class.java).putExtra(FileBrowserActivity.EXTRA_PICK_DIR, true), REQ_PLAYER_DIR)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    /** The Telegram post editor for the picked frames. */
    fun openPostEditor(i: Intent) {
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_POST)
    }

    /** In-app browser in multi-select mode (batch over several videos). */
    fun openBrowserMulti() {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(this, FileBrowserActivity::class.java).putExtra(FileBrowserActivity.EXTRA_MULTI, true), REQ_MULTI)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    /** System folder picker for "save folder" in general settings. */
    fun pickFolder() {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_TREE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_DELETE) { screen.playerTab.onDeleteResult(resultCode == RESULT_OK); return }
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQ_TREE -> data.data?.let { screen.settings.onFolderPicked(it) }
            REQ_POST -> screen.tgPick.onPosted()
            REQ_PLAYER_DIR -> data.getStringExtra(FileBrowserActivity.EXTRA_PATH)?.let { screen.playerTab.onFolderPicked(it) }
            REQ_PLAY -> {
                val path = data.getStringExtra(FileBrowserActivity.EXTRA_PATH)
                val uri = if (path != null) Uri.fromFile(File(path)) else data.data
                uri?.let { screen.playerTab.play(it) }
            }
            REQ_MULTI -> data.getStringArrayListExtra(FileBrowserActivity.EXTRA_REFS)?.let { refs ->
                val uris = refs.map { if (it.startsWith("/")) Uri.fromFile(File(it)) else Uri.parse(it) }
                screen.batch.onVideosPicked(uris)
            }
            REQ_PICK -> {
                // the browser returns a file path (a file:// Uri can't pass through setResult)
                val path = data.getStringExtra(FileBrowserActivity.EXTRA_PATH)
                val uri = if (path != null) Uri.fromFile(File(path)) else data.data
                uri?.let { screen.video.load(it); screen.nav.show(NavController.Page.CAPTURE) }
            }
        }
    }
}
