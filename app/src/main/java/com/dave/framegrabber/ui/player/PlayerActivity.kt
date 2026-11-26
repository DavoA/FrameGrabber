package com.dave.framegrabber.ui.player

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.dave.framegrabber.R
import com.dave.framegrabber.core.FrameEngine
import com.dave.framegrabber.core.TimeUtil
import com.dave.framegrabber.player.ActivityScan
import com.dave.framegrabber.player.PlayerStore
import com.dave.framegrabber.player.RandomMoment
import com.dave.framegrabber.ui.toast
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The video player.
 *  - Gestures: swipe up/down on the left = brightness, on the right = volume, sideways = seek;
 *    double tap left / right = −10 / +10 s, in the middle = pause; two fingers = zoom and move.
 *  - Speed 0.25×…2×, frame by frame, A–B loop, «лучшие моменты» (★), 🎲 random hot moment.
 *  - Remembers where you stopped, what you watched most (heat map on the bar), and how long the
 *    intros are in each folder (skips them next time).
 *  - Picture in picture; VR (180° / 360°, side-by-side / top-bottom, gyroscope, cardboard).
 */
class PlayerActivity : Activity() {

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_START = "startUs"
        const val EXTRA_NAME = "name"

        fun intent(ctx: Context, uri: Uri, startUs: Long = -1, name: String? = null) =
            Intent(ctx, PlayerActivity::class.java)
                .putExtra(EXTRA_URI, uri.toString())
                .putExtra(EXTRA_START, startUs)
                .putExtra(EXTRA_NAME, name)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        private val SPEEDS = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
    }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var store: PlayerStore
    private lateinit var data: PlayerStore.Video
    private lateinit var uri: Uri
    private var folder: String? = null
    private var mp: MediaPlayer? = null
    private var prepared = false
    private var durationUs = 0L
    private var videoW = 0; private var videoH = 0
    private var frameUs = 33_333L
    private var speed = 1f

    // A–B loop
    private var loopA = -1L
    private var loopB = -1L

    // views
    private lateinit var textureView: TextureView
    private lateinit var vrHost: FrameLayout
    private lateinit var gestureLayer: View
    private lateinit var controls: View
    private lateinit var timeline: TimelineBar
    private lateinit var txtTime: TextView
    private lateinit var txtTitle: TextView
    private lateinit var txtSub: TextView
    private lateinit var btnPlay: ImageButton
    private lateinit var btnAB: TextView
    private lateinit var btnSpeed: TextView
    private lateinit var btnVr: TextView
    private lateinit var snack: TextView
    private lateinit var gestureInfo: View
    private lateinit var gestureIcon: ImageView
    private lateinit var gestureText: TextView
    private lateinit var gestureLevel: ProgressBar
    private lateinit var vrBar: View
    private lateinit var vrChips: LinearLayout

    private var flatSurface: Surface? = null
    private var vrView: VrView? = null
    private val d get() = resources.displayMetrics.density

    // ================================================================ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        volumeControlStream = AudioManager.STREAM_MUSIC
        store = PlayerStore(this)

        textureView = findViewById(R.id.videoView)
        vrHost = findViewById(R.id.vrHost)
        gestureLayer = findViewById(R.id.gestureLayer)
        controls = findViewById(R.id.controls)
        timeline = findViewById(R.id.timeline)
        txtTime = findViewById(R.id.txtTime)
        txtTitle = findViewById(R.id.txtTitle)
        txtSub = findViewById(R.id.txtSub)
        btnPlay = findViewById(R.id.btnPlay)
        btnAB = findViewById(R.id.btnAB)
        btnSpeed = findViewById(R.id.btnSpeed)
        btnVr = findViewById(R.id.btnVr)
        snack = findViewById(R.id.snack)
        gestureInfo = findViewById(R.id.gestureInfo)
        gestureIcon = findViewById(R.id.gestureIcon)
        gestureText = findViewById(R.id.gestureText)
        gestureLevel = findViewById(R.id.gestureLevel)
        vrBar = findViewById(R.id.vrBar)
        vrChips = findViewById(R.id.vrChips)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        btnPlay.setOnClickListener { togglePlay() }
        findViewById<View>(R.id.btnBack10).setOnClickListener { seekBy(-10_000_000, show = true) }
        findViewById<View>(R.id.btnFwd10).setOnClickListener { seekBy(10_000_000, show = true) }
        findViewById<View>(R.id.btnFramePrev).setOnClickListener { stepFrame(-1) }
        findViewById<View>(R.id.btnFrameNext).setOnClickListener { stepFrame(+1) }
        findViewById<View>(R.id.btnMoment).setOnClickListener { toggleMoment() }
        findViewById<View>(R.id.btnMoment).setOnLongClickListener { momentsList(); true }
        findViewById<View>(R.id.btnMomentPrev).setOnClickListener { jumpMoment(-1) }
        findViewById<View>(R.id.btnMomentNext).setOnClickListener { jumpMoment(+1) }
        btnAB.setOnClickListener { cycleAB() }
        btnSpeed.setOnClickListener { chooseSpeed() }
        findViewById<View>(R.id.btnRandom).setOnClickListener { randomMoment() }
        findViewById<View>(R.id.btnRandomVideo).setOnClickListener { randomVideo() }
        findViewById<View>(R.id.btnPip).setOnClickListener { enterPip() }
        btnVr.setOnClickListener { if (vrView == null) chooseVr() else exitVr() }
        snack.setOnClickListener { snackAction?.invoke(); hideSnack() }

        timeline.listener = object : TimelineBar.Listener {
            override fun onScrubStart() { keepControls() }
            override fun onScrub(us: Long) { txtTime.text = timeText(us) }
            override fun onScrubEnd(us: Long) { seekTo(us, userSeek = true) }
        }
        setupGestures()

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                flatSurface = Surface(st)
                if (vrView == null) mp?.setSurface(flatSurface)
                fitVideo()
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = fitVideo()
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { flatSurface = null; return true }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }

        open(Uri.parse(intent.getStringExtra(EXTRA_URI)), intent.getLongExtra(EXTRA_START, -1), intent.getStringExtra(EXTRA_NAME))
        ui.post(tick)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        open(Uri.parse(intent.getStringExtra(EXTRA_URI)), intent.getLongExtra(EXTRA_START, -1), intent.getStringExtra(EXTRA_NAME))
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        vrView?.start()
    }

    override fun onPause() {
        super.onPause()
        if (!isInPictureInPictureMode) {
            mp?.takeIf { prepared && it.isPlaying }?.pause()
            updatePlayIcon()
            vrView?.stop()
        }
        saveState()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        saveState()
        mp?.release(); mp = null
        super.onDestroy()
    }

    /** Home while playing: keep watching in a small window. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (mp?.isPlaying == true && vrView == null) enterPip()
    }

    override fun onPictureInPictureModeChanged(pip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(pip, newConfig)
        controls.visibility = if (pip) View.GONE else View.VISIBLE
        gestureLayer.visibility = if (pip) View.GONE else View.VISIBLE
        if (!pip) showControls()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        textureView.post { fitVideo() }
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        if (Build.VERSION.SDK_INT >= 28) window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    // ================================================================ opening a video

    private var pendingStart = -1L

    private fun open(u: Uri, startUs: Long, name: String?) {
        if (::data.isInitialized) saveState()
        mp?.release(); mp = null
        prepared = false
        loopA = -1; loopB = -1
        speed = 1f
        uri = u
        data = store.load(u)
        data.uri = u.toString()
        data.name = name ?: data.name.ifEmpty { displayName(u) }
        folder = folderOf(u)
        txtTitle.text = data.name
        timeline.moments = data.moments.toList()
        timeline.heat = data.heat
        timeline.activity = data.activity
        timeline.loopA = -1; timeline.loopB = -1
        pendingStart = startUs
        updateSub()

        val p = MediaPlayer()
        mp = p
        p.setOnPreparedListener { onPrepared(it) }
        p.setOnVideoSizeChangedListener { _, w, h -> videoW = w; videoH = h; fitVideo(); updatePip() }
        p.setOnCompletionListener { updatePlayIcon(); showControls() }
        p.setOnErrorListener { _, what, extra ->
            AlertDialog.Builder(this)
                .setTitle("Не получается воспроизвести")
                .setMessage("Плеер Android не умеет показывать это видео (формат или кодек). Кадры из него в приложении снимать можно.\n\nКод: $what/$extra")
                .setPositiveButton("OK") { _, _ -> finish() }
                .setCancelable(false).show()
            true
        }
        try {
            p.setDataSource(this, u)
            (vrView?.let { pendingVrSurface } ?: flatSurface)?.let { p.setSurface(it) }
            p.prepareAsync()
        } catch (e: Exception) {
            toast("Не удалось открыть видео: ${e.message}")
            finish()
            return
        }
        // frame length (for frame-by-frame) and the activity curve, in the background
        Thread {
            runCatching { FrameEngine(this, u).use { e -> frameUs = e.frameUs } }
            if (data.activity == null) runCatching {
                val a = ActivityScan.compute(this, u) { !isDestroyed && uri == u }
                ui.post { if (uri == u) { data.activity = a; timeline.activity = a; saveState() } }
            }
        }.start()
        maybeOfferVr(u)
    }

    private fun onPrepared(p: MediaPlayer) {
        prepared = true
        durationUs = p.duration * 1000L
        data.durationUs = durationUs
        timeline.durationUs = durationUs
        videoW = p.videoWidth; videoH = p.videoHeight
        fitVideo(); updatePip()

        // where to start: asked for > where you stopped > after the intro
        val resume = data.positionUs
        val skip = store.introSkipUs(folder)
        when {
            pendingStart >= 0 -> seekTo(pendingStart)
            resume in 5_000_000 until durationUs - 15_000_000 -> {
                seekTo(resume)
                showSnack("Продолжаем с ${TimeUtil.format(resume)} · С начала") { seekTo(0) }
            }
            skip in 1 until durationUs - 60_000_000 -> {
                seekTo(skip)
                showSnack("Пропущено начало ${TimeUtil.format(skip)} · Вернуть") { seekTo(0) }
            }
        }
        startedAt = System.currentTimeMillis()
        playedFromStart = pendingStart < 0 && resume < 5_000_000 && skip <= 0
        data.vr.takeIf { it.isNotEmpty() && it != "flat" }?.let { enterVr(it) }
        p.start()
        applySpeed()
        updatePlayIcon()
        showControls()
    }

    private fun displayName(u: Uri): String = runCatching {
        if (u.scheme == "file") File(u.path!!).name
        else contentResolver.query(u, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: "Видео"

    /** The folder (for "skip the intro": one studio's videos usually sit together). */
    private fun folderOf(u: Uri): String? = runCatching {
        if (u.scheme == "file") File(u.path!!).parent
        else contentResolver.query(u, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    private fun saveState() {
        if (!::data.isInitialized) return
        val p = mp
        if (p != null && prepared) {
            val pos = p.currentPosition * 1000L
            data.positionUs = if (durationUs - pos < 10_000_000) 0 else pos
        }
        data.lastWatched = System.currentTimeMillis()
        val copy = data
        Thread { runCatching { store.save(copy) } }.start()
    }

    // ================================================================ playback

    private val position: Long get() = mp?.takeIf { prepared }?.currentPosition?.times(1000L) ?: 0L

    private fun togglePlay() {
        val p = mp ?: return
        if (!prepared) return
        if (p.isPlaying) p.pause() else {
            if (durationUs - position < 300_000) seekTo(0)
            p.start(); applySpeed()
        }
        updatePlayIcon()
        showControls()
    }

    private fun updatePlayIcon() {
        btnPlay.setImageResource(if (mp?.isPlaying == true) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun seekTo(us: Long, exact: Boolean = true, userSeek: Boolean = false) {
        val p = mp ?: return
        if (!prepared) return
        val from = position
        val t = us.coerceIn(0, max(0, durationUs - 100_000))
        p.seekTo(t / 1000, if (exact) MediaPlayer.SEEK_CLOSEST else MediaPlayer.SEEK_PREVIOUS_SYNC)
        timeline.positionUs = t
        txtTime.text = timeText(t)
        if (userSeek) learnIntro(from, t)
    }

    private fun seekBy(deltaUs: Long, show: Boolean) {
        seekTo(position + deltaUs, userSeek = true)
        if (show) showControls()
    }

    private fun stepFrame(dir: Int) {
        val p = mp ?: return
        if (p.isPlaying) { p.pause(); updatePlayIcon() }
        seekTo(position + dir * frameUs)
        showControls()
    }

    private fun applySpeed() {
        val p = mp ?: return
        if (!prepared) return
        val playing = p.isPlaying
        runCatching { p.playbackParams = p.playbackParams.setSpeed(speed) }
        if (!playing && p.isPlaying) p.pause()   // setting the speed starts playback on some phones
        updateSub()
    }

    private fun chooseSpeed() {
        val labels = SPEEDS.map { (if (it == 1f) "1× обычная" else "${fmt(it)}×") }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Скорость")
            .setSingleChoiceItems(labels, SPEEDS.indexOfFirst { it == speed }) { dlg, i ->
                speed = SPEEDS[i]; applySpeed(); btnSpeed.text = "${fmt(speed)}×"; dlg.dismiss()
            }.show()
    }

    private fun fmt(f: Float) = if (f == f.toInt().toFloat()) f.toInt().toString() else f.toString().trimEnd('0')

    // ================================================================ the clock: position, A–B, heat map

    private var lastTickPos = -1L
    private var lastSave = 0L

    private val tick = object : Runnable {
        override fun run() {
            val p = mp
            if (p != null && prepared) {
                val pos = position
                if (!scrubbing) { timeline.positionUs = pos; txtTime.text = timeText(pos) }
                if (p.isPlaying) {
                    // A–B: back to A
                    if (loopA >= 0 && loopB > loopA && pos >= loopB) seekTo(loopA)
                    // heat map: time actually watched in each part
                    if (lastTickPos >= 0 && durationUs > 0) {
                        val dt = (pos - lastTickPos) / 1e6f
                        if (dt in 0f..0.6f) {
                            val b = ((pos * PlayerStore.BUCKETS) / durationUs).toInt().coerceIn(0, PlayerStore.BUCKETS - 1)
                            data.heat[b] += dt
                        }
                    }
                    if (System.currentTimeMillis() - lastSave > 15_000) {
                        lastSave = System.currentTimeMillis(); timeline.heat = data.heat; saveState()
                    }
                }
                lastTickPos = pos
            }
            ui.postDelayed(this, 100)
        }
    }

    private fun timeText(us: Long) = "${TimeUtil.format(us)} / ${TimeUtil.format(durationUs)}"

    // ================================================================ A–B, moments

    private fun cycleAB() {
        val pos = position
        when {
            loopA < 0 -> { loopA = pos; toast("A = ${TimeUtil.format(pos)}. Нажми ещё раз в конце отрезка") }
            loopB < 0 -> {
                if (pos <= loopA + 300_000) { toast("B должна быть позже A"); return }
                loopB = pos
                seekTo(loopA)
                toast("Повтор ${TimeUtil.format(loopA)} – ${TimeUtil.format(loopB)}")
            }
            else -> { loopA = -1; loopB = -1; toast("Повтор выключен") }
        }
        timeline.loopA = loopA; timeline.loopB = loopB
        btnAB.text = when { loopA < 0 -> "A–B"; loopB < 0 -> "A–…"; else -> "A–B ⟲" }
        btnAB.setTextColor(getColor(if (loopA >= 0) R.color.warn else R.color.text))
        updateSub()
        showControls()
    }

    private fun toggleMoment() {
        val pos = position
        val near = data.moments.firstOrNull { abs(it - pos) < 2_000_000 }
        if (near != null) { data.moments.remove(near); toast("Момент убран") }
        else { data.moments += pos; data.moments.sort(); toast("★ Момент ${TimeUtil.format(pos)}") }
        timeline.moments = data.moments.toList()
        saveState()
        showControls()
    }

    private fun jumpMoment(dir: Int) {
        val pos = position
        val m = if (dir > 0) data.moments.firstOrNull { it > pos + 500_000 } else data.moments.lastOrNull { it < pos - 1_500_000 }
        if (m == null) { toast(if (data.moments.isEmpty()) "Отмечай моменты кнопкой ★" else "Дальше моментов нет"); return }
        seekTo(m)
        showControls()
    }

    private fun momentsList() {
        if (data.moments.isEmpty()) { toast("Пока нет моментов — нажми ★ во время просмотра"); return }
        val items = data.moments.map { "★  " + TimeUtil.format(it) }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Лучшие моменты")
            .setItems(items) { _, i -> seekTo(data.moments[i]) }
            .setNeutralButton("Удалить все") { _, _ -> data.moments.clear(); timeline.moments = emptyList(); saveState() }
            .setNegativeButton("Закрыть", null).show()
    }

    // ================================================================ skip the intro

    private var startedAt = 0L
    private var playedFromStart = false

    /** Jumped forward early in a video watched from the start = that's the intro length here. */
    private fun learnIntro(from: Long, to: Long) {
        if (!playedFromStart || folder == null) return
        if (from > 30_000_000 || System.currentTimeMillis() - startedAt > 60_000) { playedFromStart = false; return }
        if (to - from < 5_000_000 || to !in 10_000_000..240_000_000) return
        playedFromStart = false
        val old = store.introSkipUs(folder)
        store.setIntroSkip(folder, to)
        showSnack("Запомнил: в этой папке начинать с ${TimeUtil.format(to)} · Отменить") { store.setIntroSkip(folder, old) }
    }

    // ================================================================ 🔀 random video

    /** Another random video from the library (or the phone), from its start. */
    private fun randomVideo() {
        val cur = uri
        Thread {
            val lib = com.dave.framegrabber.player.Library(this)
            val mine = runCatching { lib.scan() }.getOrDefault(emptyList()).map { Uri.fromFile(it.file) to it.name }
            val pool = mine.ifEmpty {
                runCatching { com.dave.framegrabber.ui.browser.VideoSource.allVideos(this) }.getOrDefault(emptyList())
                    .filterIsInstance<com.dave.framegrabber.ui.browser.Entry.Video>().mapNotNull { v -> v.uri?.let { it to v.name } }
            }.filter { it.first != cur }
            ui.post {
                val p = pool.randomOrNull()
                if (p == null) toast("Других видео не нашлось") else open(p.first, -1, p.second)
            }
        }.start()
    }

    // ================================================================ 🎲

    private var randomBusy = false

    private fun randomMoment() {
        if (randomBusy) return
        randomBusy = true
        val dlg = AlertDialog.Builder(this).setMessage("Выбираю видео…").setCancelable(true).create()
        var cancelled = false
        dlg.setOnCancelListener { cancelled = true }
        dlg.show()
        val exclude = uri
        Thread {
            val pick = runCatching { RandomMoment.pick(this, exclude) { s -> ui.post { dlg.setMessage(s) }; !cancelled } }.getOrNull()
            ui.post {
                randomBusy = false
                runCatching { dlg.dismiss() }
                if (cancelled) return@post
                if (pick == null) toast("Не нашёл видео в библиотеке (нужны видео длиннее минуты)")
                else open(pick.uri, pick.startUs, pick.name)
            }
        }.start()
    }

    // ================================================================ controls show / hide, snack

    private val hideControls = Runnable { if (mp?.isPlaying == true && !scrubbing) setControls(false) }

    private fun showControls() { setControls(true); keepControls() }

    private fun keepControls() {
        ui.removeCallbacks(hideControls)
        ui.postDelayed(hideControls, 3500)
    }

    private fun setControls(on: Boolean) {
        if (isInPictureInPictureMode) return
        if (on) {
            controls.visibility = View.VISIBLE
            controls.animate().alpha(1f).setDuration(180).start()
        } else controls.animate().alpha(0f).setDuration(220).withEndAction { controls.visibility = View.GONE }.start()
    }

    private fun toggleControls() = if (controls.visibility == View.VISIBLE && controls.alpha > 0.5f) setControls(false) else showControls()

    private var snackAction: (() -> Unit)? = null
    private val hideSnackR = Runnable { hideSnack() }

    private fun showSnack(text: String, action: () -> Unit) {
        snackAction = action
        snack.text = text
        snack.visibility = View.VISIBLE
        snack.alpha = 0f
        snack.animate().alpha(1f).setDuration(200).start()
        ui.removeCallbacks(hideSnackR)
        ui.postDelayed(hideSnackR, 6000)
    }

    private fun hideSnack() {
        snack.animate().alpha(0f).setDuration(200).withEndAction { snack.visibility = View.GONE }.start()
        snackAction = null
    }

    private fun updateSub() {
        txtSub.text = listOfNotNull(
            if (speed != 1f) "${fmt(speed)}×" else null,
            if (loopA >= 0 && loopB >= 0) "повтор A–B" else null,
            vrView?.let { vrLabel() },
        ).joinToString(" · ")
    }

    // ================================================================ gestures (flat)

    private var scrubbing = false
    private enum class Drag { NONE, SEEK, BRIGHT, VOLUME }
    private var drag = Drag.NONE
    private var dragStartPos = 0L
    private var dragTarget = 0L
    private var startBrightness = 0.5f
    private var startVolume = 0
    private lateinit var audio: AudioManager

    // zoom / move
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { toggleControls(); return true }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val w = gestureLayer.width
                when {
                    e.x < w / 3f -> { seekBy(-10_000_000, show = false); flash(R.drawable.ic_replay10, "−10 с") }
                    e.x > w * 2 / 3f -> { seekBy(10_000_000, show = false); flash(R.drawable.ic_forward10, "+10 с") }
                    else -> togglePlay()
                }
                return true
            }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (e1 == null || e2.pointerCount > 1 || scaling) return false
                val totalX = e2.x - e1.x
                val totalY = e2.y - e1.y
                if (drag == Drag.NONE) {
                    if (abs(totalX) < 24 * d && abs(totalY) < 24 * d) return true
                    drag = when {
                        zoom > 1.01f -> Drag.NONE   // zoomed in: one finger moves the picture
                        abs(totalX) > abs(totalY) -> Drag.SEEK
                        e1.x < gestureLayer.width / 2f -> Drag.BRIGHT
                        else -> Drag.VOLUME
                    }
                    dragStartPos = position
                    startBrightness = window.attributes.screenBrightness.takeIf { it >= 0 }
                        ?: (Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f)
                    startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                    if (drag == Drag.SEEK) scrubbing = true
                }
                when (drag) {
                    Drag.SEEK -> {
                        // a full-width swipe = 90 s (or the whole video if shorter)
                        val span = min(durationUs, 90_000_000L)
                        dragTarget = (dragStartPos + (totalX / gestureLayer.width * span).toLong()).coerceIn(0, durationUs)
                        val diff = dragTarget - dragStartPos
                        info(if (diff >= 0) R.drawable.ic_forward10 else R.drawable.ic_replay10,
                            "${if (diff >= 0) "+" else "−"}${TimeUtil.format(abs(diff))}\n${TimeUtil.format(dragTarget)}", null)
                        timeline.positionUs = dragTarget
                    }
                    Drag.BRIGHT -> {
                        val v = (startBrightness - totalY / (gestureLayer.height * 0.8f)).coerceIn(0.01f, 1f)
                        window.attributes = window.attributes.apply { screenBrightness = v }
                        info(R.drawable.ic_brightness, "${(v * 100).toInt()} %", (v * 100).toInt())
                    }
                    Drag.VOLUME -> {
                        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        val v = (startVolume - totalY / (gestureLayer.height * 0.8f) * max).toInt().coerceIn(0, max)
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                        info(R.drawable.ic_volume, "${v * 100 / max.coerceAtLeast(1)} %", v * 100 / max.coerceAtLeast(1))
                    }
                    Drag.NONE -> if (zoom > 1.01f) { panX -= dx; panY -= dy; fitVideo() }
                }
                return true
            }
        })
        val sg = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(det: ScaleGestureDetector): Boolean { scaling = true; return true }
            override fun onScale(det: ScaleGestureDetector): Boolean {
                val old = zoom
                zoom = (zoom * det.scaleFactor).coerceIn(1f, 6f)
                // keep the point under the fingers where it is
                val cx = det.focusX - gestureLayer.width / 2f
                val cy = det.focusY - gestureLayer.height / 2f
                val k = zoom / old
                panX = cx - (cx - panX) * k
                panY = cy - (cy - panY) * k
                if (zoom <= 1.01f) { zoom = 1f; panX = 0f; panY = 0f }
                fitVideo()
                info(R.drawable.ic_search, "${"%.1f".format(zoom)}×", null)
                return true
            }
        })
        gestureLayer.setOnTouchListener { _, e ->
            sg.onTouchEvent(e)
            if (e.pointerCount > 1 && !sg.isInProgress) {
                // two fingers moving together: move the picture
            }
            gd.onTouchEvent(e)
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
                if (drag == Drag.SEEK) { scrubbing = false; seekTo(dragTarget, userSeek = true) }
                if (drag != Drag.NONE || scaling) hideInfo()
                drag = Drag.NONE
                scaling = false
            }
            true
        }
    }

    private var scaling = false

    private fun info(icon: Int, text: String, level: Int?) {
        gestureIcon.setImageResource(icon)
        gestureText.text = text
        gestureLevel.visibility = if (level == null) View.GONE else View.VISIBLE
        level?.let { gestureLevel.progress = it }
        if (gestureInfo.visibility != View.VISIBLE) { gestureInfo.alpha = 1f; gestureInfo.visibility = View.VISIBLE }
        ui.removeCallbacks(hideInfoR)
    }

    private val hideInfoR = Runnable { gestureInfo.animate().alpha(0f).setDuration(250).withEndAction { gestureInfo.visibility = View.GONE }.start() }
    private fun hideInfo() { ui.removeCallbacks(hideInfoR); ui.postDelayed(hideInfoR, 500) }
    private fun flash(icon: Int, text: String) { info(icon, text, null); hideInfo() }

    /** Fit the video into the screen (keeping its shape), then the zoom / move on top. */
    private fun fitVideo() {
        val vw = textureView.width.toFloat(); val vh = textureView.height.toFloat()
        if (vw <= 0 || vh <= 0 || videoW <= 0 || videoH <= 0) return
        val fit = min(vw / videoW, vh / videoH)
        val sx = videoW * fit / vw * zoom
        val sy = videoH * fit / vh * zoom
        // don't let the picture leave the screen
        val maxX = max(0f, (videoW * fit * zoom - vw) / 2)
        val maxY = max(0f, (videoH * fit * zoom - vh) / 2)
        panX = panX.coerceIn(-maxX, maxX); panY = panY.coerceIn(-maxY, maxY)
        val m = Matrix()
        m.setScale(sx, sy, vw / 2, vh / 2)
        m.postTranslate(panX, panY)
        textureView.setTransform(m)
        textureView.invalidate()
    }

    // ================================================================ picture in picture

    private fun pipParams(): PictureInPictureParams? {
        if (videoW <= 0 || videoH <= 0) return null
        val r = (videoW.toFloat() / videoH).coerceIn(0.42f, 2.38f)
        return PictureInPictureParams.Builder().setAspectRatio(Rational((r * 1000).toInt(), 1000)).build()
    }

    private fun updatePip() { pipParams()?.let { runCatching { setPictureInPictureParams(it) } } }

    private fun enterPip() {
        if (!packageManager.hasSystemFeature("android.software.picture_in_picture")) { toast("Картинка в картинке не поддерживается"); return }
        if (vrView != null) { toast("В режиме VR картинка в картинке недоступна"); return }
        setControls(false)
        runCatching { enterPictureInPictureMode(pipParams() ?: PictureInPictureParams.Builder().build()) }
    }

    // ================================================================ VR

    private var pendingVrSurface: Surface? = null

    /** VR-looking video (by name or shape) and no choice saved yet: offer to switch. */
    private fun maybeOfferVr(u: Uri) {
        if (data.vr.isNotEmpty()) return
        val n = data.name.lowercase()
        val byName = listOf("vr", "180", "360", "sbs", "_lr", "3dh", "_tb", "oculus", "gear").any { n.contains(it) }
        if (!byName) return
        ui.postDelayed({ if (uri == u && vrView == null) showSnack("Похоже на VR-видео · Включить VR") { chooseVr() } }, 1500)
    }

    private val vrModes = listOf(
        "vr180" to "180° · 2 картинки",
        "vr180mono" to "180° · 1 картинка",
        "vr360" to "360°",
        "vr360tb" to "360° · верх-низ",
        "vr360sbs" to "360° · 2 картинки",
    )

    private fun chooseVr() {
        AlertDialog.Builder(this).setTitle("VR-видео")
            .setItems(vrModes.map { it.second }.toTypedArray()) { _, i -> enterVr(vrModes[i].first) }
            .setNegativeButton("Отмена", null).show()
    }

    /** A paused player draws nothing on a new surface until asked: show the current frame. */
    private fun redrawIfPaused() {
        val p = mp ?: return
        if (prepared && !p.isPlaying) p.seekTo(p.currentPosition.toLong(), MediaPlayer.SEEK_CLOSEST)
    }

    private fun vrLabel() = vrModes.firstOrNull { it.first == data.vr }?.second?.let { "VR $it" } ?: "VR"

    /** Public for tests. */
    fun enterVr(mode: String) {
        data.vr = mode
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val v = vrView ?: VrView(this).also { vv ->
            vrView = vv
            vrHost.addView(vv, FrameLayout.LayoutParams(-1, -1))
            vv.onTap = { toggleControls() }
            vv.onSurface = { s -> pendingVrSurface = s; mp?.setSurface(s); redrawIfPaused() }
            vv.start()
        }
        v.fov = if (mode.startsWith("vr360")) VrView.Fov.F360 else VrView.Fov.F180
        v.layout = when (mode) {
            "vr180", "vr360sbs" -> VrView.Layout.SBS
            "vr360tb" -> VrView.Layout.TB
            else -> VrView.Layout.MONO
        }
        vrHost.visibility = View.VISIBLE
        textureView.visibility = View.INVISIBLE
        gestureLayer.visibility = View.GONE
        vrBar.visibility = View.VISIBLE
        btnVr.text = "Обычный"
        buildVrChips()
        updateSub()
        saveState()
        showControls()
        if (!v.hasGyro) toast("В телефоне нет гироскопа — смотри вокруг, водя пальцем")
    }

    private fun exitVr() {
        data.vr = "flat"
        mp?.setSurface(flatSurface)
        redrawIfPaused()
        vrView?.let { it.stop(); vrHost.removeView(it) }
        vrView = null
        pendingVrSurface = null
        vrHost.visibility = View.GONE
        textureView.visibility = View.VISIBLE
        gestureLayer.visibility = View.VISIBLE
        vrBar.visibility = View.GONE
        btnVr.text = "VR"
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        updateSub()
        saveState()
    }

    private fun buildVrChips() {
        vrChips.removeAllViews()
        val v = vrView ?: return
        fun chip(text: String, on: Boolean, click: () -> Unit) = TextView(this, null, 0, R.style.PlayerChip).apply {
            this.text = text
            setBackgroundResource(if (on) R.drawable.bg_chip_on else R.drawable.btn_icon_glass)
            layoutParams = LinearLayout.LayoutParams(-2, (38 * d).toInt()).apply { marginEnd = (8 * d).toInt() }
            setOnClickListener { click(); buildVrChips(); keepControls() }
        }
        vrModes.forEach { (id, label) -> vrChips.addView(chip(label, data.vr == id) { enterVr(id) }) }
        vrChips.addView(chip("👓 Очки", v.stereo) { v.stereo = !v.stereo })
        if (v.hasGyro) vrChips.addView(chip("Гироскоп", v.gyro) { v.setGyroEnabled(!v.gyro) })
        vrChips.addView(chip("⊙ По центру", false) { v.recenter() })
    }
}
