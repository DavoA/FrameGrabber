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

        // 3b. marks on the timeline
        listOf(500_000L, 2_500_000L, 3_200_000L).forEach { t ->
            inst.runOnMainSync { main.screen.video.seekTo(t) }
            Thread.sleep(400)
            inst.runOnMainSync { a.findViewById<android.view.View>(R.id.btnMark).performClick() }
            Thread.sleep(500)
        }
        Thread.sleep(800); shot("3b_marks")

        // project history: open another video, then come back — frames and marks are restored
        val f2 = File(appCtx.cacheDir, "h264_rot.mp4")
        inst.context.assets.open("h264_rot.mp4").use { i -> f2.outputStream().use { i.copyTo(it) } }
        inst.runOnMainSync { main.screen.video.load(Uri.fromFile(f2)) }
        Thread.sleep(3000)
        inst.runOnMainSync {
            check(CaptureStore.captures.isEmpty()) { "other video should start empty" }
            check(main.screen.marks.marks.isEmpty())
            main.screen.video.load(Uri.fromFile(f))
        }
        Thread.sleep(4000)
        inst.runOnMainSync {
            check(CaptureStore.captures.size == 4) { "restored frames: ${CaptureStore.captures.size}" }
            check(main.screen.marks.marks.size == 3) { "restored marks: ${main.screen.marks.marks}" }
        }
        inst.runOnMainSync { a.findViewById<android.view.View>(R.id.btnHistory).performClick() }
        Thread.sleep(2500); shot("3c_history")
        inst.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        Thread.sleep(1000)

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

        // 6a. «Клипы» page (make a GIF so «Готовые» has something)
        inst.runOnMainSync { a.findViewById<android.view.View>(R.id.navClips).performClick() }
        Thread.sleep(800)
        inst.runOnMainSync { a.findViewById<android.view.View>(R.id.btnGif).performClick() }
        Thread.sleep(6000)
        inst.runOnMainSync {   // close the "GIF готов" dialog
            (a.window.decorView.rootView as? ViewGroup)?.let { }
        }
        inst.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        Thread.sleep(1200); shot("6a_clips")

        // 6. «Инструменты» page
        inst.runOnMainSync { a.findViewById<android.view.View>(R.id.navTools).performClick() }
        Thread.sleep(1500); shot("6_tools")

        // 6b. several videos at once (background service), saved to their projects + gallery
        val job = BatchJob(
            listOf(BatchVideo(Uri.fromFile(f), "h264", null), BatchVideo(Uri.fromFile(f2), "h264_rot", null)),
            BatchRule(byCount = true, value = 3.0), OutputSpec(null, Fmt.JPG, 90), Prefs(appCtx).namePattern,
            skipBlurry = false, skipDupes = false, sharpest = false, toGallery = true
        )
        inst.runOnMainSync { BatchState.start(a, job) }
        val t0 = System.currentTimeMillis()
        while (BatchState.running && System.currentTimeMillis() - t0 < 90_000) Thread.sleep(500)
        Thread.sleep(1500); shot("6b_multi_batch")
        check(!BatchState.running) { "multi batch didn't finish" }
        check(BatchState.done == 6) { "multi batch: ${BatchState.summary}" }
        inst.runOnMainSync { check(CaptureStore.captures.size == 7) { "open project got ${CaptureStore.captures.size}" } }
        Thread.sleep(2000)   // the other project's file is written on the disk thread
        val other = Projects.load(appCtx, Projects.idOf(f2.path))
        check(other?.frames?.size == 3) { "other project frames: ${other?.frames?.size}" }

        // 11-13. Telegram post: pick 6 frames, then the editor (fake bot settings, nothing is sent)
        com.dave.framegrabber.telegram.TgStore(appCtx).apply {
            token = "123:fake"; chat = "@my_channel"; chatTitle = "My Channel"; botName = "@my_bot"
            clearDraft()
        }
        inst.runOnMainSync { a.findViewById<android.view.View>(R.id.navFrames).performClick() }
        Thread.sleep(800)
        inst.runOnMainSync { a.findViewById<android.view.View>(R.id.btnTgPost).performClick() }
        Thread.sleep(600)
        inst.runOnMainSync { listOf(2, 0, 1, 3, 4).forEach { main.screen.tgPick.toggle(CaptureStore.captures[it]) } }
        Thread.sleep(1200); shot("11_tg_pick")
        inst.runOnMainSync { main.screen.tgPick.toggle(CaptureStore.captures[5]) }
        // earlier posts: one of this very video (with the same 1st picture), others for the statistics
        File(appCtx.filesDir, "tg_posts.json").delete()
        com.dave.framegrabber.telegram.PostLog(appCtx).apply {
            val now = System.currentTimeMillis(); val day = 86_400_000L
            val key = com.dave.framegrabber.telegram.PostLog.videoKey(f.length(), main.screen.video.engine!!.durationUs)
            val h = com.dave.framegrabber.telegram.PostLog.hashOf(CaptureStore.captures[2].file)!!
            add(com.dave.framegrabber.telegram.PostLog.Post(now - 20 * day, "@my_channel", 10, "https://t.me/my_channel/10",
                "Studio name", listOf("Model A", "Model B"), listOf("натуральные", "hd"), "h264", key, listOf(h)))
            listOf(
                Triple("Alpha Studio", listOf("Model C"), listOf("блондинки", "догги", "hd")),
                Triple("Alpha Studio", listOf("Model D", "Model C"), listOf("брюнетки", "4k")),
                Triple("Beta Films", listOf("Model E"), listOf("блондинки", "hd", "раздевание")),
                Triple("Alpha Studio", listOf("Model C"), listOf("блондинки", "hd")),
            ).forEachIndexed { i, (st, m, t) ->
                add(com.dave.framegrabber.telegram.PostLog.Post(now - (12 - i * 3) * day, "@my_channel", 20L + i,
                    "https://t.me/my_channel/${20 + i}", st, m, t, "v$i", "k$i", listOf(i.toLong() * 7919)))
            }
        }
        val mon = inst.addMonitor(com.dave.framegrabber.ui.telegram.PostActivity::class.java.name, null, false)
        inst.runOnMainSync { a.findViewById<android.view.View>(R.id.btnTgPickNext).performClick() }
        val post = inst.waitForMonitorWithTimeout(mon, 8000)
        check(post != null) { "post editor didn't open" }
        Thread.sleep(1500)
        inst.runOnMainSync {
            post.findViewById<android.widget.EditText>(R.id.editStudio).setText("Studio name")
            val m = post.findViewById<android.widget.EditText>(R.id.editModel)
            m.setText("Model A"); post.findViewById<android.view.View>(R.id.btnAddModel).performClick()
            m.setText("Model B"); post.findViewById<android.view.View>(R.id.btnAddModel).performClick()
            // chosen in any order; the caption keeps the library order
            listOf("hd", "догги", "блондинки", "натуральные").forEach { (post as com.dave.framegrabber.ui.telegram.PostActivity).tags.toggle(it) }
            (post as com.dave.framegrabber.ui.telegram.PostActivity).tags.openCategory("Позы и виды секса")
            post.currentFocus?.clearFocus()
        }
        Thread.sleep(1500); shot("12_post")
        inst.runOnMainSync {
            check(post.findViewById<android.view.View>(R.id.cardDup).visibility == android.view.View.VISIBLE) { "no 'already posted' warning" }
            check(post.findViewById<android.view.View>(R.id.txtNamesHint).visibility == android.view.View.VISIBLE) { "no names hint" }
        }
        inst.runOnMainSync {
            val txt = post.findViewById<android.widget.TextView>(R.id.txtPreview).text.toString()
            check(txt == "Studio name - Model A & Model B\n${com.dave.framegrabber.telegram.PostCaption.SEPARATOR}\n#натуральные #блондинки #догги #hd") { "caption: $txt" }
            post.findViewById<android.widget.ScrollView>(R.id.scroll).fullScroll(android.view.View.FOCUS_DOWN)
        }
        Thread.sleep(1200); shot("12b_post_preview")
        // the picker part of the editor
        inst.runOnMainSync {
            val sc = post.findViewById<android.widget.ScrollView>(R.id.scroll)
            val area = post.findViewById<android.view.View>(R.id.tagArea)
            sc.scrollTo(0, (area.parent as android.view.View).top)
        }
        Thread.sleep(1000); shot("12c_tag_picker")
        val tmon = inst.addMonitor(com.dave.framegrabber.ui.telegram.TagsActivity::class.java.name, null, false)
        inst.runOnMainSync { post.findViewById<android.view.View>(R.id.btnEditTags).performClick() }
        val tags = inst.waitForMonitorWithTimeout(tmon, 8000)
        check(tags != null) { "tag manager didn't open" }
        Thread.sleep(1200)
        // open the first category
        inst.runOnMainSync { (tags.findViewById<android.view.ViewGroup>(R.id.catsBox).getChildAt(0) as android.view.ViewGroup).getChildAt(0).performClick() }
        Thread.sleep(1000); shot("13_hashtags")
        tags.finish()
        Thread.sleep(800)
        val smon = inst.addMonitor(com.dave.framegrabber.ui.telegram.StatsActivity::class.java.name, null, false)
        inst.runOnMainSync { post.findViewById<android.view.View>(R.id.btnStats).performClick() }
        val stats = inst.waitForMonitorWithTimeout(smon, 8000)
        check(stats != null) { "stats didn't open" }
        Thread.sleep(1500); shot("14_stats")
        inst.runOnMainSync { (stats.window.decorView as android.view.ViewGroup).let { root ->
            fun find(v: android.view.View): android.widget.ScrollView? = v as? android.widget.ScrollView
                ?: (v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) } }
            find(root)?.scrollTo(0, 1400)
        } }
        Thread.sleep(1000); shot("14b_stats")
        stats.finish()
        Thread.sleep(800)
        post.finish(); Thread.sleep(800)
        inst.runOnMainSync { main.screen.tgPick.cancel(); a.findViewById<android.view.View>(R.id.navTools).performClick() }
        Thread.sleep(600)
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
        b.finish(); Thread.sleep(800)

        // 8b. multi-select (for «Несколько видео сразу»)
        val m = launch(Intent().setClassName(appCtx, FileBrowserActivity::class.java.name)
            .putExtra(FileBrowserActivity.EXTRA_MULTI, true))
        Thread.sleep(2500)
        inst.runOnMainSync { m.findViewById<android.widget.EditText>(R.id.editSearch).setText("synth") }
        Thread.sleep(3000)
        inst.runOnMainSync {
            val list = m.findViewById<ListView>(R.id.list)
            for (i in 0 until list.adapter.count) {
                if (list.adapter.getItem(i) is com.dave.framegrabber.ui.browser.Entry.Video) {
                    list.performItemClick(list.getChildAt(i - list.firstVisiblePosition), i, i.toLong()); break
                }
            }
        }
        Thread.sleep(1500); shot("8b_multi_select")
        m.finish()
    }
}
