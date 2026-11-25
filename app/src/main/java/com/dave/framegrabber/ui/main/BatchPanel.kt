package com.dave.framegrabber.ui.main

import android.Manifest
import android.animation.ObjectAnimator
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Build
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import com.dave.framegrabber.R
import android.net.Uri
import com.dave.framegrabber.batch.BatchJob
import com.dave.framegrabber.batch.BatchRule
import com.dave.framegrabber.batch.BatchVideo
import com.dave.framegrabber.data.Naming
import com.dave.framegrabber.data.Output
import com.dave.framegrabber.batch.BatchState
import com.dave.framegrabber.core.FrameEngine
import com.dave.framegrabber.ui.anim
import com.dave.framegrabber.ui.hideAnimated
import com.dave.framegrabber.ui.hideKeyboard
import com.dave.framegrabber.ui.onTextChanged
import com.dave.framegrabber.ui.pulse
import com.dave.framegrabber.ui.showAnimated
import com.dave.framegrabber.ui.toast
import kotlin.math.max

/**
 * «Пачка кадров» (page «Инструменты»): many frames at once (every N seconds or N frames evenly), optionally skipping
 * blurry and duplicate frames. The work runs in [com.dave.framegrabber.batch.BatchService],
 * this panel only starts it and shows progress.
 */
class BatchPanel(private val s: MainScreen) {

    private val radioMode = s.h<RadioGroup>(R.id.radioBatch)
    private val lblValue = s.h<TextView>(R.id.lblBatch)
    private val editValue = s.h<EditText>(R.id.editBatch)
    private val swSkipBlur = s.h<Switch>(R.id.swSkipBlur)
    private val swSkipDup = s.h<Switch>(R.id.swSkipDup)
    private val txtHint = s.h<TextView>(R.id.txtBatchHint)
    private val btnStart = s.h<Button>(R.id.btnBatch)
    private val btnMulti = s.h<Button>(R.id.btnBatchMulti)
    private val progress = s.h<ProgressBar>(R.id.progressBatch)
    private val txtProgress = s.h<TextView>(R.id.txtProgress)
    private var pulse: ObjectAnimator? = null

    private val byCount get() = radioMode.checkedRadioButtonId == R.id.batchCount

    init {
        // saved values first, then listeners
        val p = s.prefs
        radioMode.check(if (p.batchCount) R.id.batchCount else R.id.batchInterval)
        showMode()
        swSkipBlur.isChecked = p.skipBlurry
        swSkipDup.isChecked = p.skipDupes

        radioMode.setOnCheckedChangeListener { _, _ ->
            p.batchCount = byCount
            showMode()
            editValue.alpha = 0f
            editValue.anim().alpha(1f).setDuration(250).start()
            updateHint()
        }
        editValue.onTextChanged {
            if (byCount) p.batchN = it else p.batchInterval = it
            updateHint()
        }
        swSkipBlur.setOnCheckedChangeListener { _, on -> p.skipBlurry = on }
        swSkipDup.setOnCheckedChangeListener { _, on -> p.skipDupes = on }
        btnStart.setOnClickListener {
            if (BatchState.running) { BatchState.stop(s.act); btnStart.text = "Останавливаю…" } else start()
        }
        btnMulti.setOnClickListener {
            if (BatchState.running) { s.act.toast("Дождись, пока закончится текущая пачка"); return@setOnClickListener }
            if (rule(quiet = false) == null) return@setOnClickListener
            s.act.openBrowserMulti()
        }
        if (BatchState.running) showRunning()
    }

    private fun showMode() {
        if (byCount) { lblValue.text = "Сколько кадров"; editValue.setText(s.prefs.batchN) }
        else { lblValue.text = "Интервал, сек"; editValue.setText(s.prefs.batchInterval) }
    }

    /** "Every N sec" / "N frames" from the fields, or null (with a message unless [quiet]). */
    private fun rule(quiet: Boolean): BatchRule? {
        val value = editValue.text.toString().replace(',', '.').toDoubleOrNull()
        if (value == null || value <= 0) {
            if (!quiet) s.act.toast(if (byCount) "Укажи, сколько кадров" else "Укажи интервал больше 0")
            return null
        }
        return BatchRule(byCount, value)
    }

    /** Frame times for the batch, or null (with a message unless [quiet]). */
    private fun times(e: FrameEngine, quiet: Boolean): List<Long>? {
        val r = rule(quiet) ?: return null
        val (from, to) = s.rangeBatch.bounds(e)
        return r.times(from, to, e.frameUs).getOrElse { if (!quiet) s.act.toast(it.message ?: "Проверь значения"); null }
    }

    /** "Будет снято кадров: N" under the settings. */
    fun updateHint() {
        val e = s.video.engine
        txtHint.text = if (e == null) "Сначала выбери видео"
        else times(e, quiet = true)?.let { "Будет снято кадров: ${it.size}" } ?: "Проверь значения"
    }

    private fun start() {
        val e = s.video.requireEngine() ?: return
        val uri = s.video.uri ?: return
        val spec = s.format.readSpec() ?: return
        editValue.hideKeyboard()
        val times = times(e, quiet = false) ?: return
        val go = {
            askNotificationPermission()
            BatchState.start(s.act, BatchJob(
                listOf(BatchVideo(uri, s.video.baseName, times)), rule(quiet = true)!!, spec, s.prefs.namePattern,
                swSkipBlur.isChecked, swSkipDup.isChecked, s.format.sharpest, toGallery = false
            ))
            showRunning()
        }
        if (times.size > 300) {
            AlertDialog.Builder(s.act)
                .setMessage("Будет снято до ${times.size} кадров. Это может занять время — можно свернуть приложение, съёмка продолжится в фоне. Продолжить?")
                .setPositiveButton("Да") { _, _ -> go() }
                .setNegativeButton("Отмена", null)
                .show()
        } else go()
    }

    /** Videos chosen for «Несколько видео сразу»: confirm, then run the same settings over each whole video. */
    fun onVideosPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val rule = rule(quiet = false) ?: return
        val spec = s.format.readSpec() ?: return
        val videos = uris.map { BatchVideo(it, Naming.videoName(s.act, it), times = null) }
        val what = if (rule.byCount) "по ${rule.value.toInt()} кадров с каждого" else "каждые ${editValue.text} сек"
        val where = Output.describe(s.act)
        AlertDialog.Builder(s.act)
            .setTitle("${videos.size} видео")
            .setMessage(buildString {
                append(videos.joinToString("\n") { "• " + it.name })
                append("\n\nКадры: $what, всё видео целиком.")
                append("\nСохранятся в $where — своя папка на каждое видео, и в историю проектов.")
                append("\n\nМожно свернуть приложение, работа идёт в фоне.")
            })
            .setPositiveButton("Начать") { _, _ ->
                askNotificationPermission()
                BatchState.start(s.act, BatchJob(
                    videos, rule, spec, s.prefs.namePattern,
                    swSkipBlur.isChecked, swSkipDup.isChecked, s.format.sharpest, toGallery = true
                ))
                s.nav.show(NavController.Page.TOOLS)
                showRunning()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /** Android 13+: the progress notification needs permission. */
    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            s.act.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            s.act.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 77)
        }
    }

    private fun showRunning() {
        btnStart.text = "Остановить"
        btnStart.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_stop, 0, 0, 0)
        if (pulse == null) pulse = btnStart.pulse(0.72f, 650)
        txtProgress.visibility = View.VISIBLE
        txtProgress.text = "Подготовка… (можно свернуть приложение)"
        progress.max = max(1, BatchState.total)
        if (progress.visibility != View.VISIBLE) progress.showAnimated()
    }

    private val listener = object : BatchState.Listener {
        override fun onProgress() {
            val st = BatchState
            if (progress.visibility != View.VISIBLE) showRunning()
            progress.max = max(1, st.total)
            progress.setProgress(st.index, true)
            txtProgress.text = buildString {
                if (st.videos > 1) append("Видео ${st.video} из ${st.videos}: ${st.videoName}\n")
                append("Кадр ${st.index} из ${st.total}")
                if (st.blurry + st.dupes > 0) append(" · пропущено ${st.blurry + st.dupes}")
            }
        }

        override fun onFinished() {
            pulse?.cancel(); pulse = null
            btnStart.alpha = 1f
            btnStart.text = "Снять пачку кадров"
            btnStart.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_burst, 0, 0, 0)
            progress.hideAnimated(delay = 600, duration = 300)
            txtProgress.visibility = View.VISIBLE
            txtProgress.text = BatchState.summary ?: "Готово"
            if (BatchState.done > 0) s.grid.list.post { s.grid.list.smoothScrollToPositionFromTop(1, 0) }
        }
    }

    fun onResume() {
        BatchState.listener = listener
        if (BatchState.running) listener.onProgress()
        else if (pulse != null) listener.onFinished()   // finished while we were away
    }

    /** While away, the service shows a "done" notification instead of updating this screen. */
    fun onPause() { BatchState.listener = null }
}
