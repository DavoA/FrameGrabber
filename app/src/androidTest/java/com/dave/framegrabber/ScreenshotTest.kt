package com.dave.framegrabber

import com.dave.framegrabber.batch.*
import com.dave.framegrabber.core.*
import com.dave.framegrabber.data.*
import com.dave.framegrabber.export.*
import com.dave.framegrabber.ocr.*
import com.dave.framegrabber.ui.browser.FileBrowserActivity
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
        if (intent.component == null) intent.setClassName(appCtx, MainActivity::class.java.name)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return inst.startActivitySync(intent)
    }

    @Test fun screens() {
        StrictMode.setVmPolicy(StrictMode.VmPolicy.LAX)
        AppTheme.set(appCtx, AppTheme.CLASSIC)
        // 1. empty state
        val a0 = launch(Intent(Intent.ACTION_MAIN))
        Thread.sleep(2500); shot("1_empty")
        a0.finish(); Thread.sleep(800)

        // 2. video loaded
        val f = File(appCtx.cacheDir, "h264.mp4")
        inst.context.assets.open("h264.mp4").use { i -> f.outputStream().use { i.copyTo(it) } }
        val a = launch(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(f), "video/mp4"))
        Thread.sleep(3500); shot("2_loaded")

        // 3. capture a few frames (jump to a moment, press 📷)
        val main = a as MainActivity
        repeat(4) { k ->
            inst.runOnMainSync { main.screen.video.seekTo(k * 1_000_000L + k * 200_000L) }
            Thread.sleep(600)
            inst.runOnMainSync { a.findViewById<android.view.View>(R.id.btnCapture).performClick() }
            Thread.sleep(2500)
        }
        Thread.sleep(800); shot("3_captured")

        // 4. «Кадры» page
        inst.runOnMainSync { a.findViewById<android.view.View>(R.id.navFrames).performClick() }
        Thread.sleep(1500); shot("4_grid")

        // 5. open a frame full screen
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
        inst.runOnMainSync { main.screen.viewer.close() }
        Thread.sleep(800)

        // 6. «Инструменты» page
        inst.runOnMainSync { a.findViewById<android.view.View>(R.id.navTools).performClick() }
        Thread.sleep(1500); shot("6_tools")
        inst.runOnMainSync { a.findViewById<android.view.View>(R.id.navCapture).performClick() }
        Thread.sleep(800)

        // general settings dialog, switch to the wave theme
        inst.runOnMainSync { a.findViewById<android.view.View>(R.id.btnSettings).performClick() }
        Thread.sleep(3000); shot("9_general_settings")
        // control: a plain AlertDialog with the same theme
        inst.runOnMainSync { a.window.decorView.rootView.let { } }
        inst.runOnMainSync {
            android.app.AlertDialog.Builder(a).setTitle("Контроль").setMessage("Обычный диалог").setPositiveButton("OK", null).show()
        }
        Thread.sleep(2000); shot("10_plain_dialog")
        AppTheme.set(appCtx, AppTheme.WAVE)
        a.finish(); Thread.sleep(800)

        // 7-8. file browser: folders, then search
        inst.uiAutomation.grantRuntimePermission(appCtx.packageName, android.Manifest.permission.READ_MEDIA_VIDEO)
        val v = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "synthwave_clip.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/Test")
        }
        appCtx.contentResolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v)?.let { uri ->
            appCtx.contentResolver.openOutputStream(uri)?.use { o -> f.inputStream().use { it.copyTo(o) } }
        }
        Thread.sleep(1500)
        val b = launch(Intent().setClassName(appCtx, FileBrowserActivity::class.java.name))
        Thread.sleep(3000); shot("7_browser")
        inst.runOnMainSync { b.findViewById<android.widget.EditText>(R.id.editSearch).setText("synth") }
        Thread.sleep(3000); shot("8_search")
        b.finish()
    }
}
