package com.dave.framegrabber.ui.main

import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import com.dave.framegrabber.R
import com.dave.framegrabber.core.FrameCapture
import com.dave.framegrabber.core.FrameEngine
import com.dave.framegrabber.core.OutputSpec
import com.dave.framegrabber.core.TimeUtil
import com.dave.framegrabber.data.Capture
import com.dave.framegrabber.data.CaptureStore
import com.dave.framegrabber.data.Naming
import com.dave.framegrabber.ui.anim
import com.dave.framegrabber.ui.showError
import com.dave.framegrabber.ui.toast

/** The 📷 button: takes the current frame (optionally the sharpest one nearby). */
class Shutter(private val s: MainScreen) {

    val button = s.pinned<View>(R.id.btnCapture)

    init {
        button.setOnClickListener { capture() }
    }

    private fun capture() {
        val e = s.video.requireEngine() ?: return
        val spec = s.format.readSpec() ?: return
        s.video.pausePlayback()
        val us = s.video.currentUs
        val sharpest = s.format.sharpest
        val index = CaptureStore.captures.size + 1
        if (sharpest) {   // looking at neighbours takes a moment: spin the shutter meanwhile
            button.anim().rotationBy(360f).setDuration(900).setInterpolator(AccelerateDecelerateInterpolator()).start()
        }
        s.exec.execute {
            val res = runCatching { make(e, us, spec, sharpest, index) }
            s.ui.post {
                res.onSuccess { c ->
                    s.effects.shutterFlash()
                    if (c.sharpened) s.act.toast("Взят более резкий кадр: ${TimeUtil.format(c.timeUs)}")
                    s.effects.flyToCaptures(c.thumb) { CaptureStore.add(c) }
                }.onFailure { s.act.showError("Не получилось снять кадр", it) }
            }
        }
    }

    /** A frame at each of [times] (the marks), one after another. */
    fun captureMany(times: List<Long>) {
        val e = s.video.requireEngine() ?: return
        val spec = s.format.readSpec() ?: return
        s.video.pausePlayback()
        val sharpest = s.format.sharpest
        val start = CaptureStore.captures.size
        s.act.toast("Снимаю ${times.size} кадров по меткам…")
        button.anim().rotationBy(360f * times.size.coerceAtMost(4)).setDuration(600L * times.size.coerceAtMost(4))
            .setInterpolator(AccelerateDecelerateInterpolator()).start()
        s.exec.execute {
            var ok = 0
            var error: Throwable? = null
            times.forEachIndexed { i, us ->
                if (s.video.engine !== e) return@execute   // another video opened meanwhile
                runCatching { make(e, us, spec, sharpest, start + i + 1) }
                    .onSuccess { c -> ok++; s.ui.post { CaptureStore.add(c) } }
                    .onFailure { if (error == null) error = it }
            }
            s.ui.post {
                s.effects.shutterFlash()
                if (error != null && ok == 0) s.act.showError("Не получилось снять кадры", error!!)
                else s.act.toast("Снято кадров по меткам: $ok" + if (ok < times.size) " из ${times.size}" else "")
            }
        }
    }

    /** Runs on the engine thread. */
    private fun make(e: FrameEngine, us0: Long, spec: OutputSpec, sharpest: Boolean, index: Int): Capture {
        val us = if (sharpest) FrameCapture.sharpestNear(e, us0) else us0
        val name = s.video.baseName
        val stem = Naming.stem(s.prefs.namePattern, name, us, index)
        val shot = FrameCapture.capture(e, us, spec, s.capDir, name, stem)
        return Capture(shot.file, shot.thumb, us, spec.format, shot.width, shot.height, shot.exact, sharpened = us != us0)
    }
}
