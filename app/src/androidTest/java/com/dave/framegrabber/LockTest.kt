package com.dave.framegrabber

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.dave.framegrabber.data.AppLock
import com.dave.framegrabber.ui.LockActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** «Защита»: with a PIN set, any screen opened while locked is covered by the PIN screen. */
@RunWith(AndroidJUnit4::class)
class LockTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext

    private fun shot(name: String) {
        Thread.sleep(300)
        val bmp = inst.uiAutomation.takeScreenshot() ?: return
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "$name.png")
            put(MediaStore.Downloads.MIME_TYPE, "image/png")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/FrameGrabberShots")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: return
        ctx.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun resumed(): List<android.app.Activity> {
        var out = emptyList<android.app.Activity>()
        inst.runOnMainSync { out = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).toList() }
        return out
    }

    @Test fun pinCoversScreens() {
        try {
            AppLock.setPin(ctx, "1234")
            assertTrue(AppLock.check(ctx, "1234"))
            assertFalse(AppLock.check(ctx, "4321"))
            AppLock.locked = true
            val a = inst.startActivitySync(com.dave.framegrabber.ui.StorageActivity.intent(ctx).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Thread.sleep(2500)
            val lock = resumed().firstOrNull { it is LockActivity }
            assertTrue("PIN screen should be on top", lock != null)
            shot("lock_pin")
            inst.runOnMainSync { AppLock.unlocked(); lock!!.finish() }
            Thread.sleep(1000)
            assertTrue(resumed().none { it is LockActivity })
            a.finish()

            // the «set PIN» screen from the settings
            val set = inst.startActivitySync(LockActivity.intent(ctx, LockActivity.MODE_SET).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Thread.sleep(1200); shot("lock_set")
            set.finish()
        } finally {
            AppLock.disable(ctx)
        }
    }
}
