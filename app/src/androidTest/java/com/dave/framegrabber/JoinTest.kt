package com.dave.framegrabber

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dave.framegrabber.core.FrameEngine
import com.dave.framegrabber.data.Backup
import com.dave.framegrabber.export.Exporters
import com.dave.framegrabber.split.SplitPlan
import com.dave.framegrabber.split.VideoJoin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/** Parts cut by the splitter join back into the same video (length, size); settings backup round trip. */
@RunWith(AndroidJUnit4::class)
class JoinTest {
    private val testCtx = InstrumentationRegistry.getInstrumentation().context
    private val appCtx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun split_then_join() {
        val src = File(appCtx.cacheDir, "join_src.mp4")
        testCtx.assets.open("split_src.mp4").use { i -> src.outputStream().use { i.copyTo(it) } }
        val uri = Uri.fromFile(src)
        val scan = SplitPlan.scan(appCtx, uri, src.length()) { true }
        val plan = SplitPlan.plan(scan, 3, src.length())
        val parts = plan.parts.mapIndexed { i, p ->
            File(appCtx.cacheDir, "jpart$i.mp4").also { Exporters.clip(appCtx, uri, p.startAbs, p.endAbs - 1, it, absolute = true) { true } }
        }
        assertEquals(3, parts.size)
        var last = 0f
        val out = VideoJoin.join(appCtx, parts.map { Uri.fromFile(it) }, "joined_test") { last = it }
        assertTrue("progress $last", last > 0.95f)
        val dur = FrameEngine(appCtx, out).use { it.durationUs }
        assertTrue("joined duration $dur", abs(dur - 12_000_000) < 400_000)
        val size = appCtx.contentResolver.openAssetFileDescriptor(out, "r")!!.use { it.length }
        assertTrue("joined size $size vs ${src.length()}", abs(size - src.length()) < src.length() / 10)
        appCtx.contentResolver.delete(out, null, null)
    }

    @Test fun backup_round_trip() {
        val sp = appCtx.getSharedPreferences("telegram", 0)
        sp.edit().putString("videoCaption", "Test +18").putInt("apiId", 777).commit()
        val file = Backup.export(appCtx)
        sp.edit().putString("videoCaption", "changed").putInt("apiId", 1).commit()
        val n = Backup.restore(appCtx, file)
        assertTrue("restored $n", n > 0)
        assertEquals("Test +18", sp.getString("videoCaption", null))
        assertEquals(777, sp.getInt("apiId", 0))
        sp.edit().remove("videoCaption").putInt("apiId", 0).commit()
        appCtx.contentResolver.delete(file, null, null)
    }
}
