package com.dave.framegrabber.ui.main

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.provider.OpenableColumns
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.dave.framegrabber.R
import com.dave.framegrabber.core.FrameEngine
import com.dave.framegrabber.core.TimeUtil
import com.dave.framegrabber.ui.anim
import com.dave.framegrabber.ui.dp
import com.dave.framegrabber.ui.hideKeyboard
import com.dave.framegrabber.ui.popIn
import com.dave.framegrabber.ui.showError
import com.dave.framegrabber.ui.toast
import com.dave.framegrabber.ui.wiggle
import java.util.concurrent.atomic.AtomicInteger

/**
 * The opened video: loading it, the big preview, the timeline (filmstrip + seek bar),
 * frame stepping, the time field and playback with sound.
 */
class VideoController(private val s: MainScreen) {

    companion object {
        private const val FILMSTRIP_COUNT = 10
    }

    /** The opened video; all calls to it must go through [MainScreen.exec]. */
    @Volatile var engine: FrameEngine? = null
        private set
    var uri: Uri? = null
        private set
    /** File name of the video, used in output file names. */
    var baseName = "video"
        private set
    /** Current position in microseconds. */
    var currentUs = 0L

    private val loadGen = AtomicInteger()
    private val previewGen = AtomicInteger()

    private val btnPick = s.pinned<ImageButton>(R.id.btnPick)
    private val txtInfo = s.pinned<TextView>(R.id.txtInfo)
    val previewCard = s.pinned<FrameLayout>(R.id.previewCard)
    val imgPreview = s.pinned<ImageView>(R.id.imgPreview)
    private val placeholder = s.pinned<View>(R.id.placeholder)
    private val chipMode = s.pinned<View>(R.id.chipMode)
    private val chipDot = s.pinned<View>(R.id.chipDot)
    private val chipText = s.pinned<TextView>(R.id.chipText)
    val txtTimeBadge = s.pinned<TextView>(R.id.txtTimeBadge)
    val editTime = s.pinned<EditText>(R.id.editTime)
    private val videoSurface = s.pinned<TextureView>(R.id.videoSurface)
    private val btnPlay = s.pinned<ImageButton>(R.id.btnPlay)
    private val seek = s.h<SeekBar>(R.id.seek)
    private val filmstrip = s.h<LinearLayout>(R.id.filmstrip)
    private val txtDuration = s.h<TextView>(R.id.txtDuration)

    private var player: MediaPlayer? = null
    var playerReady = false
        private set
    private val playTicker = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (!p.isPlaying) return
            currentUs = p.currentPosition * 1000L
            seek.progress = p.currentPosition
            txtTimeBadge.text = TimeUtil.format(currentUs)
            s.ui.postDelayed(this, 40)
        }
    }

    init {
        previewCard.clipToOutline = true
        s.h<View>(R.id.timelineBox).clipToOutline = true
        // Preview height adapts to the screen: ~16:10 of the width, but never more than a third of the height
        previewCard.addOnLayoutChangeListener { v, l, _, r, _, ol, _, or_, _ ->
            if (r - l == or_ - ol) return@addOnLayoutChangeListener
            val want = ((r - l) * 0.62f).toInt()
                .coerceIn((170 * v.dp).toInt(), (v.resources.displayMetrics.heightPixels * 0.34f).toInt())
            if (v.layoutParams.height != want) v.post { v.layoutParams.height = want; v.requestLayout() }
        }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                currentUs = p * 1000L
                showTime()
                requestPreview(exact = false)
            }
            override fun onStartTrackingTouch(sb: SeekBar) = s.viewer.close()
            override fun onStopTrackingTouch(sb: SeekBar) = requestPreview(exact = true)
        })
        s.pinned<View>(R.id.btnPrev).setOnClickListener { stepFrame(-1) }
        s.pinned<View>(R.id.btnNext).setOnClickListener { stepFrame(+1) }
        editTime.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                val us = TimeUtil.parse(v.text.toString())
                if (us == null) s.act.toast("Формат времени: 12.5 или 1:23.5") else seekTo(us)
                v.hideKeyboard()
                true
            } else false
        }
        btnPlay.setOnClickListener { togglePlayback() }
        videoSurface.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                player?.setSurface(Surface(st)); fitVideo()
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = fitVideo()
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    /** The engine, or a hint to pick a video first (then null). */
    fun requireEngine(): FrameEngine? = engine ?: run { nudgePick(); null }

    fun nudgePick() {
        s.act.toast("Сначала выбери видео")
        btnPick.wiggle()
    }

    // ================================================================ loading

    fun load(uri: Uri) {
        s.viewer.close()
        pausePlayback()
        txtInfo.text = "Открываю…"
        val gen = loadGen.incrementAndGet()
        s.exec.execute {
            val res = runCatching { FrameEngine(s.act, uri) }
            s.ui.post {
                if (gen != loadGen.get()) { res.getOrNull()?.let { e -> s.exec.execute { e.close() } }; return@post }
                res.onSuccess { e -> onLoaded(e, uri) }
                    .onFailure {
                        txtInfo.text = "Не удалось открыть видео"
                        s.act.showError("Не удалось открыть видео", it)
                    }
            }
        }
    }

    private fun onLoaded(e: FrameEngine, uri: Uri) {
        engine?.let { old -> s.exec.execute { old.close() } }
        engine = e
        this.uri = uri
        baseName = displayName(uri)
        currentUs = 0
        preparePlayer(uri)
        s.recent.remember(uri, e)
        s.recent.hide()
        seek.max = (e.durationUs / 1000).toInt()
        seek.progress = 0
        txtInfo.text = "$baseName · ${e.width}×${e.height} · %.0f fps".format(e.fps)
        txtDuration.text = TimeUtil.format(e.durationUs)
        showTime()
        s.range.onVideoChanged()
        s.batch.updateHint()

        // placeholder out, preview in
        if (placeholder.visibility == View.VISIBLE) {
            placeholder.anim().alpha(0f).scaleX(0.9f).scaleY(0.9f).setDuration(250)
                .withEndAction { placeholder.visibility = View.GONE }.start()
        }
        imgPreview.alpha = 0f
        imgPreview.scaleX = 1.08f; imgPreview.scaleY = 1.08f
        imgPreview.anim().alpha(1f).scaleX(1f).scaleY(1f).setDuration(550)
            .setInterpolator(DecelerateInterpolator(2f)).start()
        chipMode.popIn(200)
        txtTimeBadge.popIn(280)
        setChip(live = true, text = "ВИДЕО")

        requestPreview(exact = true)
        buildFilmstrip(e)
    }

    private fun displayName(uri: Uri): String {
        val name = runCatching {
            s.act.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "video"
        return name.substringBeforeLast('.').replace(Regex("[^\\p{L}\\p{N}._-]+"), "_").take(60).ifEmpty { "video" }
    }

    /** Thumbnails fill the timeline track; the seek bar sits on top of them. */
    private fun buildFilmstrip(e: FrameEngine) {
        filmstrip.removeAllViews()
        val times = (0 until FILMSTRIP_COUNT).map { i -> e.lastUs * i / (FILMSTRIP_COUNT - 1) }
        val views = times.map {
            ImageView(s.act).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                scaleType = ImageView.ScaleType.CENTER_CROP
                alpha = 0f
            }.also { filmstrip.addView(it) }
        }
        times.forEachIndexed { i, t ->
            s.exec.execute {
                if (engine !== e) return@execute
                val b = runCatching { e.scaledFrame(t, 200, 200, exact = false) }.getOrNull()
                s.ui.post {
                    if (engine === e && b != null) {
                        views[i].setImageBitmap(b)
                        views[i].translationY = 14 * views[i].dp
                        views[i].anim().alpha(1f).translationY(0f).setStartDelay(40L * i)
                            .setDuration(320).setInterpolator(DecelerateInterpolator()).start()
                    }
                }
            }
        }
    }

    // ================================================================ position & preview

    fun seekTo(us: Long) {
        val e = engine ?: return
        currentUs = us.coerceIn(0L, e.lastUs)
        seek.progress = (currentUs / 1000).toInt()
        showTime()
        requestPreview(exact = true, animate = true)
    }

    private fun stepFrame(dir: Int) {
        val e = engine ?: return
        s.viewer.close()
        seekTo(currentUs + dir * e.frameUs)
    }

    /** Only the most recent request is rendered; stale ones are skipped while scrubbing. */
    fun requestPreview(exact: Boolean, animate: Boolean = false) {
        val e = engine ?: return
        val gen = previewGen.incrementAndGet()
        val us = currentUs
        s.exec.execute {
            if (gen != previewGen.get() || engine !== e) return@execute
            val b = runCatching { e.scaledFrame(us, 1280, 1280, exact) }.getOrNull()
            s.ui.post {
                if (gen == previewGen.get() && b != null) {
                    imgPreview.setImageBitmap(b)
                    if (animate && s.viewer.selected == null) {
                        imgPreview.alpha = 0.55f
                        imgPreview.anim().alpha(1f).setDuration(220).start()
                    }
                }
            }
        }
    }

    /** Current time into the time field (and the preview badge while the video is shown). */
    fun showTime() {
        val t = TimeUtil.format(currentUs)
        editTime.setText(t)
        if (s.viewer.selected == null) txtTimeBadge.text = t
    }

    /** The chip in the preview corner: "● ВИДЕО", "КАДР 2 ИЗ 4", ... */
    fun setChip(live: Boolean, text: String) {
        chipText.text = text
        chipDot.visibility = if (live) View.VISIBLE else View.GONE
        if (chipMode.visibility != View.VISIBLE) chipMode.popIn()
    }

    /** Back to the video chip (or hide it if nothing is open). */
    fun resetChip() {
        if (engine != null) setChip(live = true, text = "ВИДЕО") else chipMode.visibility = View.GONE
    }

    /** Chip pieces the screen animates (idle pulse). */
    val liveDot: View get() = chipDot

    // ================================================================ playback with sound

    private fun preparePlayer(uri: Uri) {
        player?.release()
        playerReady = false
        btnPlay.visibility = View.GONE
        val mp = MediaPlayer()
        player = mp
        runCatching {
            mp.setDataSource(s.act, uri)
            mp.setOnPreparedListener {
                playerReady = true
                if (s.viewer.selected == null) btnPlay.popIn(350)
                fitVideo()
            }
            mp.setOnCompletionListener { pausePlayback() }
            mp.setOnErrorListener { _, _, _ -> playerReady = false; btnPlay.visibility = View.GONE; true }
            if (videoSurface.isAvailable) mp.setSurface(Surface(videoSurface.surfaceTexture))
            mp.prepareAsync()
        }.onFailure { player = null }
    }

    /** Letterbox the video inside the TextureView (TextureView stretches by default). */
    private fun fitVideo() {
        val e = engine ?: return
        val vw = videoSurface.width.toFloat(); val vh = videoSurface.height.toFloat()
        if (vw == 0f || e.width == 0) return
        val sc = minOf(vw / e.width, vh / e.height)
        videoSurface.setTransform(Matrix().apply { setScale(e.width * sc / vw, e.height * sc / vh, vw / 2, vh / 2) })
    }

    private fun togglePlayback() {
        val p = player ?: return
        if (!playerReady) return
        if (p.isPlaying) { pausePlayback(); return }
        s.viewer.close()
        if (currentUs >= (engine?.lastUs ?: 0) - 200_000) currentUs = 0
        p.seekTo(currentUs / 1000, MediaPlayer.SEEK_CLOSEST)
        videoSurface.visibility = View.VISIBLE
        videoSurface.alpha = 0f
        videoSurface.anim().alpha(1f).setDuration(200).start()
        p.start()
        btnPlay.setImageResource(R.drawable.ic_pause)
        setChip(live = true, text = "ВОСПРОИЗВЕДЕНИЕ")
        s.ui.post(playTicker)
    }

    /** Stops playback and shows the exact frame where it stopped. */
    fun pausePlayback() {
        val p = player ?: return
        if (!playerReady) return
        val wasPlaying = runCatching { p.isPlaying }.getOrDefault(false)
        if (wasPlaying) p.pause()
        s.ui.removeCallbacks(playTicker)
        btnPlay.setImageResource(R.drawable.ic_play)
        if (videoSurface.visibility == View.VISIBLE) {
            if (wasPlaying) currentUs = p.currentPosition * 1000L
            seek.progress = (currentUs / 1000).toInt()
            showTime()
            requestPreview(exact = true)
            videoSurface.anim().alpha(0f).setStartDelay(150).setDuration(200)
                .withEndAction { videoSurface.visibility = View.GONE }.start()
            if (s.viewer.selected == null) setChip(live = true, text = "ВИДЕО")
        }
    }

    /** The viewer hides the play button while a frame is open. */
    fun setPlayButtonVisible(show: Boolean) {
        if (show) { if (playerReady) btnPlay.popIn(150) }
        else btnPlay.anim().alpha(0f).setDuration(150).withEndAction { btnPlay.visibility = View.GONE }.start()
    }

    fun release() {
        player?.release(); player = null
        val e = engine
        s.exec.execute { e?.close() }
    }
}
