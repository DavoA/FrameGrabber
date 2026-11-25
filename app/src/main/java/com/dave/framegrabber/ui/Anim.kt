package com.dave.framegrabber.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.View
import android.view.ViewPropertyAnimator
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

/** Small animation toolkit shared by all screens. */

val View.dp: Float get() = resources.displayMetrics.density

/**
 * View.animate() reuses one animator per view and REMEMBERS the last start delay and interpolator,
 * so a delayed animation would make every later one on that view delayed too. Reset them each time.
 */
fun View.anim(): ViewPropertyAnimator = animate().apply {
    cancel()
    startDelay = 0
    interpolator = DecelerateInterpolator()
}

/** Appear with a little overshoot. */
fun View.popIn(delay: Long = 0) {
    visibility = View.VISIBLE
    alpha = 0f
    scaleX = 0.6f; scaleY = 0.6f
    anim().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(delay).setDuration(280)
        .setInterpolator(OvershootInterpolator(2f)).start()
}

/** Quick "boing" (e.g. a counter changed). */
fun View.bump() {
    scaleX = 1.4f; scaleY = 1.4f
    anim().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(OvershootInterpolator(3f)).start()
}

/** Sideways shake: "can't go further". */
fun View.shake(dir: Int) {
    ObjectAnimator.ofFloat(this, View.TRANSLATION_X, 0f, -dir * 18 * dp, dir * 8 * dp, 0f).apply {
        duration = 300; start()
    }
}

/** Wiggle: "look here". */
fun View.wiggle() {
    ObjectAnimator.ofFloat(this, View.ROTATION, 0f, -12f, 10f, -6f, 0f).apply { duration = 450; start() }
    bump()
}

/** Fade + slide down into place. */
fun View.showAnimated() {
    visibility = View.VISIBLE
    alpha = 0f
    translationY = -8 * dp
    anim().alpha(1f).translationY(0f).setDuration(250).start()
}

/** Fade out, then GONE, then reset alpha. */
fun View.hideAnimated(delay: Long = 0, duration: Long = 250) {
    anim().alpha(0f).setStartDelay(delay).setDuration(duration)
        .withEndAction { visibility = View.GONE; alpha = 1f }.start()
}

/** Gentle endless up-and-down. */
fun View.floatLoop(distDp: Float, ms: Long) {
    ObjectAnimator.ofFloat(this, View.TRANSLATION_Y, 0f, -distDp * dp).apply {
        duration = ms; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
        interpolator = AccelerateDecelerateInterpolator(); start()
    }
}

/** Endless blink between full and [low] opacity. Returns the animator so it can be stopped. */
fun View.pulse(low: Float, ms: Long): ObjectAnimator =
    ObjectAnimator.ofFloat(this, View.ALPHA, 1f, low).apply {
        duration = ms; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE; start()
    }

/** Rise from below, used for staggered screen entrances. */
fun View.riseIn(delay: Long, distDp: Float = 36f) {
    alpha = 0f
    translationY = distDp * dp
    anim().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(480)
        .setInterpolator(DecelerateInterpolator(2f)).start()
}

/** One panel slides out, the other slides in from the side ([dir] = +1 right, -1 left). */
fun switchPanel(out: View, into: View, dir: Int) {
    val d = out.dp
    out.anim().alpha(0f).translationX(-dir * 40 * d).setDuration(150)
        .withEndAction {
            out.visibility = View.GONE
            out.translationX = 0f; out.alpha = 1f
            into.visibility = View.VISIBLE
            into.alpha = 0f
            into.translationX = dir * 40 * d
            into.anim().alpha(1f).translationX(0f).setDuration(260)
                .setInterpolator(DecelerateInterpolator(1.5f)).start()
        }.start()
}
