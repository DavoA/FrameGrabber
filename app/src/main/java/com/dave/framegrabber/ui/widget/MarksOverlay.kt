package com.dave.framegrabber.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.dave.framegrabber.R

/** Little flags over the timeline where the marks are. Doesn't take touches. */
class MarksOverlay @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    private val d = resources.displayMetrics.density
    /** Same side padding as the seek bar, so a flag sits exactly under the thumb at that moment. */
    private val pad = 4 * d
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ctx.getColor(R.color.accent); strokeWidth = 2 * d
    }
    private val flag = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.getColor(R.color.accent) }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt(); strokeWidth = 4 * d }
    private val path = Path()

    private var marks: List<Long> = emptyList()
    private var durationUs = 1L

    init {
        isClickable = false
        isFocusable = false
    }

    fun set(marks: List<Long>, durationUs: Long) {
        this.marks = marks.toList()
        this.durationUs = durationUs.coerceAtLeast(1)
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width - 2 * pad
        for (us in marks) {
            val x = pad + w * (us.toFloat() / durationUs).coerceIn(0f, 1f)
            c.drawLine(x, 0f, x, height.toFloat(), shadow)
            c.drawLine(x, 0f, x, height.toFloat(), line)
            path.reset()
            path.moveTo(x - 5 * d, 0f); path.lineTo(x + 5 * d, 0f); path.lineTo(x, 7 * d); path.close()
            c.drawPath(path, flag)
        }
    }
}
