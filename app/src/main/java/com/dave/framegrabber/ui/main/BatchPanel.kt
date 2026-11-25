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
import com.dave.framegrabber.batch.BatchJob
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
 * Tab «Пачка»: many frames at once (every N seconds or N frames evenly), optionally skipping
 * blurry and duplicate frames. The work runs in [com.dave.framegrabber.batch.BatchService],
 * this panel only starts it and shows progress.
 */
class BatchPanel(private val s: MainScreen) {

    companion object {
        private const val MAX_BATCH = 2000
    }

    private val radioMode = s.h<RadioGroup>(R.id.radioBatch)
    private val lblValue = s.h<TextView>(R.id.lblBatch)
    private val editValue = s.h<EditText>(R.id.editBatch)
    private val swSkipBlur = s.h<Switch>(R.id.swSkipBlur)
    private val swSkipDup = s.h<Switch>(R.id.swSkipDup)
    private val txtHint = s.h<TextView>(R.id.txtBatchHint)
    private val btnStart = s.h<Button>(R.id.btnBatch)
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
        if (BatchState.running) showRunning()
    }

    private fun showMode() {
        if (byCount) { lblValue.text = "Сколько кадров"; editValue.setText(s.prefs.batchN) }
        else { lblValue.text = "Интервал, сек"; editValue.setText(s.prefs.batchInterval) }
    }

    /** Frame times for the batch, or null (with a message unless [quiet]). */
    private fun times(e: FrameEngine, quiet: Boolean): List<Long>? {
        fun fail(msg: String): List<Long>? { if (!quiet) s.act.toast(msg); return null }
        val (from, to) = s.range.bounds(e)
        if (to < from) return fail("Конец отрезка должен быть позже начала")
        val value = editValue.text.toString().replace(',', '.').toDoubleOrNull()
        return if (!byCount) {
            if (value == null || value <= 0) return fail("Укажи интервал больше 0")
            val step = (value * 1_000_000).toLong().coerceAtLeast(e.frameUs)
            val count = (to - from) / step + 1
            if (count > MAX_BATCH) return fail("Получится $count кадров — максимум $MAX_BATCH. Увеличь интервал.")
            (0 until count).map { from + it * step }
        } else {
            val n = value?.toInt() ?: 0
            if (n < 1 || n > MAX_BATCH) return fail("Количество: от 1 до $MAX_BATCH")
            if (n == 1) listOf(from + (to - from) / 2)
            else (0 until n).map { from + (to - from) * it / (n - 1) }
        }
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
                uri, s.video.baseName, times, spec, s.capDir, s.prefs.namePattern,
                swSkipBlur.isChecked, swSkipDup.isChecked, s.format.sharpest
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
