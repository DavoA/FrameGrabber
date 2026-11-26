package com.dave.framegrabber

import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dave.framegrabber.player.ActivityScan
import com.dave.framegrabber.player.PlayerStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Player data: activity curve, hot moments, what's remembered per video. */
@RunWith(AndroidJUnit4::class)
class PlayerTest {
    private val testCtx = InstrumentationRegistry.getInstrumentation().context
    private val appCtx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun activity_curve() {
        val f = File(appCtx.cacheDir, "split_src.mp4")
        testCtx.assets.open("split_src.mp4").use { i -> f.outputStream().use { i.copyTo(it) } }
        val t0 = System.currentTimeMillis()
        val a = ActivityScan.compute(appCtx, Uri.fromFile(f))
        Log.i("FrameGrabber", "ACTIVITY ${System.currentTimeMillis() - t0} ms: " + a.take(20).joinToString { "%.2f".format(it) })
        assertEquals(PlayerStore.BUCKETS, a.size)
        assertTrue(a.all { it in 0f..1f })
        assertTrue("some variation", a.max() - a.min() > 0.3f)
        val hot = ActivityScan.hotMoments(a, 12_000_000)
        assertTrue(hot.isNotEmpty())
        assertTrue(hot.all { it in 600_000..12_000_000 })   // never in the first 5 %
    }

    @Test fun store_roundtrip() {
        val store = PlayerStore(appCtx)
        val uri = Uri.fromFile(File("/sdcard/Movies/x.mp4"))
        val v = store.load(uri)
        v.name = "x.mp4"; v.positionUs = 12_345_000; v.durationUs = 60_000_000
        v.moments += listOf(1_000_000L, 5_000_000L)
        v.heat[3] = 4.5f; v.vr = "vr180"; v.lastWatched = 99
        v.activity = FloatArray(PlayerStore.BUCKETS) { it / 200f }
        store.save(v)
        val again = store.load(uri)
        assertEquals(12_345_000L, again.positionUs)
        assertEquals(listOf(1_000_000L, 5_000_000L), again.moments)
        assertEquals(4.5f, again.heat[3], 0.01f)
        assertEquals("vr180", again.vr)
        assertEquals(0.5f, again.activity!![100], 0.01f)
        assertTrue(store.all().any { it.id == v.id })
        store.setIntroSkip("/sdcard/Movies", 32_000_000)
        assertEquals(32_000_000L, store.introSkipUs("/sdcard/Movies"))
        store.delete(v.id)
    }
}
