package com.dave.framegrabber.ui.main

import com.dave.framegrabber.ui.tc

import android.animation.AnimatorInflater
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.dave.framegrabber.R
import com.dave.framegrabber.core.TimeUtil
import com.dave.framegrabber.ui.bump
import com.dave.framegrabber.ui.dp
import com.dave.framegrabber.ui.popIn
import com.dave.framegrabber.ui.toast
import com.dave.framegrabber.ui.widget.MarksOverlay
import kotlin.math.abs

/**
 * Marks on the timeline (page «Съёмка»): «🔖 Метка» puts one at the current moment; the chips
 * under the timeline jump there (tap) or remove it (hold); the last chip takes a frame at every mark.
 * Saved with the project.
 */
class MarksPanel(private val s: MainScreen) {

    private val btnMark = s.h<TextView>(R.id.btnMark)
    private val scroll = s.h<HorizontalScrollView>(R.id.marksScroll)
    private val row = s.h<LinearLayout>(R.id.marksRow)
    private val overlay = s.h<MarksOverlay>(R.id.marksOverlay)

    /** Sorted, in microseconds. */
    val marks = mutableListOf<Long>()

    init {
        btnMark.setOnClickListener { addHere() }
    }

    private fun addHere() {
        val e = s.video.requireEngine() ?: return
        val us = s.video.currentUs
        if (marks.any { abs(it - us) < e.frameUs }) { s.act.toast("Здесь уже есть метка"); return }
        marks += us
        marks.sort()
        btnMark.bump()
        rebuild(added = us)
        s.project.dirty()
    }

    private fun remove(us: Long) {
        marks.remove(us)
        rebuild()
        s.act.toast("Метка ${TimeUtil.format(us)} убрана")
        s.project.dirty()
    }

    /** A project was opened (or a new video with no marks). */
    fun set(list: List<Long>) {
        marks.clear(); marks += list.sorted()
        rebuild()
    }

    private fun rebuild(added: Long? = null) {
        overlay.set(marks, s.video.engine?.durationUs ?: 1)
        row.removeAllViews()
        if (marks.isEmpty()) { scroll.visibility = View.GONE; return }
        scroll.visibility = View.VISIBLE
        for (us in marks) {
            val chip = chip("🔖 " + TimeUtil.format(us))
            chip.setOnClickListener { s.viewer.close(); s.video.seekTo(us) }
            chip.setOnLongClickListener { remove(us); true }
            row.addView(chip)
            if (us == added) {
                chip.popIn()
                // keep the new chip in view
                scroll.post { scroll.smoothScrollTo((chip.left - 40 * chip.dp).toInt().coerceAtLeast(0), 0) }
            }
        }
        if (marks.size > 1) {
            val all = chip("📷 Снять все (${marks.size})", strong = true)
            all.setOnClickListener { s.shutter.captureMany(marks.toList()) }
            row.addView(all)
        }
    }

    private fun chip(text: String, strong: Boolean = false): TextView {
        val ctx = s.act
        val d = row.dp
        return TextView(ctx).apply {
            this.text = text
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(ctx.tc(if (strong) R.color.accent else R.color.text))
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_chip)
            setPadding((12 * d).toInt(), 0, (12 * d).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(-2, (34 * d).toInt()).apply { marginEnd = (8 * d).toInt() }
            stateListAnimator = AnimatorInflater.loadStateListAnimator(ctx, R.animator.press_scale)
            isClickable = true
        }
    }
}
