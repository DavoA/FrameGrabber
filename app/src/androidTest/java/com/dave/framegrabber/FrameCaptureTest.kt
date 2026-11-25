package com.dave.framegrabber

import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs on an Android emulator in GitHub Actions.
 * Test clips (generated with ffmpeg, 1280x720, 4 s, 30 fps) live in androidTest/assets.
 */
@RunWith(AndroidJUnit4::class)
class FrameCaptureTest {
    private val testCtx = InstrumentationRegistry.getInstrumentation().context
    private val appCtx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun clip(name: String): Uri {
        val f = File(appCtx.cacheDir, name)
        testCtx.assets.open(name).use { i -> f.outputStream().use { i.copyTo(it) } }
        return Uri.fromFile(f)
    }

    private fun outDir(tag: String) = File(appCtx.cacheDir, "out_$tag").apply { deleteRecursively(); mkdirs() }

    private fun checkFile(f: File, w: Int? = null, h: Int? = null) {
        assertTrue("file exists: $f", f.exists() && f.length() > 0)
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        assertTrue("decodable: $f", o.outWidth > 0 && o.outHeight > 0)
        if (w != null) assertEquals(w, o.outWidth)
        if (h != null) assertEquals(h, o.outHeight)
    }

    /** Same loop the batch mode runs: every 0.5 s across the clip, all formats. */
    private fun batch(name: String, mayBeUnsupported: Boolean = false) {
        FrameEngine(appCtx, clip(name)).use { e ->
            if (mayBeUnsupported) {
                // The emulator has no 10-bit HEVC decoder; real phones that shoot HDR do.
                assumeTrue("no decoder for $name here", runCatching { FrameCapture.grab(e, 0) }.isSuccess)
            }
            Log.i("FrameCaptureTest", "$name: ${e.width}x${e.height} ${e.fps} fps ${e.durationUs} us")
            assertTrue("duration", e.durationUs > 3_000_000)
            for (fmt in Fmt.values()) {
                val dir = outDir("${name}_$fmt")
                val spec = OutputSpec(null, fmt, 90)
                var t = 0L
                var n = 0
                while (t <= e.lastUs) {
                    val shot = FrameCapture.capture(e, t, spec, dir, "clip")
                    checkFile(shot.file, e.width, e.height)
                    assertEquals("saved at full resolution", e.width, shot.width)
                    assertNotNull(shot.thumb)
                    t += 500_000; n++
                }
                assertTrue(n >= 7)
                assertEquals(n, dir.listFiles()!!.size)
            }
        }
    }

    @Test fun h264_batch() = batch("h264.mp4")
    @Test fun h264_rotated_batch() = batch("h264_rot.mp4")
    @Test fun hevc_batch() = batch("hevc.mp4")
    @Test fun hevc10_hdr_batch() = batch("hevc10_hlg.mp4", mayBeUnsupported = true)

    @Test fun resize_and_last_frame() {
        FrameEngine(appCtx, clip("h264.mp4")).use { e ->
            val dir = outDir("resize")
            checkFile(FrameCapture.capture(e, e.lastUs, OutputSpec(1920 to 1080, Fmt.JPG, 92), dir, "a").file, 1920, 1080)
            checkFile(FrameCapture.capture(e, e.durationUs + 5_000_000, OutputSpec(640 to 640, Fmt.PNG, 100), dir, "a").file, 640, 640)
            // Same timestamp twice -> two different files
            FrameCapture.capture(e, 1_000_000, OutputSpec(null, Fmt.WEBP, 80), dir, "dup")
            FrameCapture.capture(e, 1_000_000, OutputSpec(null, Fmt.WEBP, 80), dir, "dup")
            assertEquals(2, dir.listFiles()!!.count { it.name.startsWith("dup") })
        }
    }

    @Test fun time_parse_format() {
        assertEquals(12_500_000L, TimeUtil.parse("12.5"))
        assertEquals(83_500_000L, TimeUtil.parse("1:23,5"))
        assertEquals(3_723_000_000L, TimeUtil.parse("1:02:03"))
        assertEquals(null, TimeUtil.parse("abc"))
        assertEquals("1:23.500", TimeUtil.format(83_500_000L))
    }
}
