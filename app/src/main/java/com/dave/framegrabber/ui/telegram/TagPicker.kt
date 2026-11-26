package com.dave.framegrabber.ui.telegram

import com.dave.framegrabber.ui.tc

import android.app.Activity
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.dave.framegrabber.R
import com.dave.framegrabber.telegram.PostCaption
import com.dave.framegrabber.telegram.TagLibrary
import com.dave.framegrabber.ui.telegram.Chips.setOn
import com.dave.framegrabber.ui.toast
import com.dave.framegrabber.ui.widget.FlowLayout

/**
 * Choosing hashtags for a post from a big library:
 *  - «Выбрано»: the chosen tags (in caption order), tap to remove;
 *  - «Частые»: the most posted ones;
 *  - categories that open and close (only the open ones show their tags);
 *  - search over everything, with «＋ создать» for a new tag (goes to «Мои»).
 */
class TagPicker(
    private val act: Activity,
    private val area: LinearLayout,
    private val search: EditText,
    private val onChanged: () -> Unit,
) {
    private var lib = TagLibrary(act)
    val selected = LinkedHashSet<String>()
    /** Open categories (by name). */
    private val open = HashSet<String>()
    private val d = act.resources.displayMetrics.density

    /** Live views to update without rebuilding everything. */
    private var selectedFlow: FlowLayout? = null
    private var selectedTitle: TextView? = null
    private val headerCounts = HashMap<String, TextView>()
    private val chipsByTag = HashMap<String, MutableList<TextView>>()

    init {
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = render()
        })
        search.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_DONE) { createFromSearch(); true } else false
        }
    }

    /** The library changed (edited in the manager): reload and redraw. */
    fun reload() {
        lib = TagLibrary(act)
        selected.retainAll { lib.contains(it) }
        render()
    }

    /** Chosen tags in caption order (library order). */
    fun ordered(): List<String> = lib.all.filter { it in selected }

    fun openCategory(name: String) { open += name; render() }

    fun toggle(tag: String) {
        if (!selected.remove(tag)) selected += tag
        chipsByTag[tag]?.forEach { it.setOn(tag in selected) }
        updateSelected()
        updateCounts()
        onChanged()
    }

    fun render() {
        area.removeAllViews()
        chipsByTag.clear(); headerCounts.clear()
        selectedFlow = null; selectedTitle = null
        val q = search.text.toString().trim().trimStart('#').lowercase()
        if (q.isNotEmpty()) { renderSearch(q); return }

        // chosen
        selectedTitle = label("").also { area.addView(it) }
        selectedFlow = FlowLayout(act).also { area.addView(it) }
        updateSelected()

        // most used
        val freq = lib.frequent(14)
        if (freq.isNotEmpty()) {
            area.addView(label("Частые"))
            area.addView(FlowLayout(act).apply { freq.forEach { addView(chip(it)) } })
        }

        // categories
        area.addView(label("Категории"))
        lib.categories.forEach { c -> area.addView(section(c)) }
        updateCounts()
    }

    private fun renderSearch(q: String) {
        val found = lib.all.filter { it.lowercase().contains(q) }
        area.addView(label(if (found.isEmpty()) "Ничего не найдено" else "Найдено: ${found.size}"))
        val flow = FlowLayout(act)
        found.take(150).forEach { flow.addView(chip(it, withCategory = true)) }
        val norm = PostCaption.normalizeTag(q)
        if (norm != null && !lib.contains(norm)) {
            flow.addView(Chips.make(act, "＋ создать #$norm").apply {
                setTextColor(act.tc(R.color.accent))
                setOnClickListener { createFromSearch() }
            })
        }
        area.addView(flow)
    }

    /** One category: a header that opens/closes it, and its tags when open. */
    private fun section(c: TagLibrary.Category): View {
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val isOpen = c.name in open
        val header = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_row)
            minimumHeight = (46 * d).toInt()
            setPadding((12 * d).toInt(), 0, (12 * d).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (6 * d).toInt() }
        }
        header.addView(TextView(act).apply {
            text = if (isOpen) "▾" else "▸"
            setTextColor(act.tc(R.color.accent)); textSize = 16f
            layoutParams = LinearLayout.LayoutParams((22 * d).toInt(), -2)
        })
        header.addView(TextView(act).apply {
            text = c.name; textSize = 15f; setTextColor(act.tc(R.color.text))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        val count = TextView(act).apply { textSize = 12f; setTextColor(act.tc(R.color.textDim)) }
        headerCounts[c.name] = count
        header.addView(count)
        box.addView(header)
        if (isOpen) {
            box.addView(FlowLayout(act).apply {
                setPadding((4 * d).toInt(), (8 * d).toInt(), 0, (6 * d).toInt())
                c.tags.forEach { addView(chip(it)) }
                if (c.tags.isEmpty()) addView(TextView(act).apply {
                    text = "Пусто — теги добавляются в ⚙ → Хэштеги"; setTextColor(act.tc(R.color.textDim)); textSize = 12f
                })
            })
        }
        header.setOnClickListener {
            if (!open.remove(c.name)) open += c.name
            render()
        }
        return box
    }

    private fun chip(tag: String, withCategory: Boolean = false): TextView {
        val label = if (withCategory) "#$tag" + (lib.categoryOf(tag)?.let { "  · ${it.name}" } ?: "") else "#$tag"
        return Chips.make(act, label, on = tag in selected).apply {
            setOnClickListener { toggle(tag) }
            chipsByTag.getOrPut(tag) { mutableListOf() } += this
        }
    }

    private fun updateSelected() {
        val flow = selectedFlow ?: return
        selectedTitle?.text = if (selected.isEmpty()) "Выбрано: ничего" else "Выбрано: ${selected.size}  (нажми, чтобы убрать)"
        flow.removeAllViews()
        ordered().forEach { t ->
            flow.addView(Chips.make(act, "#$t  ✕", on = true).apply { setOnClickListener { toggle(t) } })
        }
        flow.visibility = if (selected.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun updateCounts() {
        lib.categories.forEach { c ->
            val n = c.tags.count { it in selected }
            headerCounts[c.name]?.apply {
                text = if (n > 0) "$n из ${c.tags.size}" else "${c.tags.size}"
                setTextColor(act.tc(if (n > 0) R.color.accent else R.color.textDim))
            }
        }
    }

    /** The search text becomes a new tag in «Мои», already chosen. */
    private fun createFromSearch() {
        val t = PostCaption.normalizeTag(search.text.toString()) ?: return
        val existing = lib.all.firstOrNull { it.equals(t, ignoreCase = true) }
        if (existing == null) { lib.add(t, TagLibrary.MINE); lib.save() }
        selected += existing ?: t
        search.setText("")   // re-renders
        onChanged()
        act.toast(if (existing == null) "#$t добавлен в «${TagLibrary.MINE}»" else "#$existing выбран")
    }

    private fun label(s: String) = TextView(act).apply {
        text = s; textSize = 12f; setTextColor(act.tc(R.color.textDim))
        letterSpacing = 0.05f
        setPadding(0, (14 * d).toInt(), 0, (6 * d).toInt())
    }
}
