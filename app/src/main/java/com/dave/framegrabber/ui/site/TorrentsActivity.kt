package com.dave.framegrabber.ui.site

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.dave.framegrabber.R
import com.dave.framegrabber.data.AppTheme
import com.dave.framegrabber.torrent.TorrentEngine
import com.dave.framegrabber.ui.player.PlayerActivity
import com.dave.framegrabber.ui.tc
import com.dave.framegrabber.ui.toast
import com.dave.framegrabber.ui.widget.SynthwaveView
import java.io.File
import java.util.concurrent.Executors

/**
 * «Торренты»: the app's own torrent client. Add (.torrent file, magnet link, or from a tracker
 * page), pause / continue / delete one or all, see speed, peers and files, watch what's done.
 * Also opens .torrent files and magnet links from other apps.
 */
class TorrentsActivity : Activity() {

    companion object {
        private const val REQ_FILE = 1

        fun intent(ctx: Context) = Intent(ctx, TorrentsActivity::class.java)

        /** Files of a .torrent (videos ticked) → download the chosen ones. */
        fun choose(act: Activity, bytes: ByteArray, title: String, done: () -> Unit = {}) {
            val E = TorrentEngine
            val (name, files) = runCatching { E.files(bytes) }.getOrElse { act.toast("Торрент не читается: ${it.message}"); return }
            val videos = files.filter { E.isVideo(it.second) }
            val checked = BooleanArray(files.size) { i -> if (videos.isEmpty()) true else E.isVideo(files[i].second) }
            val labels = files.map { (_, p, sz) -> p.substringAfter('/').ifBlank { p } + "  ·  " + E.size(sz) }.toTypedArray()
            fun total() = files.filterIndexed { i, _ -> checked[i] }.sumOf { it.third }
            val dlg = AlertDialog.Builder(act)
                .setTitle("Что скачать?")
                .setMultiChoiceItems(labels, checked) { d, i, on -> checked[i] = on; (d as AlertDialog).setTitle("Выбрано: " + E.size(total())) }
                .setPositiveButton("Скачать") { _, _ ->
                    val sel = files.indices.filter { checked[it] }
                    if (sel.isEmpty()) { act.toast("Ничего не выбрано"); return@setPositiveButton }
                    runCatching { E.add(act, bytes, title.ifBlank { name }, sel) }
                        .onSuccess { act.toast("Качаю — всё в «Торренты»"); done() }
                        .onFailure { act.toast("Не удалось начать: ${it.message}") }
                }
                .setNegativeButton("Отмена", null)
                .create()
            dlg.show()
            dlg.setTitle("Выбрано: " + E.size(total()) + if (videos.isNotEmpty()) " (видео)" else "")
        }
    }

    private val d get() = resources.displayMetrics.density
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var list: LinearLayout
    private lateinit var summary: TextView
    private lateinit var chips: List<TextView>
    private var filter = 0   // 0 all, 1 downloading, 2 paused, 3 done
    private val refresh = Runnable { if (!isDestroyed) render() }
    private val onChange: () -> Unit = { list.removeCallbacks(refresh); list.postDelayed(refresh, 400) }

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
        if (Build.VERSION.SDK_INT >= 30) content.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars()); v.setPadding(b.left, b.top, b.right, b.bottom); insets
        }
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding((10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt(), (4 * d).toInt()) }
        fun icon(res: Int, desc: String, click: () -> Unit) = ImageButton(this, null, 0, R.style.IconBtn).apply {
            setImageResource(res); contentDescription = desc
            layoutParams = LinearLayout.LayoutParams((44 * d).toInt(), (44 * d).toInt()).apply { marginStart = (6 * d).toInt() }
            setOnClickListener { click() }
        }
        bar.addView(icon(R.drawable.ic_arrow_back, "Назад") { finish() })
        bar.addView(TextView(this).apply {
            text = "Торренты"; textSize = 19f; setTextColor(tc(R.color.text)); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding((12 * d).toInt(), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(icon(R.drawable.ic_pause, "Пауза / продолжить все") { toggleAll() })
        bar.addView(icon(R.drawable.ic_add, "Добавить торрент") { addMenu() })
        content.addView(bar)
        summary = TextView(this).apply { textSize = 12f; setTextColor(tc(R.color.textDim)); setPadding((18 * d).toInt(), 0, (18 * d).toInt(), (6 * d).toInt()) }
        content.addView(summary)
        // filters
        val row = LinearLayout(this).apply { setPadding((14 * d).toInt(), 0, (14 * d).toInt(), (6 * d).toInt()) }
        chips = listOf("Все", "Качаются", "Пауза", "Готово").mapIndexed { i, t ->
            TextView(this).apply {
                text = t; textSize = 13f; gravity = Gravity.CENTER
                setPadding((12 * d).toInt(), (6 * d).toInt(), (12 * d).toInt(), (6 * d).toInt())
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = (6 * d).toInt() }
                setOnClickListener { filter = i; render() }
                row.addView(this)
            }
        }
        content.addView(row)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((14 * d).toInt(), 0, (14 * d).toInt(), (28 * d).toInt()) }
        content.addView(ScrollView(this).apply { addView(list); clipToPadding = false }, LinearLayout.LayoutParams(-1, 0, 1f))
        runCatching { TorrentEngine.restore(this) }
        render()
        handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIncoming(intent) }
    override fun onStart() { super.onStart(); TorrentEngine.listeners += onChange; render() }
    override fun onStop() { TorrentEngine.listeners -= onChange; super.onStop() }
    override fun onDestroy() { io.shutdownNow(); super.onDestroy() }

    // ================================================================ adding

    /** A .torrent or magnet link opened from another app (or shared to this one). */
    private fun handleIncoming(i: Intent?) {
        val data = i?.data ?: (i?.getStringExtra(Intent.EXTRA_TEXT)?.let { t -> Regex("""magnet:\?\S+""").find(t)?.value?.let(Uri::parse) }) ?: return
        i?.data = null
        if (data.scheme == "magnet") addMagnet(data.toString()) else addFile(data)
    }

    private fun addMenu() {
        AlertDialog.Builder(this).setTitle("Добавить торрент").setItems(arrayOf(
            "Файл .torrent с телефона", "Magnet-ссылка", "Найти на трекере (вкладка «Сайт»)",
        )) { _, w ->
            when (w) {
                0 -> runCatching {
                    @Suppress("DEPRECATION")
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                        .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/x-bittorrent", "application/octet-stream")), REQ_FILE)
                }
                1 -> magnetDialog()
                2 -> { setResult(RESULT_OK, Intent().putExtra(SavedActivity.EXTRA_SEARCH, "")); finish() }
            }
        }.show()
    }

    private fun magnetDialog() {
        val clip = runCatching { getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString() }.getOrNull()
        val e = EditText(this, null, 0, R.style.Input).apply {
            hint = "magnet:?xt=urn:btih:…"
            if (clip?.startsWith("magnet:") == true) setText(clip)
        }
        val box = FrameLayout(this).apply { setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), 0); addView(e) }
        AlertDialog.Builder(this).setTitle("Magnet-ссылка").setView(box)
            .setPositiveButton("Добавить") { _, _ -> e.text.toString().trim().takeIf { it.startsWith("magnet:") }?.let { addMagnet(it) } ?: toast("Это не magnet-ссылка") }
            .setNegativeButton("Отмена", null).show()
    }

    private fun addMagnet(m: String) {
        val wait = android.app.ProgressDialog(this).apply { setMessage("Получаю данные раздачи… (до 40 с)"); setCancelable(true); show() }
        io.execute {
            val r = runCatching { TorrentEngine.magnetBytes(applicationContext, m) ?: error("нет ответа — возможно, нет раздающих") }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                wait.dismiss()
                r.onSuccess { b -> choose(this, b, Regex("""[?&]dn=([^&]+)""").find(m)?.groupValues?.get(1)?.let { Uri.decode(it) } ?: "") { render() } }
                    .onFailure { toast("Не удалось: ${it.message}") }
            }
        }
    }

    private fun addFile(u: Uri) {
        val r = runCatching { contentResolver.openInputStream(u)?.use { it.readBytes() } ?: error("не открывается") }
        r.onSuccess { choose(this, it, "") { render() } }.onFailure { toast("Не удалось открыть: ${it.message}") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_FILE && resultCode == RESULT_OK) data?.data?.let { addFile(it) }
    }

    // ================================================================ the list

    private fun toggleAll() {
        val active = TorrentEngine.jobs.filter { !it.finished && !it.paused }
        if (active.isNotEmpty()) { active.forEach { TorrentEngine.pause(it.hash) }; toast("Всё на паузе") }
        else {
            val paused = TorrentEngine.jobs.filter { !it.finished && it.paused }
            paused.forEach { TorrentEngine.resume(it.hash) }
            toast(if (paused.isEmpty()) "Нечего продолжать" else "Продолжаю: ${paused.size}")
        }
        render()
    }

    private fun render() {
        val E = TorrentEngine
        chips.forEachIndexed { i, c ->
            c.setBackgroundResource(if (i == filter) R.drawable.bg_chip_on else R.drawable.bg_chip)
            c.setTextColor(tc(if (i == filter) R.color.text else R.color.textDim))
        }
        val all = E.jobs.toList()
        val active = all.filter { !it.finished && !it.paused }
        summary.text = if (all.isEmpty()) "" else
            "Всего ${all.size} · качаются ${active.size} · ↓ ${E.size(active.sumOf { it.rate.toLong() })}/с · готово ${all.count { it.finished }}"
        list.removeAllViews()
        val shown = all.filter {
            when (filter) { 1 -> !it.finished && !it.paused; 2 -> it.paused && !it.finished; 3 -> it.finished; else -> true }
        }
        if (shown.isEmpty()) {
            list.addView(TextView(this).apply {
                text = if (all.isEmpty()) "Торрентов пока нет.\n\nНажми ＋ вверху: файл .torrent, magnet-ссылка, или найди раздачу во вкладке «Сайт» и нажми «⬇ Скачать видео».\n\nМожно открыть .torrent или magnet-ссылку из другого приложения — выбери FrameGrabber."
                       else "Здесь пусто"
                textSize = 14f; setTextColor(tc(R.color.textDim)); setLineSpacing(0f, 1.15f)
                setPadding((4 * d).toInt(), (24 * d).toInt(), (4 * d).toInt(), 0)
            })
            return
        }
        shown.forEach { list.addView(card(it)) }
    }

    private fun card(j: TorrentEngine.Job): View {
        val E = TorrentEngine
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding((14 * d).toInt(), (12 * d).toInt(), (10 * d).toInt(), (10 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * d).toInt() }
            isClickable = true
            setOnClickListener { details(j) }
        }
        c.addView(TextView(this).apply { text = j.title.ifBlank { j.name }; textSize = 15f; setTextColor(tc(R.color.text)); maxLines = 3 })
        val pct = (j.progress * 100).toInt()
        c.addView(TextView(this).apply {
            text = when {
                j.error != null -> "Ошибка: ${j.error}"
                j.finished -> "✓ Готово · ${E.size(j.wanted)}"
                j.paused -> "⏸ Пауза · $pct% · ${E.size(j.done)} из ${E.size(j.wanted)}"
                else -> "${j.state} · $pct% · ${E.size(j.done)} из ${E.size(j.wanted)}"
            }
            textSize = 12f; setTextColor(tc(if (j.finished) R.color.accent else R.color.textDim)); setPadding(0, (4 * d).toInt(), 0, 0)
        })
        if (!j.finished) {
            c.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000; progress = (j.progress * 1000).toInt()
                progressTintList = ColorStateList.valueOf(tc(if (j.paused) R.color.textDim else R.color.primaryBright))
                layoutParams = LinearLayout.LayoutParams(-1, (6 * d).toInt()).apply { topMargin = (6 * d).toInt() }
            })
            if (!j.paused) c.addView(TextView(this).apply {
                val left = if (j.rate > 0) (j.wanted - j.done) / j.rate.coerceAtLeast(1) else -1
                text = "↓ ${E.size(j.rate.toLong())}/с · раздают ${j.seeds} · пиров ${j.peers}" + if (left > 0) " · осталось ~${eta(left)}" else ""
                textSize = 12f; setTextColor(tc(R.color.textDim)); setPadding(0, (4 * d).toInt(), 0, 0)
            })
        }
        val btns = LinearLayout(this).apply { gravity = Gravity.END; setPadding(0, (8 * d).toInt(), 0, 0) }
        fun btn(t: String, click: () -> Unit) = btns.addView(Button(this, null, 0, R.style.BtnSecondary).apply {
            text = t; textSize = 13f; gravity = Gravity.CENTER
            minHeight = (38 * d).toInt(); minimumHeight = (38 * d).toInt()
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = (6 * d).toInt() }
            setOnClickListener { click() }
        })
        when {
            j.finished -> j.files.firstOrNull()?.let { f -> btn("▶ Смотреть") { play(f) } }
            j.paused -> btn("▶ Продолжить") { E.resume(j.hash); render() }
            else -> btn("⏸ Пауза") { E.pause(j.hash); render() }
        }
        btn("🗑") { delete(j) }
        c.addView(btns)
        return c
    }

    private fun eta(s: Long) = when {
        s >= 3600 -> "${s / 3600} ч ${(s % 3600) / 60} мин"
        s >= 60 -> "${s / 60} мин"
        else -> "$s с"
    }

    private fun play(f: File) = startActivity(PlayerActivity.intent(this, Uri.fromFile(f), -1, f.nameWithoutExtension))

    private fun delete(j: TorrentEngine.Job) {
        AlertDialog.Builder(this).setTitle(j.title.ifBlank { j.name })
            .setItems(arrayOf("Удалить торрент и скачанные файлы", "Убрать из списка, файлы оставить")) { _, i ->
                TorrentEngine.remove(j.hash, deleteFiles = i == 0); render()
            }.setNegativeButton("Отмена", null).show()
    }

    /** Status, numbers, files (tap a finished video to watch). */
    private fun details(j: TorrentEngine.Job) {
        val E = TorrentEngine
        val info = buildString {
            append("Состояние: ").append(if (j.finished) "готово" else if (j.paused) "пауза" else j.state).append('\n')
            append("Скачано: ${E.size(j.done)} из ${E.size(j.wanted)} (${(j.progress * 100).toInt()}%)\n")
            if (!j.finished) append("Скорость: ${E.size(j.rate.toLong())}/с · раздают ${j.seeds} · пиров ${j.peers}\n")
            append("Папка: Movies/Torrents/${j.name}")
        }
        val b = AlertDialog.Builder(this).setTitle(j.title.ifBlank { j.name }).setMessage(info)
        if (j.files.isNotEmpty()) b.setPositiveButton("Файлы (${j.files.size})") { _, _ ->
            AlertDialog.Builder(this).setTitle("Открыть в плеере").setItems(j.files.map { "${it.name} · ${E.size(it.length())}" }.toTypedArray()) { _, i -> play(j.files[i]) }.show()
        }
        b.setNeutralButton(if (j.finished) "Удалить…" else if (j.paused) "Продолжить" else "Пауза") { _, _ ->
            when { j.finished -> delete(j); j.paused -> { E.resume(j.hash); render() }; else -> { E.pause(j.hash); render() } }
        }
        b.setNegativeButton("Закрыть", null).show()
    }
}
