package com.dave.framegrabber

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dave.framegrabber.site.SiteStore
import com.dave.framegrabber.site.WatchJob
import com.dave.framegrabber.ui.site.SavedActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Following a model: store, daily job, the «Сохранённое» screen. */
@RunWith(AndroidJUnit4::class)
class FollowTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext

    @Test fun follow() {
        val st = SiteStore(ctx)
        if (st.isFollowed("Test Model")) st.toggleFollow("Test Model")
        assertTrue(st.toggleFollow("Test Model"))
        WatchJob.schedule(ctx)
        val a = inst.startActivitySync(SavedActivity.intent(ctx, models = true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SavedActivity
        inst.runOnMainSync { com.dave.framegrabber.ui.site.follow(a, "Other Model") }
        Thread.sleep(1500)
        inst.runOnMainSync { a.recreate() }
        Thread.sleep(1500)
        a.finish()
        st.toggleFollow("Test Model"); st.toggleFollow("Other Model")
        WatchJob.schedule(ctx)
    }

    /** «Место на телефоне» opens and lists its groups without crashing. */
    @Test fun storagePage() {
        val a = inst.startActivitySync(com.dave.framegrabber.ui.StorageActivity.intent(ctx).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Thread.sleep(4000)
        a.finish()
    }

}
