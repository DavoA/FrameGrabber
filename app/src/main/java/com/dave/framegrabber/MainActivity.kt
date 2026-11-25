package com.dave.framegrabber

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.animation.OvershootInterpolator
import android.widget.ListView
import com.dave.framegrabber.data.AppTheme
import com.dave.framegrabber.ui.anim
import com.dave.framegrabber.ui.dp
import com.dave.framegrabber.ui.browser.FileBrowserActivity
import com.dave.framegrabber.ui.main.MainScreen
import com.dave.framegrabber.ui.widget.SynthwaveView
import java.io.File
import kotlin.math.max

/**
 * Entry point. Only wiring lives here: the window, opening videos (file browser / other apps /
 * "Share to"), the folder picker result and lifecycle. Everything on the screen is in [MainScreen].
 */
class MainActivity : Activity() {

    companion object {
        private const val REQ_PICK = 1
        private const val REQ_TREE = 2
    }

    lateinit var bgView: SynthwaveView
        private set
    private lateinit var screen: MainScreen

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false) // background under the bars
        setContentView(R.layout.activity_main)
        bgView = findViewById(R.id.bgView)
        AppTheme.apply(this, bgView, AppTheme.get(this), animate = false)

        val list = findViewById<ListView>(R.id.list)
        screen = MainScreen(this, layoutInflater.inflate(R.layout.header, list, false))
        applyInsets(list)

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

    /** Android 15 draws edge-to-edge: keep content out from under status/nav bars and the keyboard. */
    private fun applyInsets(list: ListView) {
        if (Build.VERSION.SDK_INT < 30) return
        findViewById<View>(R.id.content).setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, 0)
            list.setPadding(0, 0, 0, max(bars.bottom, ime.bottom) + (12 * v.dp).toInt())
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
            type = "video/*"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(Intent.createChooser(get, "Выбрать видео через…"), REQ_PICK)
    }

    /** System folder picker for "save folder" in general settings. */
    fun pickFolder() {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_TREE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQ_TREE -> data.data?.let { screen.settings.onFolderPicked(it) }
            REQ_PICK -> {
                // the browser returns a file path (a file:// Uri can't pass through setResult)
                val path = data.getStringExtra(FileBrowserActivity.EXTRA_PATH)
                val uri = if (path != null) Uri.fromFile(File(path)) else data.data
                uri?.let { screen.video.load(it) }
            }
        }
    }
}
