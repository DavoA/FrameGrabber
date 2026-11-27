package com.dave.framegrabber.ui.main

import com.dave.framegrabber.ui.tc

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
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
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
import com.dave.framegrabber.ui.showAnimated
import com.dave.framegrabber.ui.toast
import com.dave.framegrabber.ui.shareUri
import com.dave.framegrabber.ui.widget.SynthwaveView

/**
 * ⚙ «Общие настройки»: theme, frame quality (size, format, quality, sharpest frame),
 * file name pattern, save folder, OCR languages.
 * Everything applies immediately; nothing on the screen is lost.
 */
class SettingsDialog(private val s: MainScreen) {

    /** The folder label of the open dialog, updated when a folder is picked. */
    private var folderLabel: TextView? = null

    fun show() {
        val v = s.act.layoutInflater.inflate(R.layout.dialog_settings, null)
        v.findViewById<android.widget.TextView>(R.id.txtVersion).text = runCatching {
            "Кадры из видео · версия " + s.act.packageManager.getPackageInfo(s.act.packageName, 0).versionName
        }.getOrDefault("")
        bindTheme(v)
        bindQuality(v)
        bindNames(v)
        bindFolder(v)
        bindOcr(v)
        bindTelegram(v)
        val preview = v.findViewById<SynthwaveView>(R.id.wavePreview)
        val dlg = AlertDialog.Builder(s.act).setView(v).create()
        openDlg = dlg
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
        bindSchemes(v)
        bindBackup(v)
        bindTorrents(v)
        bindLock(v)
        v.findViewById<View>(R.id.btnStorage).setOnClickListener {
            openDlg?.dismiss()
            s.act.startActivity(com.dave.framegrabber.ui.StorageActivity.intent(s.act))
        }
        optClassic.setOnClickListener { choose(AppTheme.CLASSIC) }
        optWave.setOnClickListener { choose(AppTheme.WAVE) }
    }

    /** Color scheme: green, pink, orange, red, purple — the app is redrawn in it. */
    private var openDlg: AlertDialog? = null

    /** «Защита»: PIN on entry, fingerprint, how soon it locks again. */
    private fun bindLock(v: View) {
        val box = v.findViewById<android.widget.LinearLayout>(R.id.lockBox)
        val d = s.act.resources.displayMetrics.density
        val L = com.dave.framegrabber.data.AppLock
        val Lock = com.dave.framegrabber.ui.LockActivity
        fun open(mode: Int) { openDlg?.dismiss(); s.act.startActivity(Lock.intent(s.act, mode)) }
        fun btn(t: String, onClick: () -> Unit) = android.widget.Button(s.act, null, 0, R.style.BtnSecondary).apply {
            text = t; gravity = android.view.Gravity.CENTER; setOnClickListener { onClick() }
            layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * d).toInt() }
        }
        fun build() {
            box.removeAllViews()
            if (!L.enabled(s.act)) { box.addView(btn("Поставить PIN-код на вход") { open(Lock.MODE_SET) }); return }
            // fingerprint on / off
            val bio = L.bio(s.act)
            box.addView(TextView(s.act).apply {
                text = (if (bio) "● " else "○ ") + "Отпечаток пальца вместо PIN"; textSize = 15f
                setTextColor(s.act.tc(if (bio) R.color.text else R.color.textDim))
                setBackgroundResource(R.drawable.bg_option); isSelected = bio
                setPadding((14 * d).toInt(), (12 * d).toInt(), (14 * d).toInt(), (12 * d).toInt())
                layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * d).toInt() }
                setOnClickListener { L.setBio(s.act, !bio); build() }
            })
            box.addView(TextView(s.act).apply {
                text = "Снова спрашивать, если приложение было свёрнуто дольше:"; textSize = 12f; setTextColor(s.act.tc(R.color.textDim))
                setPadding(0, (12 * d).toInt(), 0, 0)
            })
            val row = android.widget.LinearLayout(s.act)
            val cur = L.delay(s.act)
            L.DELAYS.forEach { (sec, t) ->
                row.addView(TextView(s.act).apply {
                    text = t; textSize = 13f; gravity = android.view.Gravity.CENTER
                    setTextColor(s.act.tc(if (sec == cur) R.color.text else R.color.textDim))
                    setBackgroundResource(R.drawable.bg_option); isSelected = sec == cur
                    setPadding(0, (10 * d).toInt(), 0, (10 * d).toInt())
                    layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins((3 * d).toInt(), (6 * d).toInt(), (3 * d).toInt(), 0) }
                    setOnClickListener { L.setDelay(s.act, sec); build() }
                })
            }
            box.addView(row)
            box.addView(TextView(s.act).apply {
                text = "Выключил экран — закроется сразу."; textSize = 12f; setTextColor(s.act.tc(R.color.textDim)); setPadding(0, (6 * d).toInt(), 0, 0)
            })
            val two = android.widget.LinearLayout(s.act)
            two.addView(btn("Сменить PIN") { open(Lock.MODE_CHANGE) }.apply { layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f).apply { topMargin = (8 * d).toInt(); rightMargin = (4 * d).toInt() } })
            two.addView(btn("Выключить") { open(Lock.MODE_OFF) }.apply { layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f).apply { topMargin = (8 * d).toInt(); leftMargin = (4 * d).toInt() } })
            box.addView(two)
        }
        build()
    }

    /** «Торренты»: download in the app / open in a torrent app / only save the .torrent. */
    private fun bindTorrents(v: View) {
        val box = v.findViewById<android.widget.LinearLayout>(R.id.torrentModes)
        val d = s.act.resources.displayMetrics.density
        fun build() {
            box.removeAllViews()
            val cur = com.dave.framegrabber.torrent.TorrentMode.get(s.act)
            com.dave.framegrabber.torrent.TorrentMode.entries.forEach { m ->
                box.addView(android.widget.LinearLayout(s.act).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setBackgroundResource(R.drawable.bg_option)
                    isSelected = m == cur
                    setPadding((14 * d).toInt(), (10 * d).toInt(), (14 * d).toInt(), (10 * d).toInt())
                    layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = (6 * d).toInt() }
                    addView(TextView(s.act).apply {
                        text = (if (m == cur) "● " else "○ ") + m.title; textSize = 15f
                        setTextColor(s.act.tc(if (m == cur) R.color.text else R.color.textDim))
                    })
                    addView(TextView(s.act).apply { text = m.hint; textSize = 12f; setTextColor(s.act.tc(R.color.textDim)) })
                    setOnClickListener {
                        com.dave.framegrabber.torrent.TorrentMode.set(s.act, m)
                        if (m == com.dave.framegrabber.torrent.TorrentMode.CLIENT)
                            com.dave.framegrabber.player.Library(s.act).addFolder(
                                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS).path)
                        build()
                    }
                })
            }
        }
        build()
    }

    /** «Резервная копия»: save to Download/FrameGrabber, or restore from a file. */
    private fun bindBackup(v: View) {
        v.findViewById<View>(R.id.btnBackupSave).setOnClickListener {
            s.io.execute {
                val r = runCatching { com.dave.framegrabber.data.Backup.export(s.act) }
                s.ui.post {
                    r.onSuccess {
                        AlertDialog.Builder(s.act).setTitle("Копия сохранена ✓")
                            .setMessage("Download/FrameGrabber. В ней токен бота и ключи Telegram — не отправляй файл посторонним.")
                            .setPositiveButton("OK", null)
                            .setNeutralButton("Отправить себе") { _, _ -> s.act.shareUri(it, "application/json") }
                            .show()
                    }.onFailure { s.act.toast("Не удалось: ${it.message}") }
                }
            }
        }
        v.findViewById<View>(R.id.btnBackupRestore).setOnClickListener {
            AlertDialog.Builder(s.act).setTitle("Восстановить из копии?")
                .setMessage("Текущие настройки заменятся теми, что в файле. Потом приложение перезапустится.")
                .setPositiveButton("Выбрать файл") { _, _ -> s.act.pickBackup() }
                .setNegativeButton("Отмена", null).show()
        }
    }

    private fun bindSchemes(v: View) {
        val row = v.findViewById<android.widget.LinearLayout>(R.id.schemeRow)
        val d = s.act.resources.displayMetrics.density
        val cur = com.dave.framegrabber.data.ColorScheme.get(s.act)
        com.dave.framegrabber.data.ColorScheme.entries.forEach { sc ->
            val item = android.widget.LinearLayout(s.act).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                setPadding((6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt())
                setBackgroundResource(R.drawable.bg_option)
                isSelected = sc == cur
                layoutParams = android.widget.LinearLayout.LayoutParams((78 * d).toInt(), -2).apply { marginEnd = (8 * d).toInt() }
            }
            item.addView(View(s.act).apply {
                background = android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF000000.toInt(), sc.swatch)
                ).apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setStroke((2 * d).toInt(), sc.swatch) }
            }, android.widget.LinearLayout.LayoutParams((40 * d).toInt(), (40 * d).toInt()))
            item.addView(TextView(s.act).apply {
                text = sc.title; textSize = 11f; gravity = android.view.Gravity.CENTER
                setTextColor(s.act.tc(if (sc == cur) R.color.text else R.color.textDim))
                setPadding(0, (4 * d).toInt(), 0, 0)
            })
            item.setOnClickListener {
                if (sc == com.dave.framegrabber.data.ColorScheme.get(s.act)) return@setOnClickListener
                com.dave.framegrabber.data.ColorScheme.set(s.act, sc)
                // (the home screen icon follows when you leave the app: switching it now would close the app)
                openDlg?.dismiss()
                s.act.recreate()
            }
            row.addView(item)
        }
    }

    /** Size / format / quality / "sharpest frame nearby" — saved instantly. */
    private fun bindQuality(v: View) {
        val p = s.prefs
        val radioSize = v.findViewById<RadioGroup>(R.id.radioSize)
        val customRow = v.findViewById<View>(R.id.customSizeRow)
        val editW = v.findViewById<EditText>(R.id.editW)
        val editH = v.findViewById<EditText>(R.id.editH)
        val radioFormat = v.findViewById<RadioGroup>(R.id.radioFormat)
        val txtQuality = v.findViewById<TextView>(R.id.txtQuality)
        val seekQuality = v.findViewById<SeekBar>(R.id.seekQuality)
        val swSharpest = v.findViewById<Switch>(R.id.swSharpest)

        fun showQuality(lossless: Boolean) {
            seekQuality.isEnabled = !lossless
            seekQuality.alpha = if (lossless) 0.35f else 1f
            txtQuality.text = if (lossless) "Качество: без потерь" else "Качество: ${seekQuality.progress}"
        }
        // saved values first, then listeners
        radioFormat.check(when (p.format) { "PNG" -> R.id.fmtPng; "WEBP" -> R.id.fmtWebp; else -> R.id.fmtJpg })
        radioSize.check(when (p.size) { "720" -> R.id.size720; "1080" -> R.id.size1080; "custom" -> R.id.sizeCustom; else -> R.id.sizeOrig })
        customRow.visibility = if (p.size == "custom") View.VISIBLE else View.GONE
        editW.setText(p.customW); editH.setText(p.customH)
        seekQuality.progress = p.quality
        showQuality(p.format == "PNG")
        swSharpest.isChecked = p.sharpest

        radioSize.setOnCheckedChangeListener { _, id ->
            if (id == R.id.sizeCustom) customRow.showAnimated() else customRow.visibility = View.GONE
            p.size = when (id) { R.id.size720 -> "720"; R.id.size1080 -> "1080"; R.id.sizeCustom -> "custom"; else -> "orig" }
        }
        radioFormat.setOnCheckedChangeListener { _, id ->
            p.format = when (id) { R.id.fmtPng -> "PNG"; R.id.fmtWebp -> "WEBP"; else -> "JPG" }
            showQuality(id == R.id.fmtPng)
        }
        seekQuality.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                if (value < 1) sb.progress = 1
                txtQuality.text = "Качество: ${sb.progress}"
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) { p.quality = sb.progress }
        })
        editW.onTextChanged { p.customW = it }
        editH.onTextChanged { p.customH = it }
        swSharpest.setOnCheckedChangeListener { _, on -> p.sharpest = on }
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
                setTextColor(s.act.tc(R.color.text))
                buttonTintList = ColorStateList.valueOf(s.act.tc(R.color.accent))
                isChecked = code in chosen
                setOnCheckedChangeListener { cb, on ->
                    if (on) chosen += code else chosen -= code
                    if (chosen.isEmpty()) { chosen += code; cb.isChecked = true; s.act.toast("Нужен хотя бы один язык") }
                    s.prefs.ocrLangs = Ocr.LANGS.keys.filter { it in chosen }.joinToString("+")
                }
            })
        }
    }

    /** Bot + channel (with a real check) and the hashtag library. */
    private fun bindTelegram(v: View) {
        val setup = v.findViewById<TextView>(R.id.txtTgSetup)
        val tags = v.findViewById<TextView>(R.id.txtTags)
        fun refresh() {
            val st = com.dave.framegrabber.telegram.TgStore(s.act)
            setup.text = if (st.configured) "${st.chatTitle.ifEmpty { st.chat }}\nбот ${st.botName}" else "Бот и канал не настроены"
            val lib = com.dave.framegrabber.telegram.TagLibrary(s.act)
            tags.text = "Хэштеги: ${lib.all.size} в ${lib.categories.size} категориях"
        }
        refresh()
        v.findViewById<View>(R.id.rowTgSetup).setOnClickListener {
            com.dave.framegrabber.ui.telegram.TgSetupDialog(s.act) { refresh() }.show()
        }
        val pf = v.findViewById<TextView>(R.id.txtPlayerFolders)
        fun pfText() { val n = com.dave.framegrabber.player.Library(s.act).folders.size; pf.text = if (n == 0) "Папки с видео не выбраны" else "Папки с видео: $n" }
        pfText()
        v.findViewById<View>(R.id.rowPlayerFolders).setOnClickListener { s.playerTab.showFolders() }
        val lib = com.dave.framegrabber.player.Library(s.act)
        v.findViewById<android.widget.Switch>(R.id.swPreviewKeep).apply {
            isChecked = lib.previewKeep
            setOnCheckedChangeListener { _, on -> lib.previewKeep = on }
        }
        v.findViewById<View>(R.id.rowTags).setOnClickListener {
            s.act.startActivity(android.content.Intent(s.act, com.dave.framegrabber.ui.telegram.TagsActivity::class.java))
        }
    }
}
