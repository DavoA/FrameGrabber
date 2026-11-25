package com.dave.framegrabber.ui.telegram

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
import com.dave.framegrabber.telegram.ChannelImport
import com.dave.framegrabber.telegram.PostLog
import com.dave.framegrabber.telegram.TgStore
import com.dave.framegrabber.ui.toast
import com.dave.framegrabber.telegram.TagLibrary
import com.dave.framegrabber.ui.widget.SynthwaveView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * Channel statistics from the posts published by the app: totals and posts per week, tags (most
 * used and never used), studios and models (how many posts, when last), the latest posts.
 */
class StatsActivity : Activity() {

    private val d get() = resources.displayMetrics.density
    private val day = 24 * 3600 * 1000L
    private val dateFmt = SimpleDateFormat("d MMM yyyy", Locale("ru"))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        val root = FrameLayout(this).apply { setBackgroundColor(getColor(R.color.bg)) }
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

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * d).toInt(), 0, (14 * d).toInt(), (20 * d).toInt())
        }
        content.addView(ScrollView(this).apply { addView(body); clipToPadding = false },
            LinearLayout.LayoutParams(-1, 0, 1f))
        if (Build.VERSION.SDK_INT >= 30) content.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom); insets
        }

        val posts = PostLog(this).posts
        body.addView(importCard(posts))
        if (posts.isEmpty()) return
        body.addView(totals(posts))
        body.addView(tags(posts))
        body.addView(names("Студии", group(posts) { listOf(it.studio) }))
        body.addView(names("Модели", group(posts) { it.models }))
        body.addView(recent(posts))
    }

    // ================================================================ reading the channel

    private var importing = false

    /** «Посты канала»: read the public channel page (all posts, then only new ones). */
    private fun importCard(posts: List<PostLog.Post>): View = card("Посты канала").apply {
        val store = TgStore(this@StatsActivity)
        val fromChannel = posts.count { it.source == "channel" }
        val fromApp = posts.size - fromChannel
        val sp = getSharedPreferences("telegram", MODE_PRIVATE)
        val at = sp.getLong("importAt", 0)
        val status = text(
            if (at == 0L) "Статистика пока только по постам из приложения ($fromApp). Загрузи посты канала, чтобы посчитать всё, что уже есть в канале."
            else "Из канала: $fromChannel · из приложения: $fromApp\nОбновлено: ${SimpleDateFormat("d MMM, HH:mm", Locale("ru")).format(Date(at))}",
            13f, R.color.textDim)
        addView(status)
        val btn = android.widget.Button(this@StatsActivity, null, 0, R.style.BtnPrimary).apply {
            text = if (at == 0L) "Загрузить все посты канала" else "Обновить"
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (12 * d).toInt() }
        }
        addView(btn)
        btn.setOnClickListener {
            if (importing) return@setOnClickListener
            val name = store.chatUsername.ifEmpty { sp.getString("importChannel", "") ?: "" }
            if (name.isEmpty()) askChannel { n -> sp.edit().putString("importChannel", n).apply(); runImport(n, at == 0L, btn, status) }
            else runImport(name, at == 0L, btn, status)
        }
    }

    private fun askChannel(onOk: (String) -> Unit) {
        val edit = android.widget.EditText(this).apply { hint = "@канал или t.me/канал"; isSingleLine = true; setTextColor(getColor(R.color.text)) }
        android.app.AlertDialog.Builder(this)
            .setTitle("Какой канал?")
            .setMessage("Нужен публичный канал (со ссылкой t.me/…).")
            .setView(FrameLayout(this).apply { setPadding((20 * d).toInt(), 0, (20 * d).toInt(), 0); addView(edit) })
            .setPositiveButton("OK") { _, _ ->
                val n = ChannelImport.channelName(edit.text.toString())
                if (n == null) toast("Не похоже на имя канала") else onOk(n)
            }
            .setNegativeButton("Отмена", null).show()
    }

    private fun runImport(channel: String, full: Boolean, btn: android.widget.Button, status: TextView) {
        importing = true
        btn.isEnabled = false; btn.alpha = 0.5f
        btn.text = "Загружаю…"
        status.setTextColor(getColor(R.color.text))
        status.text = "Читаю t.me/$channel…"
        val app = applicationContext
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Thread {
            val log = PostLog(app)
            val r = runCatching {
                val stop = if (full) 0L else log.lastChannelId()
                val msgs = ChannelImport(channel).readAll(stop) { total, oldest ->
                    runOnUiThread { if (!isDestroyed) status.text = "Прочитано сообщений: $total (дошёл до №$oldest)…" }
                    true
                }
                log.addFromChannel(ChannelImport.toPosts(channel, msgs))
            }
            runOnUiThread {
                importing = false
                if (isDestroyed) return@runOnUiThread
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                r.onSuccess { added ->
                    getSharedPreferences("telegram", MODE_PRIVATE).edit().putLong("importAt", System.currentTimeMillis()).apply()
                    toast(if (added == 0) "Новых постов нет" else "Добавлено постов: $added")
                    recreate()
                }.onFailure {
                    btn.isEnabled = true; btn.alpha = 1f; btn.text = "Попробовать снова"
                    status.setTextColor(getColor(R.color.danger))
                    status.text = when (it) {
                        is java.net.UnknownHostException, is java.net.SocketTimeoutException, is java.net.ConnectException ->
                            "Нет связи с t.me. Проверь интернет."
                        else -> it.message ?: it.javaClass.simpleName
                    }
                }
            }
        }.start()
    }

    // ================================================================ sections

    private fun totals(posts: List<PostLog.Post>): View = card("Всего").apply {
        val now = System.currentTimeMillis()
        val row = LinearLayout(this@StatsActivity).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(big(posts.size.toString(), "постов"), LinearLayout.LayoutParams(0, -2, 1f))
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

    private fun tags(posts: List<PostLog.Post>): View = card("Хэштеги").apply {
        val counts = posts.flatMap { it.tags }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
        val top = counts.take(15)
        val maxN = max(1, top.firstOrNull()?.value ?: 1)
        if (top.isEmpty()) addView(text("Посты были без хэштегов", 13f, R.color.textDim))
        top.forEach { (t, n) -> addView(barRow("#$t", n, maxN, "$n")) }
        if (counts.size > 15) addView(text("…и ещё ${counts.size - 15}", 12f, R.color.textDim).apply { setPadding(0, (4 * d).toInt(), 0, 0) })
        val used = counts.map { it.key }.toSet()
        val never = TagLibrary(this@StatsActivity).all.filter { it !in used }
        if (never.isNotEmpty()) {
            addView(text("Ни разу не использованы (${never.size}):", 12f, R.color.textDim).apply { setPadding(0, (12 * d).toInt(), 0, (4 * d).toInt()) })
            addView(text(never.take(40).joinToString(" ") { "#$it" } + if (never.size > 40) " …" else "", 13f, R.color.text))
        }
    }

    /** Posts by name (case-insensitive), shown with the name as last written. */
    private fun group(posts: List<PostLog.Post>, names: (PostLog.Post) -> List<String>): List<Pair<String, List<PostLog.Post>>> {
        val byKey = LinkedHashMap<String, MutableList<PostLog.Post>>()
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
    private fun names(title: String, groups: List<Pair<String, List<PostLog.Post>>>): View = card("$title (${groups.size})").apply {
        val sorted = groups.sortedWith(compareByDescending<Pair<String, List<PostLog.Post>>> { it.second.size }.thenByDescending { it.second.last().time })
        val maxN = max(1, sorted.firstOrNull()?.second?.size ?: 1)
        sorted.take(30).forEach { (name, list) ->
            addView(barRow(name, list.size, maxN, "${list.size} · ${dateFmt.format(Date(list.last().time))}"))
        }
        if (sorted.size > 30) {
            val more = text("Показать все (${sorted.size}) ›", 14f, R.color.accent).apply { setPadding(0, (10 * d).toInt(), 0, (4 * d).toInt()) }
            more.setOnClickListener {
                removeView(more)
                sorted.drop(30).forEach { (name, list) ->
                    addView(barRow(name, list.size, maxN, "${list.size} · ${dateFmt.format(Date(list.last().time))}"))
                }
            }
            addView(more)
        }
    }

    private fun recent(posts: List<PostLog.Post>): View = card("Последние посты").apply {
        posts.takeLast(20).reversed().forEach { p ->
            val row = LinearLayout(this@StatsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_row)
                setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (6 * d).toInt() }
            }
            row.addView(text("${p.studio} - ${p.models.joinToString(" & ")}", 14f, R.color.text).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
            row.addView(text(dateFmt.format(Date(p.time)) + (if (p.link != null) "  · открыть ›" else ""), 12f, R.color.accent))
            if (p.tags.isNotEmpty()) row.addView(text(p.tags.joinToString(" ") { "#$it" }, 12f, R.color.textDim).apply {
                maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            })
            p.link?.let { link -> row.setOnClickListener { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) } } }
            addView(row)
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
        text = s; textSize = size; setTextColor(getColor(color))
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
}
