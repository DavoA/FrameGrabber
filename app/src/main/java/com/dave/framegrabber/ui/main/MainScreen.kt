package com.dave.framegrabber.ui.main

import android.os.Handler
import android.os.Looper
import android.view.View
import com.dave.framegrabber.MainActivity
import com.dave.framegrabber.R
import com.dave.framegrabber.batch.BatchState
import com.dave.framegrabber.data.CaptureStore
import com.dave.framegrabber.data.Prefs
import com.dave.framegrabber.ui.switchPanel
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The main screen, split into parts. Each part owns its own views and logic:
 *
 *  - [video]   — the opened video: loading, preview, timeline, frame stepping, playback with sound
 *  - [viewer]  — a captured frame opened in the big preview (zoom, flip, share/save/delete/text)
 *  - [grid]    — the captured frames grid + "Галерея / ZIP / Очистить"
 *  - [range]   — the «Отрезок» card used by batch and export
 *  - [batch]   — tab «Пачка»: many frames at once (runs in the background service)
 *  - [export]  — tab «Экспорт»: GIF, MP4 clip, contact sheet
 *  - [format]  — tab «Формат»: size, format, quality, "sharpest frame"
 *  - [recent]  — recently opened videos on the start screen
 *  - [shutter] — the 📷 button (take the current frame)
 *  - [ocr]     — "copy text from a frame"
 *  - [settings] — ⚙ general settings dialog
 *  - [effects] — screen-wide animations (entrance, shutter flash, flying thumbnail)
 *
 * Parts talk to each other only through this object (e.g. `s.viewer.close()`).
 */
class MainScreen(val act: MainActivity, val header: View) {
    val prefs = Prefs(act)
    val ui = Handler(Looper.getMainLooper())
    /** All calls to the video engine go through this one thread (MediaMetadataRetriever is not thread-safe). */
    val exec: ExecutorService = Executors.newSingleThreadExecutor()
    /** Decoding saved frames for the viewer. */
    val io: ExecutorService = Executors.newSingleThreadExecutor()

    /**
     * Where captured frames are kept until saved. filesDir, not cacheDir: some phones wipe the
     * cache while the app is open. Cleared on start unless frames exist / a batch is running.
     */
    val capDir: File = File(act.filesDir, "captures").apply {
        if (CaptureStore.captures.isEmpty() && !BatchState.running) deleteRecursively()
        mkdirs()
    }

    /** Find a view in the pinned part of the screen. */
    fun <T : View> pinned(id: Int): T = act.findViewById(id)
    /** Find a view in the scrolling part (the list header). */
    fun <T : View> h(id: Int): T = header.findViewById(id)

    // Order matters only for init blocks (they bind views and listeners, they don't call each other).
    val effects = Effects(this)
    val video = VideoController(this)
    val viewer = ViewerController(this)
    val grid = CapturesController(this)
    val range = RangeCard(this)
    val format = FormatPanel(this)
    val batch = BatchPanel(this)
    val export = ExportPanel(this)
    val recent = RecentStrip(this)
    val shutter = Shutter(this)
    val ocr = TextRecognition(this)
    val settings = SettingsDialog(this)

    init {
        setupTabs()
        batch.updateHint()
        recent.build()
        effects.playEntrance()
        effects.startIdle()
    }

    /** Tabs «Пачка / Экспорт / Формат» (the last one is remembered). */
    private fun setupTabs() {
        val panels = listOf(h<View>(R.id.cardBatch), h<View>(R.id.cardExport), h<View>(R.id.cardSettings))
        val tabIds = listOf(R.id.tabBatch, R.id.tabExport, R.id.tabSettings)
        val tab = prefs.tab.coerceIn(0, 2)
        panels.forEachIndexed { i, p -> p.visibility = if (i == tab) View.VISIBLE else View.GONE }
        h<android.widget.RadioGroup>(R.id.radioTabs).apply {
            check(tabIds[tab])
            setOnCheckedChangeListener { _, id ->
                val to = tabIds.indexOf(id).coerceAtLeast(0)
                val from = panels.indexOfFirst { it.visibility == View.VISIBLE }.coerceAtLeast(0)
                if (from != to) switchPanel(panels[from], panels[to], if (to > from) 1 else -1)
                prefs.tab = to
            }
        }
    }

    fun onResume() {
        grid.onResume()
        batch.onResume()
    }

    fun onPause() {
        video.pausePlayback()
        grid.onPause()
        batch.onPause()
    }

    fun onDestroy() {
        video.release()
        exec.shutdown()
        io.shutdown()
    }
}
