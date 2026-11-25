package com.dave.framegrabber.ui.main

import android.graphics.Bitmap
import android.graphics.Rect
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import com.dave.framegrabber.R
import com.dave.framegrabber.ui.anim
import com.dave.framegrabber.ui.dp
import com.dave.framegrabber.ui.floatLoop
import com.dave.framegrabber.ui.pulse
import com.dave.framegrabber.ui.riseIn

/** Screen-wide animations that involve several parts of the screen at once. */
class Effects(private val s: MainScreen) {

    private val root = s.pinned<FrameLayout>(R.id.root)
    private val flash = s.pinned<View>(R.id.flash)

    /** Logo spins in, title slides, preview and the rest rise up one after another. */
    fun playEntrance() {
        val logo = s.pinned<View>(R.id.logo)
        val titleBox = s.pinned<View>(R.id.titleBox)
        val btnPick = s.pinned<View>(R.id.btnPick)
        val d = root.dp
        logo.scaleX = 0f; logo.scaleY = 0f; logo.rotation = -140f
        logo.anim().scaleX(1f).scaleY(1f).rotation(0f).setDuration(750)
            .setInterpolator(OvershootInterpolator(1.8f)).start()
        titleBox.alpha = 0f; titleBox.translationX = -24 * d
        titleBox.anim().alpha(1f).translationX(0f).setStartDelay(150).setDuration(450).start()
        btnPick.scaleX = 0f; btnPick.scaleY = 0f
        btnPick.anim().scaleX(1f).scaleY(1f).setStartDelay(300).setDuration(400)
            .setInterpolator(OvershootInterpolator(2f)).start()

        val rise = listOf(s.pinned<View>(R.id.previewCard), s.pinned<View>(R.id.actionBar)) +
            listOf(R.id.cardTimeline, R.id.radioTabs, R.id.panelHost, R.id.capHeader).map { s.h<View>(it) }
        rise.forEachIndexed { i, v -> v.riseIn(200L + 70L * i) }
    }

    /** Gentle looping motion: floating placeholder logo, floating empty icon, pulsing "live" dot. */
    fun startIdle() {
        s.pinned<View>(R.id.phIcon).floatLoop(8f, 1400)
        s.h<View>(R.id.emptyIcon).floatLoop(6f, 1700)
        s.pinned<View>(R.id.chipDot).pulse(0.25f, 800)
    }

    /** Camera-like white flash over the preview + a small squeeze of the card and the shutter button. */
    fun shutterFlash(soft: Boolean = false) {
        val card = s.video.previewCard
        flash.alpha = if (soft) 0.35f else 0.9f
        flash.anim().alpha(0f).setDuration(if (soft) 250 else 380).start()
        card.scaleX = 0.97f; card.scaleY = 0.97f
        card.anim().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(OvershootInterpolator(2f)).start()
        if (!soft) {
            val b = s.pinned<View>(R.id.btnCapture)
            b.rotation = 0f
            b.anim().rotationBy(90f).setDuration(350)
                .setInterpolator(OvershootInterpolator()).withEndAction { b.rotation = 0f }.start()
        }
    }

    /**
     * A copy of the new frame shrinks and flies from the preview down to the "Снятые кадры" counter
     * (or to the bottom of the screen if the counter is scrolled away), then [onLanded] runs.
     */
    fun flyToCaptures(bmp: Bitmap, onLanded: () -> Unit) {
        val card = s.video.previewCard
        if (card.width == 0) { onLanded(); return }
        val rootLoc = IntArray(2).also { root.getLocationInWindow(it) }
        val src = IntArray(2).also { card.getLocationInWindow(it) }
        val w = (card.width * 0.55f).toInt()
        val h = (card.height * 0.55f).toInt()

        val counter = s.grid.counter
        val list = s.grid.list
        val r = Rect()
        val (tx, ty) = if (counter.isShown && counter.getGlobalVisibleRect(r) && r.height() > 0) {
            val t = IntArray(2).also { counter.getLocationInWindow(it) }
            (t[0] - rootLoc[0] + counter.width / 2f) to (t[1] - rootLoc[1] + counter.height / 2f)
        } else {
            val l = IntArray(2).also { list.getLocationInWindow(it) }
            (l[0] - rootLoc[0] + list.width / 2f) to (l[1] - rootLoc[1] + list.height - 30 * root.dp)
        }

        val iv = ImageView(s.act).apply {
            setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundResource(R.drawable.bg_thumb)
            clipToOutline = true
            elevation = 16 * root.dp
            layoutParams = FrameLayout.LayoutParams(w, h)
            x = (src[0] - rootLoc[0] + (card.width - w) / 2).toFloat()
            y = (src[1] - rootLoc[1] + (card.height - h) / 2).toFloat()
        }
        root.addView(iv)
        iv.anim()
            .x(tx - w / 2f).y(ty - h / 2f)
            .scaleX(0.12f).scaleY(0.12f).rotation(12f).alpha(0.4f)
            .setDuration(560).setInterpolator(AccelerateInterpolator(1.3f))
            .withEndAction { root.removeView(iv); onLanded() }
            .start()
    }
}
