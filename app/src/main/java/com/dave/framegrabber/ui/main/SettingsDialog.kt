package com.dave.framegrabber.ui.main

import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.provider.DocumentsContract
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.dave.framegrabber.R
import com.dave.framegrabber.data.AppTheme
import com.dave.framegrabber.data.Naming
import com.dave.framegrabber.data.Output
import com.dave.framegrabber.data.Prefs
import com.dave.framegrabber.ocr.Ocr
import com.dave.framegrabber.ui.anim
import com.dave.framegrabber.ui.dp
import com.dave.framegrabber.ui.onTextChanged
import com.dave.framegrabber.ui.toast
import com.dave.framegrabber.ui.widget.SynthwaveView

/**
 * ⚙ «Общие настройки»: theme, file name pattern, save folder, OCR languages.
 * Everything applies immediately; nothing on the screen is lost.
 */
class SettingsDialog(private val s: MainScreen) {

    /** The folder label of the open dialog, updated when a folder is picked. */
    private var folderLabel: TextView? = null

    fun show() {
        val v = s.act.layoutInflater.inflate(R.layout.dialog_settings, null)
        bindTheme(v)
        bindNames(v)
        bindFolder(v)
        bindOcr(v)
        val preview = v.findViewById<SynthwaveView>(R.id.wavePreview)
        val dlg = AlertDialog.Builder(s.act).setView(v).create()
        v.findViewById<View>(R.id.btnDone).setOnClickListener { dlg.dismiss() }
        dlg.setOnDismissListener { preview.running = false; folderLabel = null }
        dlg.show()
        listOf(v.findViewById<View>(R.id.optClassic), v.findViewById<View>(R.id.optWave)).forEachIndexed { i, o ->
            o.alpha = 0f; o.translationY = 24 * o.dp
            o.anim().alpha(1f).translationY(0f).setStartDelay(80L + 70L * i).setDuration(300).start()
        }
    }

    private fun bindTheme(v: View) {
        val optClassic = v.findViewById<View>(R.id.optClassic)
        val optWave = v.findViewById<View>(R.id.optWave)
        val chkClassic = v.findViewById<View>(R.id.chkClassic)
        val chkWave = v.findViewById<View>(R.id.chkWave)
        v.findViewById<View>(R.id.waveSwatch).clipToOutline = true

        fun showChoice(t: AppTheme, animate: Boolean) {
            optClassic.isSelected = t == AppTheme.CLASSIC
            optWave.isSelected = t == AppTheme.WAVE
            for ((chk, on) in listOf(chkClassic to (t == AppTheme.CLASSIC), chkWave to (t == AppTheme.WAVE))) {
                if (!animate) { chk.alpha = if (on) 1f else 0f; continue }
                if (on) {
                    chk.scaleX = 0.3f; chk.scaleY = 0.3f
                    chk.anim().alpha(1f).scaleX(1f).scaleY(1f).setInterpolator(OvershootInterpolator(3f)).setDuration(300).start()
                } else chk.anim().alpha(0f).setDuration(150).start()
            }
        }
        fun choose(t: AppTheme) {
            if (AppTheme.get(s.act) == t) return
            AppTheme.set(s.act, t)
            showChoice(t, animate = true)
            AppTheme.apply(s.act, s.act.bgView, t, animate = true)
        }
        showChoice(AppTheme.get(s.act), animate = false)
        optClassic.setOnClickListener { choose(AppTheme.CLASSIC) }
        optWave.setOnClickListener { choose(AppTheme.WAVE) }
    }

    private fun bindNames(v: View) {
        val edit = v.findViewById<EditText>(R.id.editPattern)
        val example = v.findViewById<TextView>(R.id.txtPatternPreview)
        fun update() {
            example.text = "Пример: " + Naming.stem(edit.text.toString(), s.video.baseName, 83_500_000L, 1) + ".jpg"
        }
        edit.setText(s.prefs.namePattern)
        update()
        edit.onTextChanged { s.prefs.namePattern = it.ifBlank { Prefs.DEFAULT_PATTERN }; update() }
    }

    private fun bindFolder(v: View) {
        val label = v.findViewById<TextView>(R.id.txtFolder)
        label.text = Output.describe(s.act)
        folderLabel = label
        v.findViewById<View>(R.id.rowFolder).setOnClickListener {
            AlertDialog.Builder(s.act)
                .setTitle("Куда сохранять кадры")
                .setItems(arrayOf("Выбрать папку на телефоне…", "По умолчанию: Pictures/${Prefs.ALBUM}")) { _, which ->
                    if (which == 0) s.act.pickFolder() else resetFolder(label)
                }.show()
        }
    }

    private fun resetFolder(label: TextView) {
        s.prefs.folderTree?.let { old ->
            runCatching {
                s.act.contentResolver.releasePersistableUriPermission(Uri.parse(old),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        }
        s.prefs.folderTree = null; s.prefs.folderTreeName = null
        label.text = Output.describe(s.act)
        s.grid.forgetSavedCopies()
    }

    /** Result of the system folder picker (see MainActivity.pickFolder). */
    fun onFolderPicked(tree: Uri) {
        runCatching {
            s.act.contentResolver.takePersistableUriPermission(tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        s.prefs.folderTree = tree.toString()
        s.prefs.folderTreeName = Uri.decode(DocumentsContract.getTreeDocumentId(tree)).replace("primary:", "Память/")
        folderLabel?.text = Output.describe(s.act)
        s.grid.forgetSavedCopies()  // next save goes to the new folder
        s.act.toast("Кадры будут сохраняться в: ${Output.describe(s.act)}")
    }

    private fun bindOcr(v: View) {
        val box = v.findViewById<LinearLayout>(R.id.ocrLangs)
        val chosen = s.prefs.ocrLangs.split("+").toMutableSet()
        for ((code, title) in Ocr.LANGS) {
            box.addView(CheckBox(s.act).apply {
                text = title
                setTextColor(s.act.getColor(R.color.text))
                buttonTintList = ColorStateList.valueOf(s.act.getColor(R.color.accent))
                isChecked = code in chosen
                setOnCheckedChangeListener { cb, on ->
                    if (on) chosen += code else chosen -= code
                    if (chosen.isEmpty()) { chosen += code; cb.isChecked = true; s.act.toast("Нужен хотя бы один язык") }
                    s.prefs.ocrLangs = Ocr.LANGS.keys.filter { it in chosen }.joinToString("+")
                }
            })
        }
    }
}
