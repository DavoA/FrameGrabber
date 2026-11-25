package com.dave.framegrabber

import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dave.framegrabber.core.*
import com.dave.framegrabber.export.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Many containers/codecs (generated with ffmpeg, 640x360, 4 s, 30 fps, with sound):
 * frames from every one (Android's decoder or FFmpeg), right fps, and clips where possible.
 */
@RunWith(AndroidJUnit4::class)
class FormatsProbeTest {
    private val testCtx = InstrumentationRegistry.getInstrumentation().context
    private val appCtx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun log(s: String) = Log.i("FrameGrabber", "PROBE $s")

    private fun asset(name: String): Uri {
        val f = File(appCtx.cacheDir, name)
        testCtx.assets.open(name).use { i -> f.outputStream().use { i.copyTo(it) } }
        return Uri.fromFile(f)
    }

    @Test fun frames_from_every_format() {
        val names = testCtx.assets.list("")!!.filter { it.startsWith("fmt_") }.sorted()
        assertTrue(names.size >= 10)
        val failed = mutableListOf<String>()
        for (name in names) {
            val r = runCatching {
                FrameEngine(appCtx, asset(name)).use { e ->
                    log("$name: ${e.width}x${e.height} dur=${e.durationUs} fps=${e.fps} ffmpeg=${e.viaFfmpeg}")
                    assertEquals("$name width", 640, e.width)
                    assertEquals("$name height", 360, e.height)
                    assertTrue("$name duration ${e.durationUs}", e.durationUs in 3_500_000..4_500_000)
                    assertTrue("$name fps ${e.fps}", e.fps in 29.0..31.0)
                    val g = FrameCapture.grab(e, 2_000_000)
                    assertEquals("$name frame width", 640, g.bitmap.width)
                    assertTrue("$name preview", (e.scaledFrame(1_000_000, 200, 200, false)?.width ?: 0) in 100..200)
                    // a few frames like the batch does
                    val dir = File(appCtx.cacheDir, "fmt_out").apply { deleteRecursively(); mkdirs() }
                    listOf(0L, 1_500_000L, 3_000_000L).forEach { FrameCapture.capture(e, it, OutputSpec(null, Fmt.JPG, 90), dir, "x") }
                    val gif = File(appCtx.cacheDir, "$name.gif")
                    Exporters.gif(appCtx, asset(name), 0, 1_000_000, GifOptions(10, 320, GifEncoder.Quality.NORMAL), gif) { true }
                    assertTrue("$name gif", gif.length() > 1000)
                    log("$name frames OK (${g.method})")
                }
            }
            r.onFailure { log("$name FAIL $it"); failed += "$name: ${it.message}" }
        }
        assertTrue("failed:\n" + failed.joinToString("\n"), failed.isEmpty())
    }

    @Test fun clips_where_possible() {
        fun clip(name: String): Pair<Exporters.ClipFormat, Exporters.ClipResult> {
            val uri = asset(name)
            val fmt = Exporters.clipFormat(appCtx, uri)
            val out = File(appCtx.cacheDir, "$name.clip.${fmt.ext}")
            val res = Exporters.clip(appCtx, uri, 1_000_000, 3_000_000, out) { true }
            assertTrue("$name clip size", out.length() > 10_000)
            FrameEngine(appCtx, Uri.fromFile(out)).use { e -> assertTrue("$name clip dur ${e.durationUs}", e.durationUs in 1_500_000..3_200_000) }
            log("$name clip ${fmt.ext} dropped=${res.droppedAudio}")
            return fmt to res
        }
        clip("fmt_h264_aac.mov").let { (f, r) -> assertEquals("mp4", f.ext); assertTrue(r.droppedAudio.isEmpty()) }
        clip("fmt_hevc_aac.mkv").let { (f, r) -> assertEquals("mp4", f.ext); assertTrue(r.droppedAudio.isEmpty()) }
        clip("fmt_h264_mp3.mkv").let { (f, _) -> assertEquals("mp4", f.ext) }
        clip("fmt_h264_mp2.ts").let { (f, _) -> assertEquals("mp4", f.ext) }
        clip("fmt_vp9_opus.webm").let { (f, r) -> assertEquals("webm", f.ext); assertTrue(r.droppedAudio.isEmpty()) }
        clip("fmt_vp8_vorbis.webm").let { (f, r) -> assertEquals("webm", f.ext); assertTrue(r.droppedAudio.isEmpty()) }
        // containers Android can't read: a clear message instead of a crash
        for (n in listOf("fmt_xvid_mp3.avi", "fmt_h264_aac.flv", "fmt_wmv2.wmv", "fmt_mpeg2.mpg")) {
            val e = runCatching { Exporters.clipFormat(appCtx, asset(n)) }.exceptionOrNull()
            assertTrue("$n should explain", e?.message?.contains("GIF и кадры работают") == true)
        }
    }
}
