package com.dave.framegrabber

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dave.framegrabber.core.*
import com.dave.framegrabber.export.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exploratory: what works for each container/codec on this Android. Logs only (tag FrameGrabber). */
@RunWith(AndroidJUnit4::class)
class FormatsProbeTest {
    private val testCtx = InstrumentationRegistry.getInstrumentation().context
    private val appCtx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun log(s: String) = Log.i("FrameGrabber", "PROBE $s")

    @Test fun probe() {
        val names = testCtx.assets.list("")!!.filter { it.startsWith("fmt_") }.sorted()
        for (name in names) {
            val f = File(appCtx.cacheDir, name)
            testCtx.assets.open(name).use { i -> f.outputStream().use { i.copyTo(it) } }
            val uri = Uri.fromFile(f)
            val ex = runCatching {
                val x = MediaExtractor(); x.setDataSource(f.path)
                val tracks = (0 until x.trackCount).map { i -> val fm = x.getTrackFormat(i)
                    fm.getString(MediaFormat.KEY_MIME) + (if (fm.containsKey(MediaFormat.KEY_FRAME_RATE)) "@" + fm.getInteger(MediaFormat.KEY_FRAME_RATE) else "") }
                x.release(); tracks.joinToString(",")
            }
            log("$name extractor: ${ex.getOrElse { "FAIL " + it }}")
            val e = runCatching { FrameEngine(appCtx, uri) }.getOrElse { log("$name engine: FAIL $it"); continue }
            e.use {
                log("$name engine: ${e.width}x${e.height} dur=${e.durationUs} fps=${e.fps} frames=${e.frameCount}")
                log("$name grab2s: " + runCatching { FrameCapture.grab(e, 2_000_000).let { "${it.first.width} exact=${it.second}" } }.getOrElse { "FAIL $it" })
                log("$name scaled: " + runCatching { e.scaledFrame(1_000_000, 200, 200, false)?.width }.getOrElse { "FAIL $it" })
                log("$name framesAt: " + runCatching { if (e.frameCount > 0) e.framesAt(10, 3).size else "no count" }.getOrElse { "FAIL $it" })
            }
            log("$name clip: " + runCatching {
                val out = File(appCtx.cacheDir, "$name.clip.mp4")
                Exporters.clip(appCtx, uri, 1_000_000, 3_000_000, out) { true }; out.length()
            }.getOrElse { "FAIL $it" })
            log("$name gif: " + runCatching {
                val out = File(appCtx.cacheDir, "$name.gif")
                Exporters.gif(appCtx, uri, 0, 1_000_000, GifOptions(10, 320, GifEncoder.Quality.NORMAL), out) { true }; out.length()
            }.getOrElse { "FAIL $it" })
        }
    }
}
