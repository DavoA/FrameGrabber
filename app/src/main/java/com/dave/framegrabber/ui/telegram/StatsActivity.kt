package com.dave.framegrabber.ui.telegram

import com.dave.framegrabber.ui.tc

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.dave.framegrabber.R
import com.dave.framegrabber.data.AppTheme
import com.dave.framegrabber.telegram.TagLibrary
import com.dave.framegrabber.tgclient.ChannelStats
import com.dave.framegrabber.tgclient.ChannelStats.Post
import com.dave.framegrabber.ui.widget.SynthwaveView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * Channel statistics from ALL the channel's posts, read through the app's Telegram login
 * («Канал» tab): totals and posts per week, tags (most used and never used), studios and models
 * (how many posts, how many with video, when last). Tap a name or a tag → the feed searches for it.
 */
class StatsActivity : Activity() {

    companion object {
        const val EXTRA_CHAT = "chat"
        const val EXTRA_TITLE = "title"
        /** Result: what to search in the feed. */
        const val EXTRA_SEARCH = "search"
    }

    private var chatId = 0L
    private lateinit var body: LinearLayout
    private lateinit var status: TextView

    private val d get() = resources.displayMetrics.density
    private val day = 24 * 3600 * 1000L
    private val dateFmt = SimpleDateFormat("d MMM yyyy", Locale("ru"))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        val root = FrameLayout(this).apply { setBackgroundColor(tc(R.color.bg)) }
        val bg = SynthwaveView(this).apply { visibility = View.GONE }
        root.addView(bg, FrameLayout.LayoutParams(-1, -1))
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        AppTheme.apply(this, bg, AppTheme.get(this), animate = false)

        // top bar
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding((10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt())
        }
        bar.addView(ImageButton(this, null, 0, R.style.IconBtn).apply {
            setImageResource(R.drawable.ic_arrow_back); contentDescription = "Назад"
            layoutParams = LinearLayout.LayoutParams((46 * d).toInt(), (46 * d).toInt())
            setOnClickListener { finish() }
        })
        bar.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * d).toInt(), 0, 0, 0)
            addView(text("Статистика канала", 20f, R.color.text, bold = true))
            addView(text("по всем постам канала", 12f, R.color.textDim))
        })
        content.addView(bar)

        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * d).toInt(), 0, (14 * d).toInt(), (20 * d).toInt())
        }
        content.addView(ScrollView(this).apply { addView(body); clipToPadding = false },
            LinearLayout.LayoutParams(-1, 0, 1f))
        if (Build.VERSION.SDK_INT >= 30) content.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom); insets
        }

        chatId = intent.getLongExtra(EXTRA_CHAT, 0)
        ((bar.getChildAt(1) as LinearLayout).getChildAt(1) as TextView).text = intent.getStringExtra(EXTRA_TITLE) ?: "все посты канала"
        status = text("", 13f, R.color.textDim).apply { setPadding((4 * d).toInt(), 0, 0, (10 * d).toInt()) }
        body.addView(status)
        val cached = ChannelStats.load(this, chatId)
        if (cached.isNotEmpty()) show(cached)
        refresh(first = cached.isEmpty())
    }

    /** Read the new messages (all of them the first time), then redraw. */
    private fun refresh(first: Boolean) {
        status.text = if (first) "Читаю канал… (первый раз дольше: все посты)" else "Проверяю новые посты…"
        val app = applicationContext
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Thread {
            val r = runCatching {
                ChannelStats.update(app, chatId) { n -> runOnUiThread { if (!isDestroyed) status.text = "Прочитано сообщений: $n…" } }
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                r.onSuccess { show(it); status.text = "Обновлено: ${SimpleDateFormat("d MMM, HH:mm", Locale("ru")).format(Date())}" }
                    .onFailure { status.setTextColor(tc(R.color.danger)); status.text = "Не удалось прочитать канал: ${it.message}" }
            }
        }.start()
    }

    private fun show(posts: List<Post>) {
        while (body.childCount > 1) body.removeViewAt(1)
        if (posts.isEmpty()) { body.addView(text("В канале пока нет постов с подписью.", 14f, R.color.textDim)); return }
        body.addView(totals(posts))
        body.addView(names("Модели", group(posts) { it.models }))
        body.addView(names("Студии", group(posts) { listOf(it.studio) }))
        body.addView(tags(posts))
    }

    private fun searchInFeed(q: String) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_SEARCH, q))
        finish()
    }

    // ================================================================ sections

    private fun totals(posts: List<Post>): View = card("Всего").apply {
        val now = System.currentTimeMillis()
        val row = LinearLayout(this@StatsActivity).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(big(posts.size.toString(), "постов"), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(big(posts.count { it.video }.toString(), "с видео"), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(big(posts.count { now - it.time < 7 * day }.toString(), "за 7 дней"), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(big(posts.count { now - it.time < 30 * day }.toString(), "за 30 дней"), LinearLayout.LayoutParams(0, -2, 1f))
        addView(row)

        // posts per week, last 8 weeks (oldest left)
        val weeks = IntArray(8)
        posts.forEach { p -> val w = ((now - p.time) / (7 * day)).toInt(); if (w in 0..7) weeks[7 - w]++ }
        val maxW = max(1, weeks.maxOrNull() ?: 1)
        addView(text("Постов по неделям (последние 8)", 12f, R.color.textDim).apply { setPadding(0, (14 * d).toInt(), 0, (6 * d).toInt()) })
        val bars = LinearLayout(this@StatsActivity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM
            layoutParams = LinearLayout.LayoutParams(-1, (90 * d).toInt())
        }
        weeks.forEachIndexed { i, n ->
            val col = LinearLayout(this@StatsActivity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL }
            col.addView(text(if (n > 0) n.toString() else "", 11f, R.color.textDim).apply { gravity = Gravity.CENTER })
            col.addView(View(this@StatsActivity).apply {
                setBackgroundResource(if (i == 7) R.drawable.bg_chip_on else R.drawable.bg_chip)
            }, LinearLayout.LayoutParams((22 * d).toInt(), max((4 * d).toInt(), (n * 64 * d / maxW).toInt())))
            bars.addView(col, LinearLayout.LayoutParams(0, -1, 1f))
        }
        addView(bars)
        addView(text("Первый пост: ${dateFmt.format(Date(posts.first().time))} · последний: ${dateFmt.format(Date(posts.last().time))}",
            12f, R.color.textDim).apply { setPadding(0, (10 * d).toInt(), 0, 0) })
    }

    private fun tags(posts: List<Post>): View = card("Хэштеги").apply {
        val counts = posts.flatMap { it.tags }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
        val top = counts.take(15)
        val maxN = max(1, top.firstOrNull()?.value ?: 1)
        if (top.isEmpty()) addView(text("Посты были без хэштегов", 13f, R.color.textDim))
        top.forEach { (t, n) -> addView(barRow("#$t", n, maxN, "$n").apply { setOnClickListener { searchInFeed("#$t") } }) }
        if (counts.size > 15) addView(text("…и ещё ${counts.size - 15}", 12f, R.color.textDim).apply { setPadding(0, (4 * d).toInt(), 0, 0) })
        val used = counts.map { it.key }.toSet()
        val never = TagLibrary(this@StatsActivity).all.filter { it !in used }
        if (never.isNotEmpty()) {
            addView(text("Ни разу не использованы (${never.size}):", 12f, R.color.textDim).apply { setPadding(0, (12 * d).toInt(), 0, (4 * d).toInt()) })
            addView(text(never.take(40).joinToString(" ") { "#$it" } + if (never.size > 40) " …" else "", 13f, R.color.text))
        }
    }

    /** Posts by name (case-insensitive), shown with the name as last written. */
    private fun group(posts: List<Post>, names: (Post) -> List<String>): List<Pair<String, List<Post>>> {
        val byKey = LinkedHashMap<String, MutableList<Post>>()
        val shown = HashMap<String, String>()
        posts.forEach { p ->
            names(p).map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.forEach { n ->
                byKey.getOrPut(n.lowercase()) { mutableListOf() } += p
                shown[n.lowercase()] = n
            }
        }
        return byKey.map { (k, v) -> shown.getValue(k) to v }
    }

    /** Studios or models: how many posts each, and when last. */
    private fun names(title: String, groups: List<Pair<String, List<Post>>>): View = card("$title (${groups.size})").apply {
        val sorted = groups.sortedWith(compareByDescending<Pair<String, List<Post>>> { it.second.size }.thenByDescending { it.second.last().time })
        val maxN = max(1, sorted.firstOrNull()?.second?.size ?: 1)
        val row = { name: String, list: List<Post> ->
            val v = list.count { it.video }
            barRow(name, list.size, maxN, "${list.size} пост. · видео $v · ${dateFmt.format(Date(list.last().time))}").apply {
                setOnClickListener { searchInFeed(name) }
            }
        }
        if (sorted.isEmpty()) addView(text("Нет постов в формате «Студия - Модель»", 13f, R.color.textDim))
        else addView(text("Нажми на имя — лента найдёт все его посты", 12f, R.color.textDim).apply { setPadding(0, 0, 0, (6 * d).toInt()) })
        sorted.take(30).forEach { (name, list) -> addView(row(name, list)) }
        if (sorted.size > 30) {
            val more = text("Показать все (${sorted.size}) ›", 14f, R.color.accent).apply { setPadding(0, (10 * d).toInt(), 0, (4 * d).toInt()) }
            more.setOnClickListener {
                removeView(more)
                sorted.drop(30).forEach { (name, list) -> addView(row(name, list)) }
            }
            addView(more)
        }
    }

    // ================================================================ helpers

    private fun card(title: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_card)
        val p = (16 * d).toInt()
        setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (12 * d).toInt() }
        addView(text(title, 16f, R.color.text, bold = true).apply { setPadding(0, 0, 0, (10 * d).toInt()) })
    }

    private fun big(value: String, label: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        addView(text(value, 26f, R.color.accent, bold = true).apply { gravity = Gravity.CENTER })
        addView(text(label, 12f, R.color.textDim).apply { gravity = Gravity.CENTER })
    }

    /** "name ▇▇▇▇▇   12" */
    private fun barRow(name: String, n: Int, maxN: Int, right: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, (4 * d).toInt(), 0, (4 * d).toInt())
        val top = LinearLayout(this@StatsActivity).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(text(name, 14f, R.color.text).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(text(right, 12f, R.color.textDim))
        addView(top)
        val track = FrameLayout(this@StatsActivity).apply { setBackgroundResource(R.drawable.bg_chip) }
        addView(track, LinearLayout.LayoutParams(-1, (6 * d).toInt()).apply { topMargin = (3 * d).toInt() })
        track.post {
            track.addView(View(this@StatsActivity).apply { setBackgroundResource(R.drawable.bg_chip_on) },
                FrameLayout.LayoutParams(max((6 * d).toInt(), track.width * n / maxN), -1))
        }
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(tc(color))
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
}
