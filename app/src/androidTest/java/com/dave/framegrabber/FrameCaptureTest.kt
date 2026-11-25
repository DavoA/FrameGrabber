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

    @Test fun sharpness_detects_blur() {
        FrameEngine(appCtx, clip("h264.mp4")).use { e ->
            val sharp = e.frame(1_000_000)!!
            val small = android.graphics.Bitmap.createScaledBitmap(sharp, sharp.width / 8, sharp.height / 8, true)
            val blurred = android.graphics.Bitmap.createScaledBitmap(small, sharp.width, sharp.height, true)
            val a = ImageMetrics.sharpness(ImageMetrics.small(sharp))
            val b = ImageMetrics.sharpness(ImageMetrics.small(blurred))
            assertTrue("sharp $a > blurred $b", a > b * 2)
            // same frame -> identical hash, different moments -> different hash
            assertEquals(0, ImageMetrics.hamming(ImageMetrics.dHash(sharp), ImageMetrics.dHash(sharp)))
            val t = FrameCapture.sharpestNear(e, 2_000_000)
            assertTrue(t in 1_500_000..2_500_000)
        }
    }

    @Test fun gif_clip_sheet_export() {
        val uri = clip("h264.mp4")
        val gif = File(appCtx.cacheDir, "t.gif")
        Exporters.gif(appCtx, uri, 500_000, 2_000_000, 10, 320, gif) { true }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(gif.path, o)
        assertEquals(320, o.outWidth)
        assertEquals(180, o.outHeight)
        // all frames decode (AnimatedImageDrawable walks the whole file)
        val d = android.graphics.ImageDecoder.decodeDrawable(android.graphics.ImageDecoder.createSource(gif))
        assertTrue(d is android.graphics.drawable.AnimatedImageDrawable)

        val mp4 = File(appCtx.cacheDir, "t.mp4")
        Exporters.clip(appCtx, uri, 1_000_000, 3_000_000, mp4) { true }
        val r = android.media.MediaMetadataRetriever()
        r.setDataSource(mp4.path)
        val dur = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
        r.release()
        assertTrue("clip duration $dur", dur in 1500..3200)

        FrameEngine(appCtx, uri).use { e ->
            val dir = outDir("sheet")
            val caps = (0 until 5).map { k ->
                val s = FrameCapture.capture(e, k * 700_000L, OutputSpec(null, Fmt.JPG, 80), dir, "s")
                Capture(s.file, s.thumb, k * 700_000L, Fmt.JPG, s.width, s.height, s.exact)
            }
            val sheet = ContactSheet.build(caps, "test", "5 кадров")
            assertTrue(sheet.width > 600 && sheet.height > 300)
        }
    }

    @Test fun ocr_reads_latin_and_cyrillic() {
        val ok = runCatching { Ocr.download(appCtx, listOf("eng", "rus")) { _, _ -> } }.isSuccess
        assumeTrue("no network for language files", ok)
        val b = android.graphics.Bitmap.createBitmap(900, 260, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b)
        c.drawColor(android.graphics.Color.WHITE)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 64f; color = android.graphics.Color.BLACK }
        c.drawText("HELLO FRAMES 2026", 30f, 100f, p)
        c.drawText("Привет мир", 30f, 200f, p)
        val text = Ocr.recognize(appCtx, b, listOf("eng", "rus"))
        Log.i("FrameCaptureTest", "OCR: $text")
        assertTrue(text, text.contains("HELLO"))
        assertTrue(text, text.contains("Привет"))
    }

    /** The real app path: write GIF / clip / sheet straight into the gallery (MediaStore), no temp files. */
    @Test fun export_directly_to_gallery() {
        val uri = clip("h264.mp4")
        val gifUri = Output.write(appCtx, "test_direct.gif", "image/gif", Output.Kind.IMAGE) { t ->
            t.stream().buffered().use { os -> Exporters.gif(appCtx, uri, 0, 1_000_000, 10, 320, os) { true } }
        }
        appCtx.contentResolver.openInputStream(gifUri)!!.use { i ->
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(i, null, o)
            assertEquals(320, o.outWidth)
        }
        val clipUri = Output.write(appCtx, "test_direct.mp4", "video/mp4", Output.Kind.VIDEO) { t ->
            t.fd()!!.use { fd ->
                Exporters.clip(appCtx, uri, 1_000_000, 3_000_000,
                    { android.media.MediaMuxer(fd.fileDescriptor, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4) }) { true }
            }
        }
        val r = android.media.MediaMetadataRetriever()
        r.setDataSource(appCtx, clipUri)
        val dur = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
        r.release()
        assertTrue("clip duration $dur", dur in 1500..3200)
        appCtx.contentResolver.delete(gifUri, null, null)
        appCtx.contentResolver.delete(clipUri, null, null)
    }

    @Test fun naming_pattern() {
        assertEquals("clip_00-01-23.500", Naming.stem("{video}_{time}", "clip", 83_500_000L, 1))
        assertEquals("a_007", Naming.stem("a_{n}", "x", 0, 7))
        assertEquals("a_b", Naming.stem("a/b", "x", 0, 1))
    }

    @Test fun time_parse_format() {
        assertEquals(12_500_000L, TimeUtil.parse("12.5"))
        assertEquals(83_500_000L, TimeUtil.parse("1:23,5"))
        assertEquals(3_723_000_000L, TimeUtil.parse("1:02:03"))
        assertEquals(null, TimeUtil.parse("abc"))
        assertEquals("1:23.500", TimeUtil.format(83_500_000L))
    }
}
