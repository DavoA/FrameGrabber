package com.dave.framegrabber.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import com.dave.framegrabber.core.*
import com.dave.framegrabber.data.*
import com.dave.framegrabber.export.*
import com.dave.framegrabber.batch.*
import com.dave.framegrabber.ocr.*
import com.dave.framegrabber.ui.*
import com.dave.framegrabber.R

/**
 * ImageView with pinch-to-zoom, pan and double-tap zoom.
 * When not zoomed, horizontal flings are reported via [onSwipe] (used to flip between frames).
 */
class ZoomImageView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : ImageView(ctx, attrs) {

    var onSwipe: ((dir: Int) -> Unit)? = null
    private val maxScale = 6f
    private val m = Matrix()
    private var base = 1f          // fit-center scale
    private var scale = 1f         // extra zoom on top of fit (1 = fitted)
    private val vals = FloatArray(9)
    private var anim: ValueAnimator? = null

    val isZoomed get() = scale > 1.02f
    /** False while ImageView's constructor runs (it may call setImageDrawable before our fields exist). */
    private var ready = false

    init {
        scaleType = ScaleType.MATRIX
        ready = true
    }

    override fun setImageDrawable(drawable: Drawable?) {
        if (!ready) { super.setImageDrawable(drawable); return }
        val sameSize = drawable != null && this.drawable != null &&
            drawable.intrinsicWidth * this.drawable.intrinsicHeight == drawable.intrinsicHeight * this.drawable.intrinsicWidth
        super.setImageDrawable(drawable)
        // keep zoom when the low-res placeholder is replaced by the full image of the same frame
        if (sameSize && isZoomed) refit(keepZoom = true) else { scale = 1f; refit(keepZoom = false) }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (!ready) return
        scale = 1f; refit(false)
    }

    fun resetZoom() { anim?.cancel(); scale = 1f; refit(false) }

    private fun refit(keepZoom: Boolean) {
        val d = drawable ?: return
        if (width == 0 || height == 0 || d.intrinsicWidth <= 0) return
        val oldBase = base
        base = min(width / d.intrinsicWidth.toFloat(), height / d.intrinsicHeight.toFloat())
        if (keepZoom) {
            // same frame at another resolution: rescale the matrix around the view center
            m.postScale(base / oldBase, base / oldBase, width / 2f, height / 2f)
        } else {
            m.reset()
            m.postScale(base, base)
            m.postTranslate((width - d.intrinsicWidth * base) / 2f, (height - d.intrinsicHeight * base) / 2f)
        }
        clamp(); imageMatrix = m
    }

    private fun bounds(): RectF {
        val d = drawable ?: return RectF()
        val r = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        m.mapRect(r); return r
    }

    /** Keep the image covering the view (or centered when smaller). */
    private fun clamp() {
        val r = bounds()
        var dx = 0f; var dy = 0f
        if (r.width() <= width) dx = (width - r.width()) / 2f - r.left
        else { if (r.left > 0) dx = -r.left; if (r.right < width) dx = width - r.right }
        if (r.height() <= height) dy = (height - r.height()) / 2f - r.top
        else { if (r.top > 0) dy = -r.top; if (r.bottom < height) dy = height - r.bottom }
        m.postTranslate(dx, dy)
    }

    private fun zoomBy(f: Float, px: Float, py: Float) {
        val target = (scale * f).coerceIn(1f, maxScale)
        val real = target / scale
        scale = target
        m.postScale(real, real, px, py)
        clamp(); imageMatrix = m
    }

    private fun animateZoomTo(target: Float, px: Float, py: Float) {
        anim?.cancel()
        val from = scale
        anim = ValueAnimator.ofFloat(from, target).apply {
            duration = 260; interpolator = DecelerateInterpolator()
            addUpdateListener { zoomBy((it.animatedValue as Float) / scale, px, py) }
            start()
        }
    }

    private val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoomBy(d.scaleFactor, d.focusX, d.focusY); return true
        }
    })

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (!isZoomed) return false
            m.postTranslate(-dx, -dy); clamp(); imageMatrix = m
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (isZoomed) animateZoomTo(1f, e.x, e.y) else animateZoomTo(2.5f, e.x, e.y)
            return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (isZoomed || e1 == null) return false
            val dx = e2.x - e1.x
            if (abs(dx) > 60 * resources.displayMetrics.density && abs(dx) > abs(e2.y - e1.y)) {
                onSwipe?.invoke(if (dx < 0) +1 else -1); return true
            }
            return false
        }
    })

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(ev)
        if (!scaleDetector.isInProgress) gestures.onTouchEvent(ev)
        // don't let the list scroll steal the gesture while zoomed
        parent?.requestDisallowInterceptTouchEvent(isZoomed || ev.pointerCount > 1)
        return true
    }
}
