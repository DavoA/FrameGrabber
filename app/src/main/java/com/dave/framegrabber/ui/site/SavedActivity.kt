package com.dave.framegrabber.ui.site

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
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
import com.dave.framegrabber.data.AppTheme
import com.dave.framegrabber.site.Lab
import com.dave.framegrabber.site.SiteStore
import com.dave.framegrabber.site.WatchJob
import com.dave.framegrabber.ui.tc
import com.dave.framegrabber.ui.toast
import com.dave.framegrabber.ui.widget.SynthwaveView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * «Сохранённое» of the tracker: bookmarked torrents and followed models (a daily check for
 * their new torrents, with a notification). Tap a bookmark → its page; tap a model → the search.
 */
class SavedActivity : Activity() {

    companion object {
        /** Result: search the tracker for this. */
        const val EXTRA_SEARCH = "siteSearch"
        private const val EXTRA_TAB = "tab"
        fun intent(ctx: Context, models: Boolean = false) = Intent(ctx, SavedActivity::class.java).putExtra(EXTRA_TAB, if (models) 1 else 0)
    }

    private val d get() = resources.displayMetrics.density
    private val io = Executors.newFixedThreadPool(3)
    private lateinit var store: SiteStore
    private lateinit var body: LinearLayout
    private lateinit var tabs: List<TextView>
    private var tab = 0
    private val dateFmt = SimpleDateFormat("d MMM, HH:mm", Locale("ru"))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        store = SiteStore(this)
        tab = intent.getIntExtra(EXTRA_TAB, 0)
        val root = FrameLayout(this).apply { setBackgroundColor(tc(R.color.bg)) }
        val bg = SynthwaveView(this).apply { visibility = View.GONE }
        root.addView(bg, FrameLayout.LayoutParams(-1, -1))
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        AppTheme.apply(this, bg, AppTheme.get(this), animate = false)
        if (Build.VERSION.SDK_INT >= 30) content.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars()); v.setPadding(b.left, b.top, b.right, b.bottom); insets
        }
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding((10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt(), (6 * d).toInt()) }
        bar.addView(ImageButton(this, null, 0, R.style.IconBtn).apply {
            setImageResource(R.drawable.ic_arrow_back); contentDescription = "Назад"
            layoutParams = LinearLayout.LayoutParams((44 * d).toInt(), (44 * d).toInt())
            setOnClickListener { finish() }
        })
        bar.addView(TextView(this).apply {
            text = "Сохранённое"; textSize = 18f; setTextColor(tc(R.color.text)); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding((12 * d).toInt(), 0, 0, 0)
        })
        content.addView(bar)
        // Закладки | Модели
        val seg = LinearLayout(this).apply {
            setBackgroundResource(R.drawable.bg_segment_group)
            setPadding((3 * d).toInt(), (3 * d).toInt(), (3 * d).toInt(), (3 * d).toInt())
        }
        tabs = listOf("★ Закладки", "👁 Модели").mapIndexed { i, t ->
            TextView(this).apply {
                text = t; gravity = Gravity.CENTER; textSize = 14f
                setPadding(0, (9 * d).toInt(), 0, (9 * d).toInt())
                setOnClickListener { tab = i; render() }
                seg.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
            }
        }
        content.addView(seg, LinearLayout.LayoutParams(-1, -2).apply { setMargins((14 * d).toInt(), 0, (14 * d).toInt(), (8 * d).toInt()) })
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((14 * d).toInt(), 0, (14 * d).toInt(), (24 * d).toInt()) }
        content.addView(ScrollView(this).apply { addView(body); clipToPadding = false }, LinearLayout.LayoutParams(-1, 0, 1f))
        render()
    }

    override fun onResume() { super.onResume(); if (::body.isInitialized) render() }
    override fun onDestroy() { io.shutdownNow(); super.onDestroy() }

    private fun render() {
        tabs.forEachIndexed { i, t ->
            t.setBackgroundResource(if (i == tab) R.drawable.bg_segment else 0)
            t.setTextColor(tc(if (i == tab) R.color.text else R.color.textDim))
        }
        body.removeAllViews()
        if (tab == 0) bookmarks() else models()
    }

    // ================================================================ bookmarks

    private fun bookmarks() {
        val list = store.bookmarks
        if (list.isEmpty()) { hint("Закладок пока нет.\nНа странице раздачи нажми ☆ вверху — она появится здесь."); return }
        list.forEach { b ->
            val row = LinearLayout(this).apply {
                setBackgroundResource(R.drawable.bg_card)
                setPadding((8 * d).toInt(), (8 * d).toInt(), (10 * d).toInt(), (8 * d).toInt())
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * d).toInt() }
                isClickable = true
                setOnClickListener {
                    @Suppress("DEPRECATION")
                    startActivityForResult(TorrentActivity.intent(this@SavedActivity, b.id, b.title), 1)
                }
                setOnLongClickListener {
                    AlertDialog.Builder(this@SavedActivity).setMessage("Убрать из закладок?\n\n${b.title}")
                        .setPositiveButton("Убрать") { _, _ -> store.toggleBookmark(b); render() }
                        .setNegativeButton("Отмена", null).show()
                    true
                }
            }
            val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundResource(R.drawable.bg_thumb); clipToOutline = true }
            row.addView(iv, LinearLayout.LayoutParams((60 * d).toInt(), (80 * d).toInt()))
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((10 * d).toInt(), 0, 0, 0) }
            col.addView(TextView(this).apply { text = b.title; textSize = 14f; setTextColor(tc(R.color.text)); maxLines = 3 })
            col.addView(TextView(this).apply {
                text = listOf(b.size, "добавлено " + dateFmt.format(Date(b.added))).filter { it.isNotBlank() }.joinToString(" · ")
                textSize = 12f; setTextColor(tc(R.color.textDim)); setPadding(0, (4 * d).toInt(), 0, 0)
            })
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            body.addView(row)
            b.poster?.let { url ->
                Lab.cached(url, 300)?.let { iv.setImageBitmap(it) } ?: io.execute {
                    val bmp = Lab.image(applicationContext, url, 300)
                    runOnUiThread { if (!isDestroyed && bmp != null) iv.setImageBitmap(bmp) }
                }
            }
        }
        hint("Удержи закладку, чтобы убрать её.")
    }

    // ================================================================ followed models

    private fun models() {
        // add a model
        val add = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, (4 * d).toInt(), 0, (6 * d).toInt()) }
        val edit = EditText(this, null, 0, R.style.Input).apply {
            hint = "Имя модели"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            imeOptions = EditorInfo.IME_ACTION_DONE; gravity = Gravity.CENTER_VERTICAL
        }
        add.addView(edit, LinearLayout.LayoutParams(0, -2, 1f))
        val addBtn = Button(this, null, 0, R.style.BtnSecondary).apply {
            text = "Следить"; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = (8 * d).toInt() }
        }
        add.addView(addBtn)
        val doAdd = {
            val n = edit.text.toString().trim()
            if (n.length >= 2 && !store.isFollowed(n)) { follow(this, n); render() }
        }
        addBtn.setOnClickListener { doAdd() }
        edit.setOnEditorActionListener { _, _, _ -> doAdd(); true }
        body.addView(add)

        val list = store.followed
        if (list.isEmpty()) {
            hint("Добавь моделей — раз в день приложение проверит трекер и пришлёт уведомление, если у них вышла новая раздача.\nТакже можно нажать «☆ Следить» у имени на странице раздачи.")
            return
        }
        list.forEach { f ->
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_card)
                setPadding((14 * d).toInt(), (10 * d).toInt(), (6 * d).toInt(), (10 * d).toInt())
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * d).toInt() }
                isClickable = true
                setOnClickListener {
                    store.seen(f.name)
                    setResult(RESULT_OK, Intent().putExtra(EXTRA_SEARCH, f.name)); finish()
                }
            }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(this).apply { text = f.name; textSize = 16f; setTextColor(tc(R.color.text)); typeface = Typeface.DEFAULT_BOLD })
            col.addView(TextView(this).apply {
                text = when {
                    f.fresh.isNotEmpty() -> "Новых раздач: ${f.fresh.size} — «${f.freshTitles.firstOrNull() ?: ""}»"
                    f.checked > 0 -> "Проверено ${dateFmt.format(Date(f.checked))} · нового нет"
                    else -> "Ещё не проверялось"
                }
                textSize = 12f; maxLines = 2
                setTextColor(tc(if (f.fresh.isNotEmpty()) R.color.accent else R.color.textDim))
                setPadding(0, (3 * d).toInt(), 0, 0)
            })
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            if (f.fresh.isNotEmpty()) row.addView(TextView(this).apply {
                text = f.fresh.size.toString(); textSize = 12f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.bg_count)
                setPadding((8 * d).toInt(), (2 * d).toInt(), (8 * d).toInt(), (2 * d).toInt())
            })
            row.addView(ImageButton(this, null, 0, R.style.IconBtn).apply {
                setImageResource(R.drawable.ic_close); contentDescription = "Не следить"
                layoutParams = LinearLayout.LayoutParams((40 * d).toInt(), (40 * d).toInt()).apply { marginStart = (6 * d).toInt() }
                setOnClickListener {
                    AlertDialog.Builder(this@SavedActivity).setMessage("Не следить за «${f.name}»?")
                        .setPositiveButton("Да") { _, _ -> store.toggleFollow(f.name); WatchJob.schedule(this@SavedActivity); render() }
                        .setNegativeButton("Нет", null).show()
                }
            })
            body.addView(row)
        }
        body.addView(Button(this, null, 0, R.style.BtnPrimary).apply {
            text = "Проверить сейчас"; gravity = Gravity.CENTER
            setOnClickListener {
                isEnabled = false; text = "Проверяю…"
                io.execute {
                    val r = runCatching { WatchJob.check(applicationContext) }
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        r.onSuccess { news -> toast(if (news.isEmpty()) "Новых раздач нет" else "Новые раздачи: " + news.joinToString { "${it.first} (${it.second.size})" }) }
                            .onFailure { e -> toast(if (e is Lab.NeedLogin) "Сначала войди на сайте (вкладка «Сайт»)" else "Не удалось: ${e.message}") }
                        render()
                    }
                }
            }
        })
        hint("Проверка идёт сама раз в день (когда есть интернет). Нажми на модель — откроется поиск её раздач.")
    }

    private fun hint(t: String) = body.addView(TextView(this).apply {
        text = t; textSize = 13f; setTextColor(tc(R.color.textDim)); setLineSpacing(0f, 1.15f)
        setPadding((4 * d).toInt(), (14 * d).toInt(), (4 * d).toInt(), 0)
    })

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        // «открыть на сайте» from a bookmark: pass it on to the site tab
        if (resultCode == RESULT_OK && data != null) { setResult(RESULT_OK, data); finish() }
    }
}

/** Follow a model (asks once for notifications). */
fun follow(act: Activity, name: String): Boolean {
    val on = SiteStore(act).toggleFollow(name)
    WatchJob.schedule(act)
    if (on && Build.VERSION.SDK_INT >= 33 &&
        act.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
        act.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 79)
    act.toast(if (on) "Слежу за «$name» — пришлю уведомление о новых раздачах" else "Больше не слежу за «$name»")
    return on
}
