package com.dave.framegrabber.ui.widget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import com.dave.framegrabber.R
import com.dave.framegrabber.ui.tc
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Pictures full screen, swiped left / right (counter «2 / 12»), swipe down or ✕ closes, a tap
 * hides / shows the counter. Pinch or double-tap to zoom; while zoomed, one finger moves the picture. [preview] gives what is already at hand (shown at once), [full]
 * loads the big picture (background thread).
 */
object PhotoPager {
    private val exec = Executors.newFixedThreadPool(2)

    fun show(act: Activity, count: Int, start: Int, preview: (Int) -> Bitmap?, full: (Int) -> Bitmap?) {
        if (count <= 0) return
        val d = act.resources.displayMetrics.density
        val dlg = Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        // the pager takes a gesture over from the picture only for paging / closing (not while zoomed)
        var x0 = 0f; var y0 = 0f; var mode = 0   // 0 = undecided, 1 = sideways, 2 = down (close)
        val slop = 12 * d
        var current: () -> ZoomImageView? = { null }
        val root = object : FrameLayout(act) {
            override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { x0 = e.rawX; y0 = e.rawY; mode = 0 }
                    MotionEvent.ACTION_MOVE -> {
                        if (e.pointerCount > 1 || current()?.isZoomed == true) return false
                        val mx = e.rawX - x0; val my = e.rawY - y0
                        mode = when {
                            abs(mx) > slop && abs(mx) > abs(my) -> 1
                            my > slop && my > abs(mx) -> 2
                            else -> 0
                        }
                        return mode != 0
                    }
                }
                return false
            }
        }.apply { setBackgroundColor(0xFF000000.toInt()) }
        val done = HashSet<Int>()
        val asked = HashSet<Int>()
        val views = List(count) { i ->
            ZoomImageView(act).apply {
                preview(i)?.let { setImageBitmap(it) }
                root.addView(this, FrameLayout.LayoutParams(-1, -1))
            }
        }
        val spinner = ProgressBar(act).apply { indeterminateTintList = ColorStateList.valueOf(act.tc(R.color.accent)) }
        root.addView(spinner, FrameLayout.LayoutParams((40 * d).toInt(), (40 * d).toInt(), Gravity.CENTER))
        val counter = TextView(act).apply {
            textSize = 14f; setTextColor(0xFFFFFFFF.toInt()); typeface = Typeface.DEFAULT_BOLD
            setBackgroundResource(R.drawable.bg_badge)
            setPadding((12 * d).toInt(), (4 * d).toInt(), (12 * d).toInt(), (4 * d).toInt())
        }
        root.addView(counter, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = (18 * d).toInt() })
        root.addView(ImageView(act).apply {
            setImageResource(R.drawable.ic_close); setColorFilter(0xFFFFFFFF.toInt())
            setBackgroundResource(R.drawable.bg_badge)
            val p = (9 * d).toInt(); setPadding(p, p, p, p)
            setOnClickListener { dlg.dismiss() }
        }, FrameLayout.LayoutParams((42 * d).toInt(), (42 * d).toInt(), Gravity.TOP or Gravity.END).apply { setMargins(0, (12 * d).toInt(), (12 * d).toInt(), 0) })

        var index = start.coerceIn(0, count - 1)
        current = { views.getOrNull(index) }
        views.forEach { v -> v.onTap = { counter.visibility = if (counter.visibility == View.VISIBLE) View.INVISIBLE else View.VISIBLE } }
        var dx = 0f; var dy = 0f
        fun width() = root.width.takeIf { it > 0 }?.toFloat() ?: act.resources.displayMetrics.widthPixels.toFloat()
        fun layout() {
            val w = width()
            views.forEachIndexed { i, v ->
                v.translationX = (i - index) * w + dx
                v.translationY = if (i == index) dy else 0f
                v.visibility = if (abs(i - index) <= 1) View.VISIBLE else View.INVISIBLE
            }
            root.background.alpha = (255 * (1f - abs(dy) / (root.height.coerceAtLeast(1) * 0.6f))).toInt().coerceIn(60, 255)
            counter.text = "${index + 1} / $count"
            spinner.visibility = if (index in done) View.GONE else View.VISIBLE
        }
        fun ensure() {
            for (i in listOf(index, index + 1, index - 1, index + 2)) {
                if (i !in 0 until count || !asked.add(i)) continue
                exec.execute {
                    val b = runCatching { full(i) }.getOrNull()
                    act.runOnUiThread { if (b != null && !views[i].isZoomed) views[i].setImageBitmap(b) else if (b != null) views[i].setImageBitmap(b); done += i; layout() }
                }
            }
        }
        fun animateTo(tx: Float, ty: Float, end: () -> Unit = {}) {
            val fx = dx; val fy = dy
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 220; interpolator = DecelerateInterpolator()
                addUpdateListener { a -> val t = a.animatedValue as Float; dx = fx + (tx - fx) * t; dy = fy + (ty - fy) * t; layout() }
                addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(a: Animator) { end() } })
                start()
            }
        }
        root.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x0 = e.rawX; y0 = e.rawY; mode = 0 }
                MotionEvent.ACTION_MOVE -> {
                    val mx = e.rawX - x0; val my = e.rawY - y0
                    if (mode == 0) mode = when {
                        abs(mx) > slop && abs(mx) > abs(my) -> 1
                        my > slop && my > abs(mx) -> 2
                        else -> 0
                    }
                    if (mode == 1) { dx = if ((index == 0 && mx > 0) || (index == count - 1 && mx < 0)) mx / 3 else mx; layout() }
                    else if (mode == 2) { dy = my; layout() }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val w = width()
                    when (mode) {
                        1 -> {
                            val old = index
                            if (dx < -w / 6 && index < count - 1) index++ else if (dx > w / 6 && index > 0) index--
                            dx += (index - old) * w
                            if (index != old) views[old].resetZoom()
                            ensure(); animateTo(0f, 0f)
                        }
                        2 -> if (dy > root.height / 6f) animateTo(0f, root.height.toFloat()) { dlg.dismiss() } else animateTo(0f, 0f)
                        else -> {}
                    }
                    mode = 0
                }
            }
            true
        }
        dlg.setContentView(root)
        dlg.show()
        root.post { layout(); ensure() }
    }
}
