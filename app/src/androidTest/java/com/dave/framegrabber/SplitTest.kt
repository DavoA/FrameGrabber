package com.dave.framegrabber

import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dave.framegrabber.core.FrameEngine
import com.dave.framegrabber.export.Exporters
import com.dave.framegrabber.split.SplitPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/** Splitting by size: the plan's sizes match the real parts, nothing lost or doubled. */
@RunWith(AndroidJUnit4::class)
class SplitTest {
    private val testCtx = InstrumentationRegistry.getInstrumentation().context
    private val appCtx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun plan_and_cut() {
        val src = File(appCtx.cacheDir, "split_src.mp4")
        testCtx.assets.open("split_src.mp4").use { i -> src.outputStream().use { i.copyTo(it) } }
        val uri = Uri.fromFile(src)
        val scan = SplitPlan.scan(appCtx, uri, src.length()) { true }
        assertTrue("keyframes ${scan.keys.size}", scan.keys.size in 12..14)
        assertTrue("media ${scan.mediaBytes} of ${src.length()}", scan.mediaBytes in src.length() * 9 / 10..src.length())

        // a limit that needs ~3 parts
        val limit = src.length() / 3 + src.length() / 10
        val plan = SplitPlan.auto(scan, limit)
        Log.i("FrameGrabber", "SPLIT plan: " + plan.parts.joinToString { "${it.fromUs}-${it.toUs}:${it.bytes}" })
        assertTrue("parts ${plan.parts.size} " + plan.parts.joinToString { "${it.bytes}" }, plan.parts.size in 3..4)
        val desc = "limit $limit media ${scan.mediaBytes} keys ${scan.keys.size}: " + plan.parts.joinToString { "${it.fromUs / 1000}-${it.toUs / 1000}ms ${it.bytes}" }
        assertTrue(desc, plan.parts.all { it.bytes <= limit })
        // contiguous, from 0 to the end
        assertEquals(desc, 0L, plan.parts.first().fromUs)
        plan.parts.zipWithNext().forEach { (a, b) -> assertEquals(a.toUs, b.fromUs) }
        // about equal
        val sizes = plan.parts.map { it.bytes }
        assertTrue("sizes $sizes", sizes.max() - sizes.min() < src.length() / 6)

        // cut for real (same code path as the service) and compare
        var totalDur = 0L
        plan.parts.forEachIndexed { i, p ->
            val out = File(appCtx.cacheDir, "part$i.mp4")
            Exporters.clip(appCtx, uri, p.startAbs, p.endAbs - 1, out, absolute = true) { true }
            val real = out.length()
            Log.i("FrameGrabber", "SPLIT part ${i + 1}: planned ${p.bytes} real $real")
            assertTrue("part ${i + 1}: planned ${p.bytes}, real $real", abs(real - p.bytes) < p.bytes / 50 + 70_000)
            assertTrue("part ${i + 1} over the limit", real <= limit)
            FrameEngine(appCtx, Uri.fromFile(out)).use { e -> totalDur += e.durationUs }
        }
        assertTrue("total duration $totalDur", abs(totalDur - 12_000_000) < 300_000)

        // more parts by hand
        val five = SplitPlan.plan(scan, 5, limit)
        assertEquals("five: " + five.parts.joinToString { "${it.fromUs / 1000}" }, 5, five.parts.size)
        // can't cut finer than the keyframes
        val fine = SplitPlan.plan(scan, 30, limit).parts.size
        assertTrue("30 -> $fine", fine <= scan.keys.size)
    }
}
