package com.dave.framegrabber

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Environment
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dave.framegrabber.core.Fmt
import com.dave.framegrabber.data.Projects
import com.dave.framegrabber.ui.telegram.PostActivity
import com.dave.framegrabber.ui.telegram.QueueActivity
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** «Очередь постов»: the list of videos with frames and the post page of a queue step. */
@RunWith(AndroidJUnit4::class)
class QueueTest {
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

    private fun fakeProject(name: String, n: Int): Projects.Project {
        val ref = "/sdcard/queue-test/$name.mp4"
        val id = Projects.idOf(ref)
        val p = Projects.Project(id, ref, true, name)
        val dir = Projects.dir(ctx, id).apply { mkdirs() }
        repeat(n) { i ->
            val b = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
            Canvas(b).apply {
                drawColor(Color.HSVToColor(floatArrayOf((i * 40f + name.length * 25f) % 360, 0.6f, 0.8f)))
                drawText("$name #${i + 1}", 20f, 100f, Paint().apply { textSize = 36f; color = Color.WHITE })
            }
            val f = File(dir, "f$i.jpg"); f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            p.frames += Projects.Frame(f.name, i * 5_000_000L, Fmt.JPG, 320, 180, true, false, null)
        }
        Projects.save(ctx, p)
        return p
    }

    @Test fun queueScreens() {
        val a = fakeProject("Studio A clip", 8)
        val b = fakeProject("Another video", 6)
        try {
            val q = inst.startActivitySync(Intent(ctx, QueueActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Thread.sleep(2500); shot("queue_list")
            q.finish()
            val dir = Projects.dir(ctx, a.id)
            val post = inst.startActivitySync(PostActivity.queueIntent(ctx, a.frames.map { File(dir, it.file).path }, a.frames.map { it.timeUs },
                a.name, "", null, 1, 2).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Thread.sleep(2500); shot("queue_post")
            post.finish()
        } finally {
            Projects.delete(ctx, a.id); Projects.delete(ctx, b.id)
        }
    }
}
