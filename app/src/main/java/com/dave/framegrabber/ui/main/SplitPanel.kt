package com.dave.framegrabber.ui.main

import android.app.AlertDialog
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import com.dave.framegrabber.R
import com.dave.framegrabber.core.TimeUtil
import com.dave.framegrabber.split.SplitJob
import com.dave.framegrabber.split.SplitPlan
import com.dave.framegrabber.split.SplitState
import com.dave.framegrabber.ui.bump
import com.dave.framegrabber.ui.onTextChanged
import com.dave.framegrabber.ui.toast
import java.io.File
import kotlin.math.max

/**
 * «Инструменты» → «Разделить на части»: cuts the open video into parts of about equal size, each
 * under a limit (Telegram 2 GB by default), without re-encoding. Before cutting it shows every
 * part's time and size; the count can be raised by hand.
 */
class SplitPanel(private val s: MainScreen) {

    private val info = s.h<TextView>(R.id.txtSplitInfo)
    private val radio = s.h<RadioGroup>(R.id.radioSplitLimit)
    private val rowCustom = s.h<View>(R.id.rowSplitCustom)
    private val editMb = s.h<EditText>(R.id.editSplitMb)
    private val txtCount = s.h<TextView>(R.id.txtSplitCount)
    private val partsBox = s.h<LinearLayout>(R.id.splitParts)
    private val hint = s.h<TextView>(R.id.txtSplitHint)
    private val progress = s.h<ProgressBar>(R.id.progressSplit)
    private val btn = s.h<Button>(R.id.btnSplit)

    /** Sample sizes of the open video (read once per video). */
    private var scan: SplitPlan.Scan? = null
    private var scanUri: Uri? = null
    private var scanning = false
    private var fileBytes = 0L
    /** null = automatic (fewest parts under the limit). */
    private var count: Int? = null
    private var plan: SplitPlan.Plan? = null

    init {
        radio.check(when (s.prefs.splitLimit) {
            SplitPlan.TELEGRAM_PREMIUM_LIMIT -> R.id.sl4
            SplitPlan.TELEGRAM_LIMIT -> R.id.sl2
            else -> R.id.slCustom
        })
        if (radio.checkedRadioButtonId == R.id.slCustom) {
            rowCustom.visibility = View.VISIBLE
            editMb.setText((s.prefs.splitLimit / 1_048_576).toString())
        }
        radio.setOnCheckedChangeListener { _, id ->
            rowCustom.visibility = if (id == R.id.slCustom) View.VISIBLE else View.GONE
            when (id) {
                R.id.sl2 -> s.prefs.splitLimit = SplitPlan.TELEGRAM_LIMIT
                R.id.sl4 -> s.prefs.splitLimit = SplitPlan.TELEGRAM_PREMIUM_LIMIT
                else -> editMb.text.toString().toLongOrNull()?.takeIf { it > 0 }?.let { s.prefs.splitLimit = it * 1_048_576 }
            }
            count = null; replan()
        }
        editMb.onTextChanged {
            val mb = it.toLongOrNull() ?: return@onTextChanged
            if (mb in 1..100_000) { s.prefs.splitLimit = mb * 1_048_576; count = null; replan() }
        }
        s.h<View>(R.id.btnSplitMinus).setOnClickListener { change(-1) }
        s.h<View>(R.id.btnSplitPlus).setOnClickListener { change(+1) }
        btn.setOnClickListener { if (SplitState.running) SplitState.stop() else start() }
        SplitState.listener = object : SplitState.Listener {
            override fun onProgress() = showRunning()
            override fun onFinished() = finished()
        }
        if (SplitState.running) showRunning()
    }

    private val limit get() = s.prefs.splitLimit

    /** Another video was opened. */
    fun onVideoChanged() {
        scan = null; scanUri = null; plan = null; count = null
        partsBox.removeAllViews()
        val uri = s.video.uri ?: return
        fileBytes = sizeOf(uri)
        info.text = "Видео: ${size(fileBytes)} · ${TimeUtil.format(s.video.engine?.durationUs ?: 0)}"
        txtCount.text = "—"
        hint.text = ""
        if (s.nav.page == NavController.Page.TOOLS) ensureScan()
    }

    /** The «Инструменты» page was opened: work out the parts (once per video). */
    fun onShown() = ensureScan()

    private fun ensureScan() {
        val uri = s.video.uri ?: return
        if (scanning || (scan != null && scanUri == uri)) return
        scanning = true
        hint.text = "Считаю размеры частей…"
        val ctx = s.act.applicationContext
        Thread {
            val r = runCatching {
                SplitPlan.scan(ctx, uri, fileBytes) { f -> s.ui.post { if (s.video.uri == uri) hint.text = "Считаю размеры частей… ${(f * 100).toInt()}%" }; s.video.uri == uri }
            }
            s.ui.post {
                scanning = false
                if (s.video.uri != uri) { ensureScan(); return@post }
                r.onSuccess { scan = it; scanUri = uri; replan() }
                    .onFailure { e ->
                        if (e is InterruptedException) return@onFailure
                        hint.text = e.message ?: "Не удалось прочитать видео"
                        hint.setTextColor(s.act.getColor(R.color.danger))
                    }
            }
        }.start()
    }

    private fun change(d: Int) {
        val sc = scan ?: return
        val cur = plan?.parts?.size ?: return
        val n = (cur + d).coerceIn(1, 200)
        if (n == cur) return
        val p = SplitPlan.plan(sc, n, limit)
        if (p.parts.size < n) { s.act.toast("Мельче не получится: в видео мало ключевых кадров для разрезов"); return }
        count = n; plan = p
        render()
    }

    private fun replan() {
        val sc = scan ?: return
        plan = count?.let { SplitPlan.plan(sc, it, limit) } ?: SplitPlan.auto(sc, limit)
        render()
    }

    private fun render() {
        val p = plan ?: return
        hint.setTextColor(s.act.getColor(R.color.textDim))
        val n = p.parts.size
        txtCount.text = if (n == 1) "1 часть" else "$n ${if (n in 2..4) "части" else "частей"}"
        partsBox.removeAllViews()
        p.parts.forEach { part -> partsBox.addView(row(part, part.bytes > limit)) }
        val over = p.parts.count { it.bytes > limit }
        hint.text = when {
            n == 1 && over == 0 -> "Видео и так меньше ${size(limit)} — делить не нужно (но можно, нажми ＋)"
            over > 0 -> "⚠ Частей больше лимита: $over — добавь частей (＋)"
            else -> "Все части меньше ${size(limit)}. Размер — по данным самого файла, погрешность меньше 1%."
        }
        if (over > 0) hint.setTextColor(s.act.getColor(R.color.warn))
    }

    private fun row(p: SplitPlan.Part, tooBig: Boolean): View {
        val ctx = s.act
        val d = ctx.resources.displayMetrics.density
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_row)
            setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (6 * d).toInt() }
            addView(TextView(ctx).apply {
                text = "${p.number}"
                textSize = 15f; setTextColor(ctx.getColor(R.color.accent))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams((28 * d).toInt(), -2)
            })
            addView(TextView(ctx).apply {
                text = "${TimeUtil.format(p.fromUs)} – ${TimeUtil.format(p.toUs)}\n${TimeUtil.format(p.toUs - p.fromUs)}"
                textSize = 13f; setTextColor(ctx.getColor(R.color.text))
                typeface = android.graphics.Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            })
            addView(TextView(ctx).apply {
                text = "≈ " + size(p.bytes)
                textSize = 16f
                setTextColor(ctx.getColor(if (tooBig) R.color.danger else R.color.text))
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            })
        }
    }

    // ================================================================ cutting

    private fun start() {
        val p = plan ?: run { s.act.toast(if (scanning) "Подожди, ещё считаю размеры" else "Сначала выбери видео"); return }
        val uri = s.video.uri ?: return
        if (p.parts.size < 2) { s.act.toast("Одна часть — это само видео. Нажми ＋, чтобы разделить."); txtCount.bump(); return }
        if (p.parts.any { it.bytes > limit }) { s.act.toast("Есть части больше лимита — добавь частей"); hint.bump(); return }
        val need = p.parts.sumOf { it.bytes }
        val free = runCatching { StatFs(Environment.getExternalStorageDirectory().path).availableBytes }.getOrDefault(Long.MAX_VALUE)
        val go = {
            SplitState.start(s.act, SplitJob(uri, s.video.baseName, p.parts))
            showRunning()
        }
        if (free < need + 20L * 1_048_576) {
            AlertDialog.Builder(s.act)
                .setTitle("Мало места")
                .setMessage("Для частей нужно ${size(need)}, а свободно ${size(free)}. Освободи место или выбери другую папку в ⚙.")
                .setPositiveButton("Всё равно начать") { _, _ -> go() }
                .setNegativeButton("Отмена", null).show()
        } else go()
    }

    private fun showRunning() {
        val st = SplitState
        progress.visibility = View.VISIBLE
        progress.max = 1000
        val overall = if (st.parts == 0) 0f else ((st.part - 1).coerceAtLeast(0) + st.fraction) / st.parts
        progress.progress = (overall * 1000).toInt()
        btn.text = "Остановить"
        hint.setTextColor(s.act.getColor(R.color.text))
        hint.text = "Режу часть ${max(1, st.part)} из ${st.parts}… ${(overall * 100).toInt()}%\nМожно свернуть приложение — работа идёт в фоне."
    }

    private fun finished() {
        val st = SplitState
        progress.visibility = View.GONE
        btn.text = "Разделить"
        when {
            st.error != null -> { hint.setTextColor(s.act.getColor(R.color.danger)); hint.text = "Не получилось: ${st.error}" }
            st.cancel.get() -> { hint.setTextColor(s.act.getColor(R.color.textDim)); hint.text = "Остановлено. Готово частей: ${st.saved.size}" }
            else -> {
                hint.setTextColor(s.act.getColor(R.color.accent))
                hint.text = "✓ Готово: ${st.saved.size} частей в ${st.folder}" +
                    (if (st.droppedAudio.isNotEmpty()) "\nБез звука: звук ${st.droppedAudio.joinToString()} нельзя сохранить без перекодирования" else "")
                btn.bump()
            }
        }
    }

    // ================================================================ helpers

    /** Sizes the way Telegram counts them: its "2 GB" is 2000 MB of 1 048 576 bytes. */
    private fun size(b: Long): String {
        val mb = b / 1_048_576.0
        return when {
            mb >= 1000 -> "%.2f ГБ".format(mb / 1000)
            mb >= 10 -> "%.0f МБ".format(mb)
            mb >= 1 -> "%.1f МБ".format(mb)
            else -> "%.0f КБ".format(b / 1024.0)
        }
    }

    private fun sizeOf(uri: Uri): Long =
        if (uri.scheme == "file") File(uri.path!!).length()
        else runCatching {
            s.act.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        }.getOrNull() ?: 0L
}
