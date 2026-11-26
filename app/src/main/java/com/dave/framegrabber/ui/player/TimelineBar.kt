package com.dave.framegrabber.ui.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.dave.framegrabber.R
import kotlin.math.max
import kotlin.math.min

/**
 * The player's seek bar, in the app's palette:
 *  - the activity curve (where something is going on) as a soft green hill behind the track;
 *  - the heat map (what you watched most) as a warm strip above the track;
 *  - your «лучшие моменты» as stars, the A–B loop as a band;
 *  - drag anywhere to seek.
 */
class TimelineBar @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    interface Listener {
        fun onScrubStart()
        fun onScrub(us: Long)
        fun onScrubEnd(us: Long)
    }

    var listener: Listener? = null
    var durationUs = 1L; set(v) { field = max(1, v); invalidate() }
    var positionUs = 0L; set(v) { if (!scrubbing) { field = v; invalidate() } }
    var activity: FloatArray? = null; set(v) { field = v; invalidate() }
    var heat: FloatArray? = null; set(v) { field = v; invalidate() }
    var moments: List<Long> = emptyList(); set(v) { field = v; invalidate() }
    var loopA = -1L; set(v) { field = v; invalidate() }
    var loopB = -1L; set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private val pad = 14 * d
    private var scrubbing = false

    private val accent = ctx.getColor(R.color.accent)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x44FFFFFF; strokeWidth = 4 * d; strokeCap = Paint.Cap.ROUND }
    private val donePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; strokeWidth = 4 * d; strokeCap = Paint.Cap.ROUND }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val thumbRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; style = Paint.Style.STROKE; strokeWidth = 3 * d }
    private val actPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (accent and 0x00FFFFFF) or 0x55000000 }
    private val heatPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val loopPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FFC857 }
    private val loopEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFC857.toInt(); strokeWidth = 2 * d }
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFC857.toInt() }
    private val path = Path()
    private val rect = RectF()

    private fun xOf(us: Long) = pad + (width - 2 * pad) * (us.toFloat() / durationUs).coerceIn(0f, 1f)
    private fun usOf(x: Float) = ((x - pad) / (width - 2 * pad)).coerceIn(0f, 1f).let { (it * durationUs).toLong() }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), (56 * d).toInt())

    override fun onDraw(c: Canvas) {
        val trackY = height - 14 * d
        val w = width - 2 * pad

        // activity: a hill behind the track
        activity?.takeIf { it.isNotEmpty() }?.let { a ->
            val top = 10 * d
            path.reset()
            path.moveTo(pad, trackY)
            for (i in a.indices) {
                val x = pad + w * (i + 0.5f) / a.size
                path.lineTo(x, trackY - (trackY - top) * a[i] * 0.9f)
            }
            path.lineTo(pad + w, trackY); path.close()
            c.drawPath(path, actPaint)
        }

        // heat map: a strip just above the track; warmer = watched more
        heat?.let { h ->
            // only what was watched MORE than the rest shows (one straight viewing = no colour)
            val watched = h.filter { it > 0f }
            val minH = if (watched.size > h.size * 0.8) watched.minOrNull() ?: 0f else 0f
            val maxH = h.maxOrNull() ?: 0f
            if (maxH - minH > 0.5f) {
                val bw = w / h.size
                for (i in h.indices) {
                    val k = ((h[i] - minH) / (maxH - minH)).coerceIn(0f, 1f)
                    if (k < 0.05f) continue
                    heatPaint.color = heatColor(k)
                    rect.set(pad + i * bw, trackY - 9 * d, pad + (i + 1) * bw + 0.5f, trackY - 5 * d)
                    c.drawRect(rect, heatPaint)
                }
            }
        }

        // A–B loop
        if (loopA >= 0) {
            val xa = xOf(loopA)
            val xb = if (loopB >= 0) xOf(loopB) else xa
            rect.set(xa, trackY - 6 * d, max(xb, xa + 2 * d), trackY + 6 * d)
            c.drawRoundRect(rect, 3 * d, 3 * d, loopPaint)
            c.drawLine(xa, trackY - 10 * d, xa, trackY + 8 * d, loopEdge)
            if (loopB >= 0) c.drawLine(xb, trackY - 10 * d, xb, trackY + 8 * d, loopEdge)
        }

        // track + progress
        val xp = xOf(positionUs)
        c.drawLine(pad, trackY, pad + w, trackY, trackPaint)
        c.drawLine(pad, trackY, xp, trackY, donePaint)

        // moments: little stars under the track
        moments.forEach { m -> star(c, xOf(m), trackY + 9 * d, 4.5f * d) }

        // thumb
        val r = if (scrubbing) 10 * d else 7 * d
        c.drawCircle(xp, trackY, r, thumbPaint)
        c.drawCircle(xp, trackY, r, thumbRing)
    }

    /** green → yellow → pink, matching the app palette. */
    private fun heatColor(k: Float): Int {
        val (r, g, b) = if (k < 0.5f) {
            val t = k / 0.5f
            Triple(lerp(0x5C, 0xFF, t), lerp(0xF5, 0xC8, t), lerp(0xAE, 0x57, t))
        } else {
            val t = (k - 0.5f) / 0.5f
            Triple(lerp(0xFF, 0xFF, t), lerp(0xC8, 0x5C, t), lerp(0x57, 0x7A, t))
        }
        val alpha = (90 + 165 * k).toInt()
        return (alpha shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun lerp(a: Int, b: Int, t: Float) = (a + (b - a) * t).toInt()

    private fun star(c: Canvas, cx: Float, cy: Float, r: Float) {
        path.reset()
        for (i in 0 until 10) {
            val rr = if (i % 2 == 0) r else r * 0.45f
            val ang = Math.PI / 2 + i * Math.PI / 5
            val x = cx + (rr * Math.cos(ang)).toFloat()
            val y = cy - (rr * Math.sin(ang)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        c.drawPath(path, starPaint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                scrubbing = true
                listener?.onScrubStart()
                positionUsInternal(usOf(e.x))
            }
            MotionEvent.ACTION_MOVE -> positionUsInternal(usOf(e.x))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val us = usOf(e.x)
                scrubbing = false
                positionUs = us
                listener?.onScrubEnd(us)
            }
        }
        return true
    }

    private fun positionUsInternal(us: Long) {
        scrubbingPos(us)
        listener?.onScrub(us)
    }

    private fun scrubbingPos(us: Long) {
        val was = scrubbing
        scrubbing = false
        positionUs = min(us, durationUs)
        scrubbing = was
    }
}
