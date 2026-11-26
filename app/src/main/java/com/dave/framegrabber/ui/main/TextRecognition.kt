package com.dave.framegrabber.ui.main

import com.dave.framegrabber.ui.tc

import android.app.AlertDialog
import android.graphics.BitmapFactory
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import com.dave.framegrabber.R
import com.dave.framegrabber.data.Capture
import com.dave.framegrabber.ocr.Ocr
import com.dave.framegrabber.ui.copyToClipboard
import com.dave.framegrabber.ui.dp
import com.dave.framegrabber.ui.progressDialog
import com.dave.framegrabber.ui.shareText
import com.dave.framegrabber.ui.showError
import com.dave.framegrabber.ui.toast

/** «Текст» on an opened frame: OCR, with a one-time download of the language files. */
class TextRecognition(private val s: MainScreen) {

    fun recognize(c: Capture) {
        val langs = s.prefs.ocrLangs.split("+").filter { it.isNotBlank() }
        val missing = Ocr.missing(s.act, langs)
        if (missing.isNotEmpty()) {
            val names = missing.joinToString { Ocr.LANGS[it] ?: it }
            AlertDialog.Builder(s.act)
                .setTitle("Распознавание текста")
                .setMessage("Один раз нужно скачать языковые файлы ($names, ≈ ${missing.size * 4} МБ). Потом всё работает без интернета.\n\nЯзыки можно поменять в общих настройках.")
                .setPositiveButton("Скачать") { _, _ -> download(missing) { recognize(c) } }
                .setNegativeButton("Отмена", null)
                .show()
            return
        }
        val dlg = s.act.progressDialog("Читаю текст…")
        Thread {
            val r = runCatching {
                val bmp = BitmapFactory.decodeFile(c.file.path) ?: error("Не удалось открыть кадр")
                Ocr.recognize(s.act, bmp, langs).also { bmp.recycle() }
            }
            s.ui.post {
                dlg.dismiss()
                r.onSuccess { show(it) }.onFailure { s.act.showError("Не удалось распознать текст", it) }
            }
        }.start()
    }

    private fun download(langs: List<String>, then: () -> Unit) {
        val dlg = s.act.progressDialog("Скачиваю языковые файлы…")
        Thread {
            val r = runCatching {
                Ocr.download(s.act, langs) { lang, f ->
                    s.ui.post { dlg.findViewById<TextView>(android.R.id.message)?.text = "Скачиваю: ${Ocr.LANGS[lang]} ${(f * 100).toInt()}%" }
                }
            }
            s.ui.post {
                dlg.dismiss()
                r.onSuccess { then() }.onFailure { s.act.showError("Не удалось скачать (нужен интернет)", it) }
            }
        }.start()
    }

    private fun show(text: String) {
        if (text.isBlank()) { s.act.toast("Текст на кадре не найден"); return }
        val pad = (20 * s.act.resources.displayMetrics.density).toInt()
        val tv = EditText(s.act).apply {
            setText(text)
            setTextColor(s.act.tc(R.color.text))
            textSize = 15f
            background = null
            setTextIsSelectable(true)
            keyListener = null
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        AlertDialog.Builder(s.act)
            .setTitle("Текст на кадре")
            .setView(ScrollView(s.act).apply { addView(tv) })
            .setPositiveButton("Копировать") { _, _ -> s.act.copyToClipboard("text", text); s.act.toast("Скопировано") }
            .setNeutralButton("Поделиться") { _, _ -> s.act.shareText(text) }
            .setNegativeButton("Закрыть", null)
            .show()
    }
}
