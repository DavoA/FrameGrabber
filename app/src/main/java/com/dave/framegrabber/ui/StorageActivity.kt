package com.dave.framegrabber.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.dave.framegrabber.R
import com.dave.framegrabber.data.AppTheme
import com.dave.framegrabber.data.CaptureStore
import com.dave.framegrabber.data.Prefs
import com.dave.framegrabber.data.Projects
import com.dave.framegrabber.torrent.TorrentEngine
import com.dave.framegrabber.ui.widget.SynthwaveView
import java.io.File
import java.util.concurrent.Executors

/**
 * «Место на телефоне»: what the app keeps and how much it takes — torrents, videos from Telegram,
 * clips / parts, GIFs and pictures, frames of projects, cache. Open a group, tick what to delete.
 */
class StorageActivity : Activity() {

    companion object {
        private const val REQ_DELETE = 1
        fun intent(ctx: Context) = Intent(ctx, StorageActivity::class.java)
    }

    /** One thing that can be deleted. */
    private class Entry(val name: String, val size: Long, val time: Long, val delete: Deleter)
    private sealed class Deleter {
        class Media(val uri: Uri) : Deleter()
        class Files(val file: File) : Deleter()
        class Torrent(val hash: String) : Deleter()
        class Project(val id: String) : Deleter()
    }
    private class Group(val title: String, val icon: String, val hint: String, val entries: List<Entry>) {
        val size get() = entries.sumOf { it.size }
    }

    private val d get() = resources.displayMetrics.density
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var body: LinearLayout
    private lateinit var free: TextView
    /** Deletes waiting for the system's «allow» (gallery files from before a reinstall). */
    private var afterSystemDelete: (() -> Unit)? = null

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
        bar.addView(ImageButton(this, null, 0, R.style.IconBtn).apply {
            setImageResource(R.drawable.ic_arrow_back); contentDescription = "Назад"
            layoutParams = LinearLayout.LayoutParams((44 * d).toInt(), (44 * d).toInt())
            setOnClickListener { finish() }
        })
        bar.addView(TextView(this).apply {
            text = "Место на телефоне"; textSize = 19f; setTextColor(tc(R.color.text)); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding((12 * d).toInt(), 0, 0, 0)
        })
        content.addView(bar)
        free = TextView(this).apply { textSize = 13f; setTextColor(tc(R.color.textDim)); setPadding((18 * d).toInt(), 0, (18 * d).toInt(), (6 * d).toInt()) }
        content.addView(free)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((14 * d).toInt(), 0, (14 * d).toInt(), (28 * d).toInt()) }
        content.addView(ScrollView(this).apply { addView(body); clipToPadding = false }, LinearLayout.LayoutParams(-1, 0, 1f))
        load()
    }

    override fun onDestroy() { io.shutdownNow(); super.onDestroy() }

    // ================================================================ reading

    private fun load() {
        body.removeAllViews()
        body.addView(ProgressBar(this), LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = (40 * d).toInt() })
        io.execute {
            val groups = runCatching { scan() }.getOrDefault(emptyList())
            runOnUiThread { if (!isDestroyed) show(groups) }
        }
    }

    private fun scan(): List<Group> {
        val out = ArrayList<Group>()
        // torrents (the app's own folder)
        TorrentEngine.restore(this)
        val tor = TorrentEngine.jobs.map { j ->
            val f = File(TorrentEngine.dir(this), j.name)
            Entry(j.title.ifBlank { j.name } + if (!j.finished) " (не докачан)" else "", sizeOf(f), 0, Deleter.Torrent(j.hash))
        }.toMutableList()
        // files in the torrents folder no torrent knows about any more
        val known = TorrentEngine.jobs.map { it.name }.toSet()
        TorrentEngine.dir(this).listFiles()?.filter { it.name !in known }?.forEach { f ->
            tor += Entry(f.name + " (без торрента)", sizeOf(f), f.lastModified(), Deleter.Files(f))
        }
        out += Group("Торренты", "⬇", "Скачанное в приложении (Movies/Torrents приложения)", tor)

        val movies = "${Environment.DIRECTORY_MOVIES}/${Prefs.ALBUM}/"
        val pics = "${Environment.DIRECTORY_PICTURES}/${Prefs.ALBUM}/"
        out += Group("Видео из Telegram", "📥", "Скачанные из канала и соединённые части (Movies/${Prefs.ALBUM}/Telegram)",
            media(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), "${MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf("${movies}Telegram/")))
        out += Group("Клипы и части видео", "🎬", "Вырезанные клипы и разрезанные части (Movies/${Prefs.ALBUM})",
            media(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} != ?", arrayOf("$movies%", "${movies}Telegram/")))
        out += Group("GIF и картинки", "🖼", "GIF, сохранённые кадры, листы кадров (Pictures/${Prefs.ALBUM})",
            media(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("$pics%")))
        // frames kept inside the app, by video
        val open = CaptureStore.project?.id
        out += Group("Кадры проектов", "🎞", "Кадры, снятые из видео (хранятся внутри приложения)",
            Projects.list(this).filter { it.id != open }.map { p ->
                Entry("${p.name} · кадров ${p.frames}", sizeOf(Projects.dir(this, p.id)), p.updated, Deleter.Project(p.id))
            })
        // caches
        val caches = listOfNotNull(cacheDir, externalCacheDir, File(getExternalFilesDir(null), "upload"), File(getExternalFilesDir(null), "export"), File(filesDir, "torrents/magnet"))
            .filter { it.exists() }.map { Entry("Кэш: " + it.name, sizeOf(it), 0, Deleter.Files(it)) }
        out += Group("Кэш", "🧹", "Временные файлы — можно чистить всегда", caches)
        return out
    }

    private fun media(table: Uri, where: String, args: Array<String>): List<Entry> {
        val out = ArrayList<Entry>()
        runCatching {
            contentResolver.query(table, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_ADDED), where, args, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
                while (c.moveToNext()) out += Entry(c.getString(1) ?: "?", c.getLong(2), c.getLong(3) * 1000, Deleter.Media(ContentUris.withAppendedId(table, c.getLong(0))))
            }
        }
        return out
    }

    private fun sizeOf(f: File): Long = if (!f.exists()) 0 else if (f.isFile) f.length() else f.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    // ================================================================ showing

    private fun show(groups: List<Group>) {
        body.removeAllViews()
        val st = runCatching { StatFs(Environment.getExternalStorageDirectory().path) }.getOrNull()
        val total = groups.sumOf { it.size }
        free.text = "Приложение занимает: ${size(total)}" + (st?.let { " · свободно на телефоне: ${size(it.availableBytes)} из ${size(it.totalBytes)}" } ?: "")
        groups.forEach { g ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_card)
                setPadding((14 * d).toInt(), (12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt())
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (10 * d).toInt() }
                isClickable = g.entries.isNotEmpty()
                setOnClickListener { pick(g) }
            }
            val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            top.addView(TextView(this).apply { text = "${g.icon}  ${g.title}"; textSize = 16f; setTextColor(tc(R.color.text)); typeface = Typeface.DEFAULT_BOLD },
                LinearLayout.LayoutParams(0, -2, 1f))
            top.addView(TextView(this).apply { text = size(g.size); textSize = 16f; setTextColor(tc(if (g.size > 0) R.color.accent else R.color.textDim)); typeface = Typeface.DEFAULT_BOLD })
            card.addView(top)
            // share of the whole
            if (total > 0) card.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000; progress = (g.size * 1000 / total).toInt()
                progressTintList = android.content.res.ColorStateList.valueOf(tc(R.color.primaryBright))
                layoutParams = LinearLayout.LayoutParams(-1, (5 * d).toInt()).apply { topMargin = (8 * d).toInt() }
            })
            card.addView(TextView(this).apply {
                text = g.hint + " · " + (if (g.entries.isEmpty()) "пусто" else "${g.entries.size} шт.")
                textSize = 12f; setTextColor(tc(R.color.textDim)); setPadding(0, (6 * d).toInt(), 0, 0)
            })
            if (g.entries.isNotEmpty()) {
                val row = LinearLayout(this).apply { gravity = Gravity.END; setPadding(0, (8 * d).toInt(), 0, 0) }
                row.addView(Button(this, null, 0, R.style.BtnSecondary).apply {
                    text = "Выбрать…"; textSize = 13f; gravity = Gravity.CENTER
                    minHeight = (38 * d).toInt(); minimumHeight = (38 * d).toInt()
                    setOnClickListener { pick(g) }
                })
                row.addView(Button(this, null, 0, R.style.BtnSecondary).apply {
                    text = "Удалить всё"; textSize = 13f; gravity = Gravity.CENTER
                    minHeight = (38 * d).toInt(); minimumHeight = (38 * d).toInt()
                    setTextColor(tc(R.color.danger))
                    layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = (8 * d).toInt() }
                    setOnClickListener { confirm(g.title, g.entries) }
                })
                card.addView(row)
            }
            body.addView(card)
        }
        body.addView(TextView(this).apply {
            text = "Видео в «Плеере» из твоих собственных папок здесь не считаются — только то, что сделало или скачало приложение."
            textSize = 12f; setTextColor(tc(R.color.textDim)); setPadding((4 * d).toInt(), (14 * d).toInt(), (4 * d).toInt(), 0)
        })
    }

    /** The group's items, biggest first; tick and delete. */
    private fun pick(g: Group) {
        val items = g.entries.sortedByDescending { it.size }
        val checked = BooleanArray(items.size)
        val labels = items.map { "${it.name}\n${size(it.size)}" }.toTypedArray()
        val dlg = AlertDialog.Builder(this).setTitle(g.title)
            .setMultiChoiceItems(labels, checked) { dd, i, on ->
                checked[i] = on
                (dd as AlertDialog).setTitle("Выбрано: " + size(items.filterIndexed { k, _ -> checked[k] }.sumOf { it.size }))
            }
            .setPositiveButton("Удалить") { _, _ ->
                val sel = items.filterIndexed { i, _ -> checked[i] }
                if (sel.isNotEmpty()) confirm("${sel.size} шт.", sel)
            }
            .setNegativeButton("Отмена", null).create()
        dlg.show()
    }

    private fun confirm(what: String, list: List<Entry>) {
        AlertDialog.Builder(this).setTitle("Удалить: $what?")
            .setMessage("Освободится ${size(list.sumOf { it.size })}. Отменить нельзя.")
            .setPositiveButton("Удалить") { _, _ -> delete(list) }
            .setNegativeButton("Отмена", null).show()
    }

    // ================================================================ deleting

    private fun delete(list: List<Entry>) {
        val media = ArrayList<Uri>()
        io.execute {
            list.forEach { e ->
                when (val x = e.delete) {
                    is Deleter.Torrent -> runOnUiThread { TorrentEngine.remove(x.hash, deleteFiles = true) }
                    is Deleter.Files -> if (x.file == cacheDir || x.file == externalCacheDir) x.file.listFiles()?.forEach { it.deleteRecursively() } else x.file.deleteRecursively()
                    is Deleter.Project -> Projects.delete(this, x.id)
                    is Deleter.Media -> {
                        // the app's own files go at once; others need the system's «allow»
                        val ok = runCatching { contentResolver.delete(x.uri, null, null) > 0 }.getOrDefault(false)
                        if (!ok) media += x.uri
                    }
                }
            }
            runOnUiThread {
                if (media.isNotEmpty()) {
                    afterSystemDelete = { load() }
                    runCatching {
                        @Suppress("DEPRECATION")
                        startIntentSenderForResult(MediaStore.createDeleteRequest(contentResolver, media).intentSender, REQ_DELETE, null, 0, 0, 0)
                    }.onFailure { toast("Не удалось удалить ${media.size} файл(ов)"); load() }
                } else { toast("Удалено"); load() }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_DELETE) { if (resultCode == RESULT_OK) toast("Удалено"); afterSystemDelete?.invoke(); afterSystemDelete = null }
    }

    private fun size(b: Long) = com.dave.framegrabber.site.Lab.size(b).ifBlank { "0 Б" }
}
