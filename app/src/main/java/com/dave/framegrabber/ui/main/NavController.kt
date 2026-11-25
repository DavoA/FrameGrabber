package com.dave.framegrabber.ui.main

import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import com.dave.framegrabber.R
import com.dave.framegrabber.ui.anim
import com.dave.framegrabber.ui.bump
import com.dave.framegrabber.ui.popIn
import com.dave.framegrabber.ui.switchPanel

/** Bottom bar: «Съёмка» / «Кадры» / «Клипы» / «Инструменты», with a frame counter on «Кадры». */
class NavController(private val s: MainScreen) {

    enum class Page { CAPTURE, FRAMES, CLIPS, TOOLS }

    var page = Page.CAPTURE
        private set

    private class Item(val root: View, val pill: View, val icon: ImageView, val label: TextView)

    private val pages = mapOf(
        Page.CAPTURE to s.pinned<View>(R.id.pageCapture),
        Page.FRAMES to s.pinned<View>(R.id.list),
        Page.CLIPS to s.pinned<View>(R.id.pageClips),
        Page.TOOLS to s.pinned<View>(R.id.pageTools),
    )
    private val items = mapOf(
        Page.CAPTURE to item(R.id.navCapture, R.id.navCapturePill, R.id.navCaptureIcon, R.id.navCaptureLabel),
        Page.FRAMES to item(R.id.navFrames, R.id.navFramesPill, R.id.navFramesIcon, R.id.navFramesLabel),
        Page.CLIPS to item(R.id.navClips, R.id.navClipsPill, R.id.navClipsIcon, R.id.navClipsLabel),
        Page.TOOLS to item(R.id.navTools, R.id.navToolsPill, R.id.navToolsIcon, R.id.navToolsLabel),
    )
    private val badge = s.pinned<TextView>(R.id.navBadge)

    /** Where a newly taken frame "flies" to. */
    val framesTarget: View get() = items.getValue(Page.FRAMES).icon

    private fun item(root: Int, pill: Int, icon: Int, label: Int) =
        Item(s.pinned(root), s.pinned(pill), s.pinned(icon), s.pinned(label))

    init {
        items.forEach { (p, it) -> it.root.setOnClickListener { show(p) } }
        Page.values().forEach { highlight(it, it == page, animate = false) }
    }

    fun show(target: Page) {
        if (target == page) return
        val from = page
        page = target
        switchPanel(pages.getValue(from), pages.getValue(target), if (target.ordinal > from.ordinal) 1 else -1)
        highlight(from, false, animate = true)
        highlight(target, true, animate = true)
        if (target != Page.CAPTURE) s.video.pausePlayback()
    }

    private fun highlight(p: Page, on: Boolean, animate: Boolean) {
        val it = items.getValue(p)
        val color = s.act.getColor(if (on) R.color.accent else R.color.textDim)
        it.icon.imageTintList = android.content.res.ColorStateList.valueOf(color)
        it.label.setTextColor(color)
        if (!animate) { it.pill.alpha = if (on) 1f else 0f; return }
        if (on) {
            it.pill.scaleX = 0.4f
            it.pill.anim().alpha(1f).scaleX(1f).setDuration(280).setInterpolator(OvershootInterpolator(1.6f)).start()
            it.icon.bump()
        } else it.pill.anim().alpha(0f).setDuration(150).start()
    }

    /** Number of captured frames on the «Кадры» tab. */
    fun setCount(n: Int, animate: Boolean) {
        if (n == 0) { badge.visibility = View.GONE; badge.text = ""; return }
        val changed = badge.text.toString() != n.toString()
        badge.text = if (n > 99) "99+" else n.toString()
        if (badge.visibility != View.VISIBLE) { if (animate) badge.popIn() else badge.visibility = View.VISIBLE }
        else if (changed && animate) badge.bump()
    }
}
