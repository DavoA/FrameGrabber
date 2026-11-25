package com.dave.framegrabber

import android.app.Activity
import android.content.Context
import android.view.View

/** Visual theme of the app, chosen in "Общие настройки". Classic green is the default. */
enum class AppTheme(val key: String) {
    CLASSIC("classic"),
    WAVE("wave");

    companion object {
        private const val PREFS = "settings"
        private const val KEY = "theme"

        fun get(ctx: Context): AppTheme {
            val v = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, CLASSIC.key)
            return entries.firstOrNull { it.key == v } ?: CLASSIC
        }

        fun set(ctx: Context, t: AppTheme) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, t.key).apply()
        }

        /** Shows/hides the animated background (with a crossfade when [animate]). */
        fun apply(activity: Activity, bg: SynthwaveView, t: AppTheme, animate: Boolean) {
            val root = bg.parent as View
            if (t == WAVE) {
                bg.running = true
                if (bg.visibility != View.VISIBLE) {
                    bg.visibility = View.VISIBLE
                    if (animate) { bg.alpha = 0f; bg.animate().alpha(1f).setDuration(450).start() } else bg.alpha = 1f
                }
                root.setBackgroundColor(activity.getColor(R.color.bg))
            } else {
                root.setBackgroundColor(activity.getColor(R.color.bgClassic))
                if (bg.visibility == View.VISIBLE) {
                    if (animate) {
                        bg.animate().alpha(0f).setDuration(350).withEndAction {
                            bg.visibility = View.GONE; bg.running = false
                        }.start()
                    } else { bg.visibility = View.GONE; bg.running = false }
                } else bg.running = false
            }
        }
    }
}
