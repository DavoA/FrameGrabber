package com.dave.framegrabber.ui.main

import com.dave.framegrabber.core.Fmt
import com.dave.framegrabber.core.OutputSpec
import com.dave.framegrabber.ui.toast

/**
 * How frames are saved: size, format, quality and "sharpest frame nearby".
 * Edited in ⚙ settings (section «Качество кадров»), read from the saved settings here.
 */
class CaptureFormat(private val s: MainScreen) {

    /** "Sharpest frame nearby" is on. */
    val sharpest: Boolean get() = s.prefs.sharpest

    /** What to save frames as, or a message + null if the custom size is wrong. */
    fun readSpec(): OutputSpec? {
        val p = s.prefs
        val fmt = when (p.format) { "PNG" -> Fmt.PNG; "WEBP" -> Fmt.WEBP; else -> Fmt.JPG }
        val size = when (p.size) {
            "720" -> 1280 to 720
            "1080" -> 1920 to 1080
            "custom" -> {
                val w = p.customW.toIntOrNull()
                val h = p.customH.toIntOrNull()
                if (w == null || h == null || w !in 16..8192 || h !in 16..8192) {
                    s.act.toast("Свой размер в ⚙ настройках: от 16 до 8192 пикселей"); return null
                }
                w to h
            }
            else -> null
        }
        return OutputSpec(size, fmt, p.quality.coerceIn(1, 100))
    }
}
