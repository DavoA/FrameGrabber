package com.dave.framegrabber.ui.main

import android.app.AlertDialog
import android.graphics.Bitmap
import android.media.MediaMuxer
import android.net.Uri
import android.view.View
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import com.dave.framegrabber.R
import com.dave.framegrabber.core.TimeUtil
import com.dave.framegrabber.data.CaptureStore
import com.dave.framegrabber.data.Naming
import com.dave.framegrabber.data.Output
import com.dave.framegrabber.export.ContactSheet
import com.dave.framegrabber.export.Exporters
import com.dave.framegrabber.ui.bump
import com.dave.framegrabber.ui.hideAnimated
import com.dave.framegrabber.ui.shareUri
import com.dave.framegrabber.ui.showAnimated
import com.dave.framegrabber.ui.showError
import com.dave.framegrabber.ui.toast
import java.io.File

/**
 * Tab «Экспорт»: GIF and MP4 clip from the «Отрезок», contact sheet from the captured frames.
 * Everything is written straight into the destination file (no temp files in the cache).
 */
class ExportPanel(private val s: MainScreen) {

    companion object {
        private const val GIF_MAX_US = 20_000_000L
    }

    private val radioFps = s.h<RadioGroup>(R.id.radioGifFps)
    private val radioWidth = s.h<RadioGroup>(R.id.radioGifW)
    private val progress = s.h<ProgressBar>(R.id.progressExport)
    private val txtStatus = s.h<TextView>(R.id.txtExport)
    private var busy = false

    init {
        val p = s.prefs
        radioFps.check(when (p.gifFps) { 5 -> R.id.fps5; 15 -> R.id.fps15; else -> R.id.fps10 })
        radioWidth.check(when (p.gifWidth) { 320 -> R.id.gw320; 720 -> R.id.gw720; else -> R.id.gw480 })
        radioFps.setOnCheckedChangeListener { _, id -> p.gifFps = when (id) { R.id.fps5 -> 5; R.id.fps15 -> 15; else -> 10 } }
        radioWidth.setOnCheckedChangeListener { _, id -> p.gifWidth = when (id) { R.id.gw320 -> 320; R.id.gw720 -> 720; else -> 480 } }
        s.h<View>(R.id.btnGif).setOnClickListener { gif() }
        s.h<View>(R.id.btnClip).setOnClickListener { clip() }
        s.h<View>(R.id.btnSheet).setOnClickListener { sheet() }
    }

    private fun gif() {
        val e = s.video.requireEngine() ?: return
        val uri = s.video.uri ?: return
        val (from, to) = s.range.require(e) ?: return
        if (to - from > GIF_MAX_US) {
            s.act.toast("GIF до 20 секунд — укажи отрезок короче (сейчас ${TimeUtil.format(to - from)})")
            s.range.card.bump(); return
        }
        val fps = s.prefs.gifFps
        val width = s.prefs.gifWidth
        val name = Naming.stem(s.prefs.namePattern, s.video.baseName, from, 1) + ".gif"
        launch("GIF", "Делаю GIF…", "image/gif") {
            Output.write(s.act, name, "image/gif", Output.Kind.IMAGE) { t ->
                t.stream().buffered().use { os ->
                    Exporters.gif(s.act, uri, from, to, fps, width, os) { reportProgress(it, "GIF"); true }
                }
            }
        }
    }

    private fun clip() {
        val e = s.video.requireEngine() ?: return
        val uri = s.video.uri ?: return
        val (from, to) = s.range.require(e) ?: return
        val name = Naming.stem(s.prefs.namePattern, s.video.baseName, from, 1) + "_clip.mp4"
        launch("Клип", "Вырезаю клип…", "video/mp4") {
            Output.write(s.act, name, "video/mp4", Output.Kind.VIDEO) { t ->
                val pfd = t.fd()
                if (pfd != null) {
                    // MediaMuxer writes straight into the destination file
                    pfd.use { fd ->
                        Exporters.clip(s.act, uri, from, to,
                            { MediaMuxer(fd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4) }
                        ) { reportProgress(it, "Клип"); true }
                    }
                } else {
                    // folder without read/write access: build in the app's permanent storage, then copy
                    val tmp = File(File(s.act.filesDir, "export").apply { mkdirs() }, "clip.mp4")
                    try {
                        Exporters.clip(s.act, uri, from, to, tmp) { reportProgress(it, "Клип"); true }
                        t.stream().use { o -> tmp.inputStream().use { it.copyTo(o) } }
                    } finally { tmp.delete() }
                }
            }
        }
    }

    private fun sheet() {
        val captures = CaptureStore.captures
        if (captures.isEmpty()) { s.act.toast("Сначала сними кадры — лист собирается из них"); return }
        val snapshot = captures.sortedBy { it.timeUs }
        val e = s.video.engine
        val title = s.video.baseName
        val sub = buildString {
            append("${snapshot.size} кадров")
            if (e != null) append(" · ${e.width}×${e.height} · ${TimeUtil.format(e.durationUs)}")
        }
        val name = Naming.stem(s.prefs.namePattern, title, 0, 1) + "_sheet.jpg"
        launch("Лист кадров", "Собираю лист кадров…", "image/jpeg") {
            reportProgress(0.3f, "Лист")
            val bmp = ContactSheet.build(snapshot, title, sub)
            reportProgress(0.8f, "Лист")
            Output.write(s.act, name, "image/jpeg", Output.Kind.IMAGE) { t ->
                t.stream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            }.also { bmp.recycle() }
        }
    }

    /** Runs [work] on a background thread with the progress bar, then offers to share the result. */
    private fun launch(label: String, startText: String, mime: String, work: () -> Uri) {
        if (busy) { s.act.toast("Подожди, идёт экспорт"); return }
        busy = true
        progress.progress = 0
        progress.showAnimated()
        txtStatus.visibility = View.VISIBLE
        txtStatus.text = startText
        Thread {
            val r = runCatching(work)
            s.ui.post { finished(r, label, mime) }
        }.start()
    }

    private fun reportProgress(f: Float, label: String) = s.ui.post {
        progress.setProgress((f * 1000).toInt(), true)
        txtStatus.text = "$label ${(f * 100).toInt()}%"
    }

    private fun finished(result: Result<Uri>, label: String, mime: String) {
        busy = false
        progress.hideAnimated(delay = 400)
        result.onSuccess { uri ->
            txtStatus.text = "$label готов: ${Output.describe(s.act)}"
            AlertDialog.Builder(s.act)
                .setTitle("$label готов")
                .setMessage("Сохранено: ${Output.describe(s.act)}")
                .setPositiveButton("Поделиться") { _, _ -> s.act.shareUri(uri, mime) }
                .setNegativeButton("OK", null)
                .show()
        }.onFailure { txtStatus.text = "Ошибка: ${it.message}"; s.act.showError("Не получилось: $label", it) }
    }
}
