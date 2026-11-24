package com.dave.framegrabber

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.os.StrictMode
import android.provider.MediaStore
import android.view.ViewGroup
import android.widget.ListView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Not a real check: takes screenshots of the main states so the design can be reviewed from CI artifacts. */
@RunWith(AndroidJUnit4::class)
class ScreenshotTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val appCtx = inst.targetContext

    private fun shot(name: String) {
        Thread.sleep(300)
        val bmp = inst.uiAutomation.takeScreenshot() ?: return
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "$name.png")
            put(MediaStore.Downloads.MIME_TYPE, "image/png")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/FrameGrabberShots")
        }
        val uri = appCtx.contentResolver.insert(
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: return
        appCtx.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun launch(intent: Intent): Activity {
        intent.setClassName(appCtx, MainActivity::class.java.name)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return inst.startActivitySync(intent)
    }

    @Test fun screens() {
        StrictMode.setVmPolicy(StrictMode.VmPolicy.LAX)
        // 1. empty state
        val a0 = launch(Intent(Intent.ACTION_MAIN))
        Thread.sleep(2500); shot("1_empty")
        a0.finish(); Thread.sleep(800)

        // 2. video loaded
        val f = File(appCtx.cacheDir, "h264.mp4")
        inst.context.assets.open("h264.mp4").use { i -> f.outputStream().use { i.copyTo(it) } }
        val a = launch(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(f), "video/mp4"))
        Thread.sleep(3500); shot("2_loaded")

        // 3. capture a few frames
        repeat(4) { k ->
            inst.runOnMainSync {
                a.findViewById<android.widget.SeekBar>(R.id.seek).let { it.progress = it.max * (k + 1) / 5 }
            }
            inst.runOnMainSync { a.findViewById<android.view.View>(R.id.btnCapture).performClick() }
            Thread.sleep(1200)
        }
        Thread.sleep(800); shot("3_captured")

        // 4. scroll down to the grid
        inst.runOnMainSync { a.findViewById<ListView>(R.id.list).setSelection(1) }
        Thread.sleep(1200); shot("4_grid")

        // 5. open a capture in the big preview
        inst.runOnMainSync {
            val list = a.findViewById<ListView>(R.id.list)
            for (i in 0 until list.childCount) {
                val row = list.getChildAt(i)
                if (row is android.widget.LinearLayout && row.orientation == android.widget.LinearLayout.HORIZONTAL && row.childCount == 3) {
                    (row.getChildAt(1) as ViewGroup).performClick(); break
                }
            }
        }
        Thread.sleep(1500); shot("5_viewer")

        // 6. settings tab
        inst.runOnMainSync {
            a.findViewById<ListView>(R.id.list).setSelection(0)
            a.findViewById<android.widget.RadioButton>(R.id.tabSettings).isChecked = true
        }
        Thread.sleep(1500); shot("6_settings")
        a.finish()
    }
}
