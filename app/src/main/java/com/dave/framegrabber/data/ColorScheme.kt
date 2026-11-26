package com.dave.framegrabber.data

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import com.dave.framegrabber.R

/**
 * Color scheme (Общие настройки → Цвет): the green original or the same look in pink, orange,
 * red, purple on black. Laid over every screen's theme right before it is created.
 */
enum class ColorScheme(val key: String, val title: String, val style: Int, val swatch: Int) {
    GREEN("green", "Зелёная", R.style.Scheme_Green, 0xFF2FC47F.toInt()),
    PINK("pink", "Розовая", R.style.Scheme_Pink, 0xFFC42F7A.toInt()),
    ORANGE("orange", "Оранжевая", R.style.Scheme_Orange, 0xFFC4692F.toInt()),
    RED("red", "Красная", R.style.Scheme_Red, 0xFFC42F45.toInt()),
    PURPLE("purple", "Фиолетовая", R.style.Scheme_Purple, 0xFF8E2FC4.toInt());

    companion object {
        private const val PREFS = "settings"
        private const val KEY = "scheme"

        fun get(ctx: Context): ColorScheme {
            val v = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, GREEN.key)
            return entries.firstOrNull { it.key == v } ?: GREEN
        }

        fun set(ctx: Context, s: ColorScheme) =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, s.key).apply()

        /** Call once from Application.onCreate. */
        fun install(app: Application) {
            app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityPreCreated(a: Activity, b: Bundle?) { a.theme.applyStyle(get(a).style, true) }
                override fun onActivityCreated(a: Activity, b: Bundle?) {}
                override fun onActivityStarted(a: Activity) {}
                override fun onActivityResumed(a: Activity) {}
                override fun onActivityPaused(a: Activity) {}
                override fun onActivityStopped(a: Activity) {}
                override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
                override fun onActivityDestroyed(a: Activity) {}
            })
        }
    }
}
