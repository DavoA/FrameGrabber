package com.dave.framegrabber.ui.telegram

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.dave.framegrabber.R
import com.dave.framegrabber.telegram.PostCaption
import com.dave.framegrabber.telegram.TgStore
import com.dave.framegrabber.ui.toast

/**
 * ⚙ → «Хэштеги»: the list of all hashtags. Add, rename (tap), delete (🗑), reorder (drag ≡).
 * The order here is the order in posts. The field on top also filters the list.
 */
class HashtagsDialog(private val act: Activity, private val onChanged: () -> Unit = {}) {

    private val store = TgStore(act)
    private val tags = store.hashtags.toMutableList()
    private val d = act.resources.displayMetrics.density
    private lateinit var listBox: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var title: TextView

    fun show() {
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * d).toInt(), (18 * d).toInt(), (20 * d).toInt(), (8 * d).toInt())
        }
        title = TextView(act).apply {
            textSize = 20f; setTextColor(act.getColor(R.color.text))
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }
        root.addView(title)
        root.addView(TextView(act).apply {
            text = "Порядок здесь — порядок в постах. Тяни за ≡, чтобы переставить; нажми на тег, чтобы переименовать."
            textSize = 12f; setTextColor(act.getColor(R.color.textDim))
            setPadding(0, (4 * d).toInt(), 0, (12 * d).toInt())
        })

        val addRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        input = EditText(act).apply {
            hint = "#новый тег или поиск"
            setBackgroundResource(R.drawable.bg_input)
            setTextColor(act.getColor(R.color.text)); setHintTextColor(act.getColor(R.color.textDim))
            textSize = 15f; isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE
            minHeight = (46 * d).toInt()
            setPadding((12 * d).toInt(), 0, (12 * d).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val addBtn = Button(act, null, 0, R.style.BtnPrimary).apply {
            text = "＋"
            textSize = 20f
            layoutParams = LinearLayout.LayoutParams((52 * d).toInt(), (46 * d).toInt()).apply { marginStart = (8 * d).toInt() }
            setPadding(0, 0, 0, 0)
        }
        addRow.addView(input); addRow.addView(addBtn)
        root.addView(addRow)

        listBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(0, (10 * d).toInt(), 0, 0) }
        scroll = ScrollView(act).apply {
            addView(listBox)
            layoutParams = LinearLayout.LayoutParams(-1, (act.resources.displayMetrics.heightPixels * 0.5f).toInt())
        }
        root.addView(scroll)

        addBtn.setOnClickListener { add() }
        input.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_DONE) { add(); true } else false }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = rebuild()
        })

        rebuild()
        AlertDialog.Builder(act)
            .setView(root)
            .setPositiveButton("Готово", null)
            .setOnDismissListener { onChanged() }
            .show()
    }

    private fun save() {
        store.hashtags = tags.toList()
        title.text = "Хэштеги (${tags.size})"
    }

    private fun add() {
        val raw = input.text.toString()
        if (raw.isBlank()) return
        // several at once: "#a #b", "a, b"; "big studio" alone is one tag (big_studio)
        val parts = raw.split('#', ',', ';').mapNotNull { PostCaption.normalizeTag(it) }
        if (parts.isEmpty()) { act.toast("Хэштег — это буквы, цифры и _"); return }
        var added = 0
        for (t in parts) {
            if (tags.any { it.equals(t, ignoreCase = true) }) continue
            tags += t; added++
        }
        if (added == 0) act.toast("Такой тег уже есть") else save()
        input.setText("")
        rebuild()
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun rebuild() {
        save()
        listBox.removeAllViews()
        val q = input.text.toString().trim().trimStart('#').lowercase()
        val filtering = q.isNotEmpty()
        if (tags.isEmpty()) {
            listBox.addView(TextView(act).apply {
                text = "Пока нет хэштегов. Напиши тег сверху и нажми ＋\n(можно сразу несколько: #tag1 #tag2)"
                setTextColor(act.getColor(R.color.textDim)); gravity = Gravity.CENTER
                setPadding(0, (24 * d).toInt(), 0, (24 * d).toInt())
            })
            return
        }
        for (t in tags) {
            if (filtering && !t.lowercase().contains(q)) continue
            listBox.addView(row(t, draggable = !filtering))
        }
        if (filtering && listBox.childCount == 0) {
            listBox.addView(TextView(act).apply {
                text = "Нет такого тега — нажми ＋, чтобы добавить #${PostCaption.normalizeTag(q) ?: q}"
                setTextColor(act.getColor(R.color.textDim)); gravity = Gravity.CENTER
                setPadding(0, (20 * d).toInt(), 0, (20 * d).toInt())
            })
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun row(tag: String, draggable: Boolean): View {
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_row)
            layoutParams = LinearLayout.LayoutParams(-1, (50 * d).toInt()).apply { bottomMargin = (6 * d).toInt() }
            setPadding((4 * d).toInt(), 0, 0, 0)
        }
        val handle = ImageView(act).apply {
            setImageResource(R.drawable.ic_drag)
            imageTintList = ColorStateList.valueOf(act.getColor(if (draggable) R.color.accent else R.color.stroke))
            scaleType = ImageView.ScaleType.CENTER
            layoutParams = LinearLayout.LayoutParams((44 * d).toInt(), -1)
            contentDescription = "Переставить"
        }
        val text = TextView(act).apply {
            this.text = "#$tag"
            textSize = 15f; setTextColor(act.getColor(R.color.text))
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { rename(tag) }
        }
        val del = ImageButton(act).apply {
            setImageResource(R.drawable.ic_delete)
            imageTintList = ColorStateList.valueOf(act.getColor(R.color.textDim))
            background = null
            layoutParams = LinearLayout.LayoutParams((48 * d).toInt(), -1)
            contentDescription = "Удалить"
            setOnClickListener {
                tags.remove(tag); rebuild(); act.toast("#$tag удалён")
            }
        }
        row.addView(handle); row.addView(text); row.addView(del)
        if (draggable) handle.setOnTouchListener(DragHelper(row))
        return row
    }

    /** Drag a row by its ≡ handle; neighbours swap with it as it passes their middle. */
    private inner class DragHelper(private val row: View) : View.OnTouchListener {
        private var lastY = 0f
        private var offset = 0f

        override fun onTouch(v: View, ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastY = ev.rawY; offset = 0f
                    scroll.requestDisallowInterceptTouchEvent(true)
                    row.animate().scaleX(1.03f).scaleY(1.03f).setDuration(120).start()
                    row.elevation = 10 * d
                }
                MotionEvent.ACTION_MOVE -> {
                    offset += ev.rawY - lastY; lastY = ev.rawY
                    val step = row.height + (6 * d)
                    val i = listBox.indexOfChild(row)
                    if (offset > step / 2 && i < listBox.childCount - 1) {
                        move(i, i + 1); offset -= step
                    } else if (offset < -step / 2 && i > 0) {
                        move(i, i - 1); offset += step
                    }
                    row.translationY = offset
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    scroll.requestDisallowInterceptTouchEvent(false)
                    row.animate().translationY(0f).scaleX(1f).scaleY(1f).setDuration(150)
                        .withEndAction { row.elevation = 0f }.start()
                    save()
                }
            }
            return true
        }

        /** Moves the neighbour (never the dragged row itself, so the touch isn't lost). */
        private fun move(from: Int, to: Int) {
            val other = listBox.getChildAt(to)
            listBox.removeView(other)
            listBox.addView(other, from)
            java.util.Collections.swap(tags, from, to)
        }
    }

    private fun rename(old: String) {
        val edit = EditText(act).apply {
            setText(old); setSelectAllOnFocus(true)
            setTextColor(act.getColor(R.color.text)); isSingleLine = true
        }
        val box = FrameLayout(act).apply {
            setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), 0); addView(edit)
        }
        AlertDialog.Builder(act)
            .setTitle("Переименовать #$old")
            .setView(box)
            .setPositiveButton("Сохранить") { _, _ ->
                val t = PostCaption.normalizeTag(edit.text.toString())
                when {
                    t == null -> act.toast("Хэштег — это буквы, цифры и _")
                    t != old && tags.any { it.equals(t, ignoreCase = true) && it != old } -> act.toast("#$t уже есть")
                    else -> { tags[tags.indexOf(old)] = t; rebuild() }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }
}
