package com.dave.framegrabber.ui.main

import android.text.format.Formatter
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import com.dave.framegrabber.R
import com.dave.framegrabber.core.FrameRender
import com.dave.framegrabber.core.TimeUtil
import com.dave.framegrabber.data.Capture
import com.dave.framegrabber.data.CaptureStore
import com.dave.framegrabber.ui.anim
import com.dave.framegrabber.ui.dp
import com.dave.framegrabber.ui.hideKeyboard
import com.dave.framegrabber.ui.popIn
import com.dave.framegrabber.ui.shake
import com.dave.framegrabber.ui.widget.ZoomImageView
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/**
 * A captured frame opened in the big preview: pinch-zoom, swipe/arrows to flip,
 * and the action bar (Отправить / Сохранить / Момент / Текст / Удалить) instead of the video controls.
 */
class ViewerController(private val s: MainScreen) {

    companion object {
        private const val VIEW_MAX_SIDE = 1600
    }

    /** Frame currently open in the preview; null = the video is shown. */
    var selected: Capture? = null
        private set

    private val captures get() = CaptureStore.captures
    private val viewGen = AtomicInteger()

    private val imgCapture = s.pinned<ZoomImageView>(R.id.imgCapture)
    private val btnClose = s.pinned<View>(R.id.btnCloseCap)
    private val btnPrev = s.pinned<View>(R.id.btnCapPrev)
    private val btnNext = s.pinned<View>(R.id.btnCapNext)
    private val videoBar = s.pinned<View>(R.id.videoBar)
    private val captureBar = s.pinned<ViewGroup>(R.id.captureBar)

    init {
        btnClose.setOnClickListener { close() }
        btnPrev.setOnClickListener { step(-1) }
        btnNext.setOnClickListener { step(+1) }
        imgCapture.onSwipe = { d -> step(d) }
        s.pinned<View>(R.id.actShare).setOnClickListener { selected?.let { s.grid.share(it) } }
        s.pinned<View>(R.id.actSave).setOnClickListener { selected?.let { s.grid.saveOne(it) } }
        s.pinned<View>(R.id.actJump).setOnClickListener { selected?.let { c -> close(); s.video.seekTo(c.timeUs) } }
        s.pinned<View>(R.id.actText).setOnClickListener { selected?.let { s.ocr.recognize(it) } }
        s.pinned<View>(R.id.actDelete).setOnClickListener { deleteSelected() }
    }

    fun open(c: Capture, dir: Int = 0) {
        val wasOpen = selected != null
        if (selected === c) return
        selected = c
        s.grid.refresh()
        s.video.editTime.hideKeyboard()

        imgCapture.resetZoom()
        imgCapture.setImageBitmap(c.thumb) // instant low-res, full image follows
        imgCapture.visibility = View.VISIBLE
        val w = max(s.video.previewCard.width, 1).toFloat()
        when {
            !wasOpen -> {
                imgCapture.alpha = 0f
                imgCapture.translationX = 0f
                imgCapture.scaleX = 0.86f; imgCapture.scaleY = 0.86f
                imgCapture.anim().alpha(1f).scaleX(1f).scaleY(1f).setDuration(380)
                    .setInterpolator(OvershootInterpolator(1.1f)).start()
                s.video.imgPreview.anim().alpha(0f).setDuration(250).start()
                enterChrome()
            }
            dir != 0 -> {   // flipped with swipe / arrows: slide in from that side
                imgCapture.translationX = dir * w * 0.35f
                imgCapture.alpha = 0.3f
                imgCapture.scaleX = 1f; imgCapture.scaleY = 1f
                imgCapture.anim().translationX(0f).alpha(1f).setDuration(300)
                    .setInterpolator(DecelerateInterpolator(1.6f)).start()
            }
            else -> {
                imgCapture.alpha = 0.4f
                imgCapture.scaleX = 0.94f; imgCapture.scaleY = 0.94f
                imgCapture.anim().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260).start()
            }
        }
        updateChrome()
        loadFull(c)
    }

    /** Back to the video. No-op if nothing is open. */
    fun close() {
        if (selected == null) return
        selected = null
        viewGen.incrementAndGet()
        s.grid.refresh()
        imgCapture.anim().alpha(0f).scaleX(0.9f).scaleY(0.9f).setDuration(220)
            .withEndAction { imgCapture.visibility = View.GONE; imgCapture.setImageDrawable(null) }.start()
        s.video.imgPreview.anim().alpha(1f).setDuration(260).start()
        exitChrome()
        s.video.resetChip()
        s.video.showTime()
    }

    private fun step(d: Int) {
        val c = selected ?: return
        val i = captures.indexOf(c) + d
        if (i in captures.indices) open(captures[i], d) else imgCapture.shake(d)
    }

    private fun deleteSelected() {
        val c = selected ?: return
        val idx = captures.indexOf(c)
        imgCapture.anim().alpha(0f).scaleX(0.6f).scaleY(0.6f).translationY(40 * imgCapture.dp).setDuration(240)
            .setInterpolator(AccelerateInterpolator()).withEndAction {
                imgCapture.translationY = 0f
                s.grid.remove(c)
                if (captures.isEmpty()) close() // `selected` is still c, so the exit animation runs
                else openInPlace(captures[idx.coerceAtMost(captures.size - 1)])
            }.start()
    }

    /** Shows another frame while the viewer is already open (after a delete). */
    private fun openInPlace(c: Capture) {
        selected = c
        s.grid.refresh()
        imgCapture.resetZoom()
        imgCapture.setImageBitmap(c.thumb)
        imgCapture.alpha = 0f
        imgCapture.scaleX = 0.85f; imgCapture.scaleY = 0.85f
        imgCapture.anim().alpha(1f).scaleX(1f).scaleY(1f).setDuration(300)
            .setInterpolator(OvershootInterpolator(1.4f)).start()
        updateChrome()
        loadFull(c)
    }

    /** Replace the small thumbnail with the real saved image. */
    private fun loadFull(c: Capture) {
        val gen = viewGen.incrementAndGet()
        s.io.execute {
            val b = runCatching { FrameRender.decodeFile(c.file, VIEW_MAX_SIDE) }.getOrNull()
            s.ui.post { if (gen == viewGen.get() && selected === c && b != null) imgCapture.setImageBitmap(b) }
        }
    }

    /** Chip "КАДР 2 ИЗ 4", badge with the real file resolution, arrows dimmed at the ends. */
    fun updateChrome() {
        val c = selected ?: return
        val i = captures.indexOf(c)
        s.video.setChip(live = false, text = "КАДР ${i + 1} ИЗ ${captures.size}")
        val size = Formatter.formatShortFileSize(s.act, c.file.length())
        s.video.txtTimeBadge.text = buildString {
            if (c.sharpened) append("★ ")   // the sharper neighbour was taken
            append("${c.width}×${c.height} · ${c.fmt.name} · $size")
            append("  ")
            if (!c.exact) append("≈")
            append(TimeUtil.format(c.timeUs))
        }
        btnPrev.anim().alpha(if (i > 0) 1f else 0.3f).setDuration(150).start()
        btnNext.anim().alpha(if (i < captures.size - 1) 1f else 0.3f).setDuration(150).start()
    }

    private fun enterChrome() {
        s.video.setPlayButtonVisible(false)
        listOf(btnClose, btnPrev, btnNext).forEachIndexed { i, v -> v.popIn(60L * i) }
        if (s.video.txtTimeBadge.visibility != View.VISIBLE) s.video.txtTimeBadge.popIn()
        swapBars(out = videoBar, into = captureBar)
    }

    private fun exitChrome() {
        listOf(btnClose, btnPrev, btnNext).forEach { v ->
            v.anim().alpha(0f).scaleX(0.6f).scaleY(0.6f).setDuration(150)
                .withEndAction { v.visibility = View.GONE }.start()
        }
        swapBars(out = captureBar, into = videoBar)
        s.video.setPlayButtonVisible(true)
    }

    /** Context bar swap: video controls ↔ frame actions (the action buttons pop one by one). */
    private fun swapBars(out: View, into: View) {
        val d = out.dp
        out.anim().alpha(0f).translationY(16 * d).setDuration(160)
            .withEndAction { out.visibility = View.GONE; out.translationY = 0f }.start()
        into.visibility = View.VISIBLE
        into.alpha = 0f
        into.translationY = -16 * d
        into.anim().alpha(1f).translationY(0f).setStartDelay(90).setDuration(260).start()
        if (into === captureBar) {
            for (i in 0 until captureBar.childCount) {
                val b = captureBar.getChildAt(i)
                b.scaleX = 0.7f; b.scaleY = 0.7f
                b.anim().scaleX(1f).scaleY(1f).setStartDelay(110L + 45L * i).setDuration(300)
                    .setInterpolator(OvershootInterpolator(2f)).start()
            }
        }
    }
}
