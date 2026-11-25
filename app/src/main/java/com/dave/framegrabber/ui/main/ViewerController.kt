package com.dave.framegrabber.ui.main

import android.text.format.Formatter
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import com.dave.framegrabber.R
import com.dave.framegrabber.core.FrameRender
import com.dave.framegrabber.core.TimeUtil
import com.dave.framegrabber.data.Capture
import com.dave.framegrabber.data.CaptureStore
import com.dave.framegrabber.ui.anim
import com.dave.framegrabber.ui.dp
import com.dave.framegrabber.ui.shake
import com.dave.framegrabber.ui.widget.ZoomImageView
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/**
 * A captured frame opened full screen: pinch-zoom, swipe/arrows to flip, and the actions
 * Отправить / Сохранить / Момент / Текст / Удалить at the bottom.
 */
class ViewerController(private val s: MainScreen) {

    companion object {
        private const val VIEW_MAX_SIDE = 2048
    }

    /** Frame currently open; null = viewer closed. */
    var selected: Capture? = null
        private set

    private val captures get() = CaptureStore.captures
    private val viewGen = AtomicInteger()

    private val root = s.pinned<View>(R.id.viewer)
    private val img = s.pinned<ZoomImageView>(R.id.imgCapture)
    private val top = s.pinned<View>(R.id.viewerTop)
    private val bottom = s.pinned<View>(R.id.viewerBottom)
    private val title = s.pinned<TextView>(R.id.txtViewerTitle)
    private val info = s.pinned<TextView>(R.id.txtViewerInfo)
    private val btnPrev = s.pinned<View>(R.id.btnCapPrev)
    private val btnNext = s.pinned<View>(R.id.btnCapNext)
    private val actions = s.pinned<ViewGroup>(R.id.captureBar)

    init {
        s.pinned<View>(R.id.btnCloseCap).setOnClickListener { close() }
        btnPrev.setOnClickListener { step(-1) }
        btnNext.setOnClickListener { step(+1) }
        img.onSwipe = { d -> step(d) }
        s.pinned<View>(R.id.actShare).setOnClickListener { selected?.let { s.grid.share(it) } }
        s.pinned<View>(R.id.actSave).setOnClickListener { selected?.let { s.grid.saveOne(it) } }
        s.pinned<View>(R.id.actJump).setOnClickListener {
            selected?.let { c -> close(); s.nav.show(NavController.Page.CAPTURE); s.video.seekTo(c.timeUs) }
        }
        s.pinned<View>(R.id.actText).setOnClickListener { selected?.let { s.ocr.recognize(it) } }
        s.pinned<View>(R.id.actDelete).setOnClickListener { deleteSelected() }
    }

    fun open(c: Capture, dir: Int = 0) {
        if (selected === c) return
        val wasOpen = selected != null
        selected = c
        s.grid.refresh()
        s.video.pausePlayback()

        img.resetZoom()
        img.setImageBitmap(c.thumb) // instant low-res, the full image follows
        val w = max(root.width, 1).toFloat()
        when {
            !wasOpen -> {
                root.visibility = View.VISIBLE
                root.alpha = 0f
                root.anim().alpha(1f).setDuration(200).start()
                img.alpha = 0f
                img.translationX = 0f
                img.scaleX = 0.8f; img.scaleY = 0.8f
                img.anim().alpha(1f).scaleX(1f).scaleY(1f).setDuration(380)
                    .setInterpolator(OvershootInterpolator(1.1f)).start()
                slideIn(top, -1); slideIn(bottom, +1)
                popActions()
            }
            dir != 0 -> {   // flipped with swipe / arrows: slide in from that side
                img.translationX = dir * w * 0.35f
                img.alpha = 0.3f
                img.anim().translationX(0f).alpha(1f).setDuration(300).setInterpolator(DecelerateInterpolator(1.6f)).start()
            }
            else -> {
                img.alpha = 0f
                img.scaleX = 0.85f; img.scaleY = 0.85f
                img.anim().alpha(1f).scaleX(1f).scaleY(1f).setDuration(300).setInterpolator(OvershootInterpolator(1.4f)).start()
            }
        }
        updateChrome()
        loadFull(c)
    }

    /** No-op if nothing is open. */
    fun close() {
        if (selected == null) return
        selected = null
        viewGen.incrementAndGet()
        s.grid.refresh()
        img.anim().scaleX(0.9f).scaleY(0.9f).setDuration(200).start()
        root.anim().alpha(0f).setDuration(220).withEndAction {
            root.visibility = View.GONE
            img.setImageDrawable(null); img.scaleX = 1f; img.scaleY = 1f
        }.start()
    }

    private fun step(d: Int) {
        val c = selected ?: return
        val i = captures.indexOf(c) + d
        if (i in captures.indices) open(captures[i], d) else img.shake(d)
    }

    private fun deleteSelected() {
        val c = selected ?: return
        val idx = captures.indexOf(c)
        img.anim().alpha(0f).scaleX(0.6f).scaleY(0.6f).translationY(40 * img.dp).setDuration(240)
            .setInterpolator(AccelerateInterpolator()).withEndAction {
                img.translationY = 0f
                s.grid.remove(c)
                if (captures.isEmpty()) close() // `selected` is still c, so the exit animation runs
                else open(captures[idx.coerceAtMost(captures.size - 1)])
            }.start()
    }

    /** Replace the small thumbnail with the real saved image. */
    private fun loadFull(c: Capture) {
        val gen = viewGen.incrementAndGet()
        s.io.execute {
            val b = runCatching { FrameRender.decodeFile(c.file, VIEW_MAX_SIDE) }.getOrNull()
            s.ui.post { if (gen == viewGen.get() && selected === c && b != null) img.setImageBitmap(b) }
        }
    }

    /** "КАДР 2 ИЗ 4", the real file resolution, arrows dimmed at the ends. */
    fun updateChrome() {
        val c = selected ?: return
        val i = captures.indexOf(c)
        title.text = "КАДР ${i + 1} ИЗ ${captures.size}"
        val size = Formatter.formatShortFileSize(s.act, c.file.length())
        info.text = buildString {
            if (c.sharpened) append("★ ")   // the sharper neighbour was taken
            append("${c.width}×${c.height} · ${c.fmt.name} · $size")
            append("  ")
            if (!c.exact) append("≈")
            append(TimeUtil.format(c.timeUs))
        }
        btnPrev.anim().alpha(if (i > 0) 1f else 0.3f).setDuration(150).start()
        btnNext.anim().alpha(if (i < captures.size - 1) 1f else 0.3f).setDuration(150).start()
    }

    private fun slideIn(v: View, fromDir: Int) {
        v.alpha = 0f
        v.translationY = fromDir * 30 * v.dp
        v.anim().alpha(1f).translationY(0f).setStartDelay(80).setDuration(300).start()
    }

    /** Action buttons pop one by one. */
    private fun popActions() {
        for (i in 0 until actions.childCount) {
            val b = actions.getChildAt(i)
            b.scaleX = 0.7f; b.scaleY = 0.7f
            b.anim().scaleX(1f).scaleY(1f).setStartDelay(140L + 45L * i).setDuration(300)
                .setInterpolator(OvershootInterpolator(2f)).start()
        }
    }
}
