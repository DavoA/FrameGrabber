package com.dave.framegrabber.ui.telegram

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import com.dave.framegrabber.R
import com.dave.framegrabber.data.AppTheme
import com.dave.framegrabber.telegram.PostCaption
import com.dave.framegrabber.telegram.TagLibrary
import com.dave.framegrabber.ui.toast
import com.dave.framegrabber.ui.widget.SynthwaveView
import kotlin.math.max

/**
 * ⚙ → «Хэштеги»: the library by category.
 *  - a category opens with a tap; ✎ renames / moves / deletes it;
 *  - inside: drag ≡ to reorder, tap a tag to rename / move to another category / delete,
 *    the field at the bottom adds tags (several at once: "#a #b");
 *  - search over all tags; «⋮» → bring back the standard tags, open/close all.
 * The order here (categories, then tags) is the order of tags in posts.
 */
class TagsActivity : Activity() {

    private lateinit var lib: TagLibrary
    private lateinit var box: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var search: EditText
    private lateinit var sub: TextView
    private val open = HashSet<String>()
    private val d get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        setContentView(R.layout.activity_tags)
        AppTheme.apply(this, findViewById<SynthwaveView>(R.id.bgView), AppTheme.get(this), animate = false)
        lib = TagLibrary(this)
        box = findViewById(R.id.catsBox)
        scroll = findViewById(R.id.scroll)
        search = findViewById(R.id.editSearch)
        sub = findViewById(R.id.txtSub)
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnAddCat).setOnClickListener { addCategory() }
        findViewById<View>(R.id.btnMenu).setOnClickListener { menu(it) }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = render()
        })
        if (Build.VERSION.SDK_INT >= 30) findViewById<View>(R.id.content).setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, max(bars.bottom, ime.bottom))
            insets
        }
        render()
    }

    private fun changed() {
        lib.save()
        render()
    }

    private fun render() {
        val y = scroll.scrollY
        box.removeAllViews()
        sub.text = "${lib.all.size} тегов в ${lib.categories.size} категориях"
        val q = search.text.toString().trim().trimStart('#').lowercase()
        if (q.isNotEmpty()) {
            val found = lib.categories.flatMap { c -> c.tags.filter { it.lowercase().contains(q) }.map { it to c } }
            if (found.isEmpty()) box.addView(hint("Ничего не найдено. Добавь тег в нужную категорию — поле внизу открытой категории."))
            found.forEach { (t, c) -> box.addView(tagRow(t, c, null, showCategory = true)) }
            return
        }
        lib.categories.forEach { c -> box.addView(section(c)) }
        scroll.post { scroll.scrollTo(0, y) }
    }

    // ================================================================ categories

    private fun section(c: TagLibrary.Category): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (8 * d).toInt() }
        }
        val isOpen = c.name in open
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_row)
            minimumHeight = (52 * d).toInt()
            setPadding((12 * d).toInt(), 0, 0, 0)
        }
        header.addView(TextView(this).apply {
            text = if (isOpen) "▾" else "▸"; textSize = 18f; setTextColor(getColor(R.color.accent))
            layoutParams = LinearLayout.LayoutParams((24 * d).toInt(), -2)
        })
        header.addView(TextView(this).apply {
            text = c.name; textSize = 16f; setTextColor(getColor(R.color.text))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        header.addView(TextView(this).apply {
            text = c.tags.size.toString(); textSize = 13f; setTextColor(getColor(R.color.textDim))
        })
        header.addView(iconBtn(R.drawable.ic_edit, "Категория: действия") { categoryActions(c) })
        header.setOnClickListener { if (!open.remove(c.name)) open += c.name; render() }
        wrap.addView(header)

        if (isOpen) {
            val list = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((10 * d).toInt(), (6 * d).toInt(), 0, 0)
            }
            c.tags.forEach { t -> list.addView(tagRow(t, c, list, showCategory = false)) }
            wrap.addView(list)
            wrap.addView(addRow(c))
        }
        return wrap
    }

    private fun categoryActions(c: TagLibrary.Category) {
        val i = lib.categories.indexOf(c)
        val items = arrayOf("Переименовать", "Поднять выше", "Опустить ниже", "Удалить категорию")
        AlertDialog.Builder(this).setTitle(c.name).setItems(items) { _, which ->
            when (which) {
                0 -> ask("Название категории", c.name) { n ->
                    if (n.isBlank()) return@ask
                    if (lib.categories.any { it !== c && it.name == n }) { toast("Такая категория уже есть"); return@ask }
                    if (c.name in open) { open.remove(c.name); open += n }
                    c.name = n; changed()
                }
                1 -> if (i > 0) { java.util.Collections.swap(lib.categories, i, i - 1); changed() }
                2 -> if (i < lib.categories.size - 1) { java.util.Collections.swap(lib.categories, i, i + 1); changed() }
                3 -> AlertDialog.Builder(this)
                    .setMessage("Удалить категорию «${c.name}» и все её теги (${c.tags.size})?")
                    .setPositiveButton("Удалить") { _, _ -> lib.categories.remove(c); changed() }
                    .setNegativeButton("Отмена", null).show()
            }
        }.show()
    }

    private fun addCategory() {
        ask("Новая категория", "") { n ->
            if (n.isBlank()) return@ask
            if (lib.categories.any { it.name == n }) { toast("Такая категория уже есть"); return@ask }
            lib.categories += TagLibrary.Category(n, mutableListOf())
            open += n
            changed()
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun menu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "Вернуть стандартные теги")
            menu.add(0, 2, 1, "Развернуть все")
            menu.add(0, 3, 2, "Свернуть все")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> { val n = lib.restoreDefaults(); render(); toast(if (n == 0) "Все стандартные теги уже на месте" else "Добавлено тегов: $n") }
                    2 -> { open += lib.categories.map { c -> c.name }; render() }
                    3 -> { open.clear(); render() }
                }
                true
            }
        }.show()
    }

    // ================================================================ tags

    @SuppressLint("ClickableViewAccessibility")
    private fun tagRow(t: String, c: TagLibrary.Category, list: LinearLayout?, showCategory: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_row)
            layoutParams = LinearLayout.LayoutParams(-1, (46 * d).toInt()).apply { bottomMargin = (5 * d).toInt() }
        }
        val handle = ImageView(this).apply {
            setImageResource(R.drawable.ic_drag)
            imageTintList = ColorStateList.valueOf(getColor(if (list != null) R.color.accent else R.color.stroke))
            scaleType = ImageView.ScaleType.CENTER
            layoutParams = LinearLayout.LayoutParams((42 * d).toInt(), -1)
            contentDescription = "Переставить"
        }
        row.addView(handle)
        row.addView(TextView(this).apply {
            text = "#$t" + if (showCategory) "   · ${c.name}" else ""
            textSize = 15f; setTextColor(getColor(R.color.text))
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
            maxLines = 1
            setOnClickListener { tagActions(t, c) }
        })
        row.addView(iconBtn(R.drawable.ic_delete, "Удалить") {
            c.tags.remove(t); changed(); toast("#$t удалён")
        })
        if (list != null) handle.setOnTouchListener(Drag(row, list, c.tags))
        return row
    }

    private fun tagActions(t: String, c: TagLibrary.Category) {
        AlertDialog.Builder(this).setTitle("#$t").setItems(arrayOf("Переименовать", "Перенести в другую категорию", "Удалить")) { _, w ->
            when (w) {
                0 -> ask("Переименовать #$t", t) { raw ->
                    val n = PostCaption.normalizeTag(raw)
                    when {
                        n == null -> toast("Хэштег — это буквы, цифры и _")
                        n != t && lib.contains(n) -> toast("#$n уже есть")
                        else -> { c.tags[c.tags.indexOf(t)] = n; changed() }
                    }
                }
                1 -> {
                    val others = lib.categories.filter { it !== c }
                    AlertDialog.Builder(this).setTitle("Перенести #$t в…").setItems(others.map { it.name }.toTypedArray()) { _, k ->
                        c.tags.remove(t); others[k].tags += t; changed(); toast("#$t → «${others[k].name}»")
                    }.show()
                }
                2 -> { c.tags.remove(t); changed() }
            }
        }.show()
    }

    /** "＋ тег" at the bottom of an open category. */
    private fun addRow(c: TagLibrary.Category): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((10 * d).toInt(), (2 * d).toInt(), 0, (4 * d).toInt())
        }
        val input = EditText(this).apply {
            hint = "＋ тег в «${c.name}»"
            setBackgroundResource(R.drawable.bg_input)
            setTextColor(getColor(R.color.text)); setHintTextColor(getColor(R.color.textDim))
            textSize = 14f; isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE
            minHeight = (44 * d).toInt()
            setPadding((12 * d).toInt(), 0, (12 * d).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        fun add() {
            val parts = input.text.toString().split('#', ',', ';').mapNotNull { PostCaption.normalizeTag(it) }
            if (parts.isEmpty()) { toast("Хэштег — это буквы, цифры и _"); return }
            val dup = parts.filter { lib.contains(it) }
            parts.forEach { lib.add(it, c.name) }
            changed()
            if (dup.isNotEmpty()) toast("Уже есть: " + dup.joinToString(" ") { t -> "#$t (${lib.categoryOf(t)?.name})" })
        }
        input.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_DONE) { add(); true } else false }
        row.addView(input)
        row.addView(iconBtn(R.drawable.ic_add, "Добавить") { add() })
        return row
    }

    /** Drag a tag row by ≡ inside its category; neighbours move as it passes them. */
    private inner class Drag(private val row: View, private val list: LinearLayout, private val tags: MutableList<String>) : View.OnTouchListener {
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
                    val step = row.height + 5 * d
                    val i = list.indexOfChild(row)
                    if (offset > step / 2 && i < list.childCount - 1) { move(i, i + 1); offset -= step }
                    else if (offset < -step / 2 && i > 0) { move(i, i - 1); offset += step }
                    row.translationY = offset
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    scroll.requestDisallowInterceptTouchEvent(false)
                    row.animate().translationY(0f).scaleX(1f).scaleY(1f).setDuration(150)
                        .withEndAction { row.elevation = 0f }.start()
                    lib.save()
                }
            }
            return true
        }

        private fun move(from: Int, to: Int) {
            val other = list.getChildAt(to)
            list.removeView(other)
            list.addView(other, from)
            java.util.Collections.swap(tags, from, to)
        }
    }

    // ================================================================ helpers

    private fun iconBtn(icon: Int, desc: String, onClick: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(getColor(R.color.textDim))
        background = null
        contentDescription = desc
        layoutParams = LinearLayout.LayoutParams((46 * d).toInt(), (46 * d).toInt())
        setOnClickListener { onClick() }
    }

    private fun hint(s: String) = TextView(this).apply {
        text = s; setTextColor(getColor(R.color.textDim)); gravity = Gravity.CENTER
        setPadding((12 * d).toInt(), (24 * d).toInt(), (12 * d).toInt(), (24 * d).toInt())
    }

    private fun ask(title: String, value: String, onOk: (String) -> Unit) {
        val edit = EditText(this).apply {
            setText(value); setSelectAllOnFocus(true); isSingleLine = true
            setTextColor(getColor(R.color.text))
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(FrameLayout(this).apply { setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), 0); addView(edit) })
            .setPositiveButton("OK") { _, _ -> onOk(edit.text.toString().trim()) }
            .setNegativeButton("Отмена", null)
            .show()
        edit.requestFocus()
    }
}
