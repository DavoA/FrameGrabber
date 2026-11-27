package com.dave.framegrabber

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dave.framegrabber.site.Lab
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Live: a fastpic «view» page → the real big picture (skipped when fastpic can't be reached). */
@RunWith(AndroidJUnit4::class)
class FastpicTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun bigPicture() {
        val page = "https://fastpic.org/view/129/2026/1126/_ec448b111e5831113f9f6772b6e2b640.jpg.html"
        assumeTrue("no network", Lab.fetchText(ctx, page) != null)
        val img = Lab.Img("https://i129.fastpic.org/thumb/2026/1126/40/_ec448b111e5831113f9f6772b6e2b640.jpeg",
            "https://i129.fastpic.org/thumb/2026/1126/40/_ec448b111e5831113f9f6772b6e2b640.jpeg", page)
        val (big, ref) = Lab.bigOf(ctx, img)
        Log.i("FrameGrabber", "FASTPIC big=$big ref=$ref log=${Lab.bigLog.joinToString(" | ")}")
        assertTrue("big: $big", "/big/" in big && "_ec448b111e5831113f9f6772b6e2b640" in big)
        val b = Lab.image(ctx, big, 4000, ref)
        assertTrue("loaded ${b?.width}x${b?.height}", b != null && b.width > 400)
    }
}
