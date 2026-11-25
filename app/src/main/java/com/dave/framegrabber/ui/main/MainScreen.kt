package com.dave.framegrabber.ui.main

import android.os.Handler
import android.os.Looper
import android.view.View
import com.dave.framegrabber.MainActivity
import com.dave.framegrabber.R
import com.dave.framegrabber.batch.BatchState
import com.dave.framegrabber.data.CaptureStore
import com.dave.framegrabber.data.Prefs
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The main screen, split into parts. Each part owns its own views and logic.
 * Pages (bottom bar): «Съёмка» (page_capture.xml), «Кадры» (the list), «Инструменты» (page_tools.xml).
 *
 *  - [nav]     — the bottom bar and page switching
 *  - [video]   — the opened video: loading, preview, timeline, frame stepping, playback with sound
 *  - [viewer]  — a captured frame opened full screen (zoom, flip, share/save/delete/text)
 *  - [grid]    — page «Кадры»: the frames grid + "Галерея / ZIP / Очистить"
 *  - [clips]   — page «Клипы»: GIF and MP4 clip from a range + the ones made so far
 *  - [batch]   — «Пачка кадров» (page «Инструменты»): many frames at once (background service)
 *  - [sheet]   — «Лист кадров» (page «Инструменты»): all frames in one image
 *  - [rangeClips] / [rangeBatch] — the «Отрезок» card of each page
 *  - [format]  — size, format, quality, "sharpest frame" (edited in ⚙ settings)
 *  - [recent]  — recently opened videos on the start screen
 *  - [shutter] — the 📷 button (take the current frame)
 *  - [ocr]     — "copy text from a frame"
 *  - [settings] — ⚙ general settings dialog
 *  - [effects] — screen-wide animations (entrance, shutter flash, flying thumbnail)
 *
 * Parts talk to each other only through this object (e.g. `s.viewer.close()`).
 */
class MainScreen(val act: MainActivity) {
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

    /** Find a view on the screen (any page). */
    fun <T : View> pinned(id: Int): T = act.findViewById(id)
    /** Same as [pinned] (kept short for the page parts). */
    fun <T : View> h(id: Int): T = act.findViewById(id)

    // Order matters only for init blocks (they bind views and listeners, they don't call each other).
    val effects = Effects(this)
    val nav = NavController(this)
    val video = VideoController(this)
    val viewer = ViewerController(this)
    val grid = CapturesController(this)
    val rangeClips = RangeCard(this, h(R.id.rangeClips))
    val rangeBatch = RangeCard(this, h(R.id.rangeBatch)) { batch.updateHint() }
    val format = CaptureFormat(this)
    val batch = BatchPanel(this)
    val clips = ClipsPanel(this)
    val sheet = SheetPanel(this)
    val recent = RecentStrip(this)
    val shutter = Shutter(this)
    val ocr = TextRecognition(this)
    val settings = SettingsDialog(this)

    init {
        batch.updateHint()
        recent.build()
        effects.playEntrance()
        effects.startIdle()
        freezeBackgroundWhileScrolling(h(R.id.pageTools))
        freezeBackgroundWhileScrolling(h(R.id.pageClips))
    }

    /** The animated background pauses while a page scrolls (smoother scrolling). */
    private fun freezeBackgroundWhileScrolling(v: View) {
        val resume = Runnable { act.bgView.scrolling = false }
        v.setOnScrollChangeListener { _, _, _, _, _ ->
            act.bgView.scrolling = true
            ui.removeCallbacks(resume)
            ui.postDelayed(resume, 180)
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
