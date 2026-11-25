package com.dave.framegrabber.ui.main

import android.view.View
import android.widget.EditText
import android.widget.TextView
import com.dave.framegrabber.R
import com.dave.framegrabber.core.FrameEngine
import com.dave.framegrabber.core.TimeUtil
import com.dave.framegrabber.ui.bump
import com.dave.framegrabber.ui.onTextChanged
import com.dave.framegrabber.ui.toast

/**
 * «Отрезок»: start/end (empty = the whole video). One card per page (range_card.xml is included in
 * «Клипы» and «Инструменты»), so each page keeps its own range. [onChange] runs when it's edited.
 */
class RangeCard(private val s: MainScreen, val card: View, private val onChange: () -> Unit = {}) {

    private val editFrom = card.findViewById<EditText>(R.id.editFrom)
    private val editTo = card.findViewById<EditText>(R.id.editTo)
    private val txtLen = card.findViewById<TextView>(R.id.txtRangeLen)

    init {
        // ⏮ / ⏭ take the current frame
        card.findViewById<View>(R.id.btnMarkIn).setOnClickListener {
            if (s.video.requireEngine() == null) return@setOnClickListener
            editFrom.setText(TimeUtil.format(s.video.currentUs)); editFrom.bump()
        }
        card.findViewById<View>(R.id.btnMarkOut).setOnClickListener {
            if (s.video.requireEngine() == null) return@setOnClickListener
            editTo.setText(TimeUtil.format(s.video.currentUs)); editTo.bump()
        }
        editFrom.onTextChanged { changed() }
        editTo.onTextChanged { changed() }
    }

    private fun changed() {
        updateLength()
        onChange()
    }

    /** New video: forget the old range. */
    fun onVideoChanged() {
        editFrom.setText("")
        editTo.setText("")
        updateLength()
    }

    /** Range clamped to the video, without validation (may be empty/reversed). */
    fun bounds(e: FrameEngine): Pair<Long, Long> {
        val from = TimeUtil.parse(editFrom.text.toString())?.coerceIn(0L, e.lastUs) ?: 0L
        val to = TimeUtil.parse(editTo.text.toString())?.coerceIn(0L, e.lastUs) ?: e.lastUs
        return from to to
    }

    /** Valid range, or a message + null. */
    fun require(e: FrameEngine): Pair<Long, Long>? {
        val (from, to) = bounds(e)
        if (to <= from) { s.act.toast("Конец отрезка должен быть позже начала"); return null }
        return from to to
    }

    private fun updateLength() {
        val e = s.video.engine ?: run { txtLen.text = "всё видео"; return }
        val (from, to) = bounds(e)
        txtLen.text = when {
            editFrom.text.isBlank() && editTo.text.isBlank() -> "всё видео · ${TimeUtil.format(e.durationUs)}"
            to > from -> "длина ${TimeUtil.format(to - from)}"
            else -> "проверь отрезок"
        }
    }
}
