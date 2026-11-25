package com.dave.framegrabber.ui.main

import android.app.AlertDialog
import android.net.Uri
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import com.dave.framegrabber.data.Output
import com.dave.framegrabber.ui.hideAnimated
import com.dave.framegrabber.ui.shareUri
import com.dave.framegrabber.ui.showAnimated
import com.dave.framegrabber.ui.showError
import com.dave.framegrabber.ui.toast

/**
 * Runs one export at a time on a background thread with a progress bar + status line,
 * then offers to share the result. Used by «Клипы» (GIF, clip) and «Лист кадров».
 */
class ExportJob(private val s: MainScreen, private val progress: ProgressBar, private val status: TextView) {

    private var busy = false

    fun launch(label: String, startText: String, mime: String, onDone: (Uri) -> Unit = {}, work: () -> Uri) {
        if (busy) { s.act.toast("Подожди, идёт экспорт"); return }
        busy = true
        progress.progress = 0
        progress.showAnimated()
        status.visibility = View.VISIBLE
        status.text = startText
        Thread {
            val r = runCatching(work)
            s.ui.post { finished(r, label, mime, onDone) }
        }.start()
    }

    /** Call from the worker thread, [f] = 0..1. */
    fun report(f: Float, label: String) = s.ui.post {
        progress.setProgress((f * 1000).toInt(), true)
        status.text = "$label ${(f * 100).toInt()}%"
    }

    private fun finished(result: Result<Uri>, label: String, mime: String, onDone: (Uri) -> Unit) {
        busy = false
        progress.hideAnimated(delay = 400)
        result.onSuccess { uri ->
            status.text = "$label готов: ${Output.describe(s.act)}"
            onDone(uri)
            AlertDialog.Builder(s.act)
                .setTitle("$label готов")
                .setMessage("Сохранено: ${Output.describe(s.act)}")
                .setPositiveButton("Поделиться") { _, _ -> s.act.shareUri(uri, mime) }
                .setNegativeButton("OK", null)
                .show()
        }.onFailure { status.text = "Ошибка: ${it.message}"; s.act.showError("Не получилось: $label", it) }
    }
}
