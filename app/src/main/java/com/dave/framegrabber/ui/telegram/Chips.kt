package com.dave.framegrabber.ui.telegram

import com.dave.framegrabber.ui.tc

import android.animation.AnimatorInflater
import android.content.Context
import android.view.Gravity
import android.widget.TextView
import com.dave.framegrabber.R

/** Round chips used by the Telegram screens (hashtags, models). */
object Chips {
    fun make(ctx: Context, text: String, on: Boolean = false): TextView {
        val d = ctx.resources.displayMetrics.density
        return TextView(ctx).apply {
            this.text = text
            textSize = 14f
            gravity = Gravity.CENTER
            minHeight = (36 * d).toInt()
            setPadding((14 * d).toInt(), (6 * d).toInt(), (14 * d).toInt(), (6 * d).toInt())
            stateListAnimator = AnimatorInflater.loadStateListAnimator(ctx, R.animator.press_scale)
            isClickable = true
            setOn(on)
        }
    }

    fun TextView.setOn(on: Boolean) {
        setBackgroundResource(if (on) R.drawable.bg_chip_on else R.drawable.bg_chip)
        setTextColor(context.tc(if (on) R.color.text else R.color.textDim))
        isSelected = on
    }
}
