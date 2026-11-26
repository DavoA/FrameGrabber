package com.dave.framegrabber.ui.main

import com.dave.framegrabber.ui.tc

import android.app.AlertDialog
import android.graphics.Typeface
import android.net.Uri
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.dave.framegrabber.R
import com.dave.framegrabber.core.TimeUtil
import com.dave.framegrabber.player.Library
import com.dave.framegrabber.player.PlayerStore
import com.dave.framegrabber.player.RandomMoment
import com.dave.framegrabber.ui.onTextChanged
import com.dave.framegrabber.ui.player.PlayerActivity
import com.dave.framegrabber.ui.telegram.Chips
import com.dave.framegrabber.ui.telegram.Chips.setOn
import com.dave.framegrabber.ui.toast
import java.io.File

/**
 * Page «Плеер»: every video from the folders chosen in the settings, as a grid of cards
 * (thumbnail, length, how far you watched, NEW / VR), with search, sorting and filters
 * (Все · Продолжить · Новые · by folder). 🎲 = a random hot moment.
 */
class PlayerTab(private val s: MainScreen) {

    private val lib = Library(s.act)
    private val grid = s.h<GridView>(R.id.libGrid)
    private val chips = s.h<LinearLayout>(R.id.libChips)
    private val search = s.h<EditText>(R.id.editLibSearch)
    private val status = s.h<TextView>(R.id.txtRandomStatus)
    private val empty = s.h<View>(R.id.libEmpty)
    private val emptyText = s.h<TextView>(R.id.txtLibEmpty)
    private val btnRandom = s.h<View>(R.id.btnRandomMoment)
    private val d = s.act.resources.displayMetrics.density

    private var all: List<Library.Item> = emptyList()
    private var watched: Map<String, PlayerStore.Video> = emptyMap()
    private var shown: List<Library.Item> = emptyList()
    /** "all" | "continue" | "new" | a folder path */
    private var filter = "all"
    private var busy = false
    private var scanning = false
    private val adapter = Adapter()

    init {
        applyView()
        // taps, holds (= preview) and ⋮ are handled by each card itself
        grid.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(v: AbsListView, state: Int) {
                if (state != AbsListView.OnScrollListener.SCROLL_STATE_IDLE) stopPreview()
                s.act.bgView.scrolling = state != AbsListView.OnScrollListener.SCROLL_STATE_IDLE
            }
            override fun onScroll(v: AbsListView, a: Int, b: Int, c: Int) {}
        })
        search.onTextChanged { apply() }
        btnRandom.setOnClickListener { random() }
        s.h<View>(R.id.btnRandomVideo).setOnClickListener { randomVideo() }
        s.h<View>(R.id.btnLibSort).setOnClickListener { chooseSort() }
        s.h<View>(R.id.btnLibFolders).setOnClickListener { showFolders() }
        s.h<View>(R.id.btnLibAddFolder).setOnClickListener { s.act.pickPlayerFolder() }
    }

    fun play(uri: Uri, startUs: Long = -1, name: String? = null) {
        s.video.pausePlayback()
        s.act.startActivity(PlayerActivity.intent(s.act, uri, startUs, name))
    }

    /** The page was opened (or folders changed): scan again. */
    fun refresh() {
        if (lib.view != appliedView) applyView()
        if (scanning) return
        scanning = true
        val ctx = s.act.applicationContext
        Thread {
            val items = runCatching { lib.scan() }.getOrDefault(emptyList())
            val mem = PlayerStore(ctx).all().associateBy { it.id }
            s.ui.post {
                scanning = false
                all = items; watched = mem
                buildChips()
                apply()
            }
        }.start()
    }

    // ================================================================ filters, search, sorting

    private fun buildChips() {
        chips.removeAllViews()
        val folders = all.map { it.folder }.distinctBy { it.path }.sortedBy { it.name.lowercase() }
        if (filter != "all" && filter != "continue" && filter != "new" && folders.none { it.path == filter }) filter = "all"
        fun chip(text: String, key: String) = Chips.make(s.act, text, on = filter == key).apply {
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = (8 * d).toInt() }
            setOnClickListener { filter = key; buildChips(); apply() }
            chips.addView(this)
        }
        chip("Все · ${all.size}", "all")
        val cont = all.count { inProgress(it) }
        if (cont > 0) chip("▶ Продолжить · $cont", "continue")
        val fresh = all.count { watched[it.id] == null }
        if (fresh > 0 && fresh < all.size) chip("Новые · $fresh", "new")
        if (folders.size > 1) folders.forEach { f -> chip("📁 " + f.name, f.path) }
    }

    private fun inProgress(i: Library.Item) = watched[i.id]?.let { it.positionUs > 0 } == true

    private fun apply() {
        val q = search.text.toString().trim().lowercase()
        var list = all.asSequence()
        list = when (filter) {
            "all" -> list
            "continue" -> list.filter { inProgress(it) }
            "new" -> list.filter { watched[it.id] == null }
            else -> list.filter { it.folder.path == filter }
        }
        if (q.isNotEmpty()) list = list.filter { it.name.lowercase().contains(q) || it.folder.name.lowercase().contains(q) }
        shown = when (if (filter == "continue") "watched" else lib.sort) {
            "name" -> list.sortedBy { it.name.lowercase() }
            "size" -> list.sortedByDescending { it.size }
            "duration" -> list.sortedByDescending { lib.cachedDuration(it.file) }
            "watched" -> list.sortedByDescending { watched[it.id]?.lastWatched ?: 0 }
            else -> list.sortedByDescending { it.date }
        }.toList()
        adapter.notifyDataSetChanged()
        val noFolders = lib.folders.isEmpty()
        empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        s.h<View>(R.id.btnLibAddFolder).visibility = if (noFolders) View.VISIBLE else View.GONE
        emptyText.text = when {
            noFolders -> "Выбери папки, где лежат твои видео —\nплеер покажет их все, как в VLC.\n(Потом — ⚙ или 📁 вверху.)"
            all.isEmpty() -> "В выбранных папках нет видео"
            else -> "Ничего не найдено"
        }
    }

    /** «Вид и сортировка»: cards per row (1 / 2 / 3 / list) and the order. */
    private fun chooseSort() {
        val ctx = s.act
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), 0)
        }
        box.addView(TextView(ctx, null, 0, R.style.Label).apply { text = "Вид" })
        val views = listOf("1" to "1 в ряд", "2" to "2 в ряд", "3" to "3 в ряд", "list" to "Список")
        val seg = android.widget.RadioGroup(ctx, null).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(R.drawable.bg_segment_group)
            setPadding((3 * d).toInt(), (3 * d).toInt(), (3 * d).toInt(), (3 * d).toInt())
        }
        views.forEachIndexed { i, (key, label) ->
            seg.addView(android.widget.RadioButton(ctx, null, 0, R.style.Segment).apply {
                id = View.generateViewId(); text = label; tag = key
                // made in code with only our style, the button misses Android's default
                // "clickable" — without this a tap does nothing
                isClickable = true; isFocusable = true
                layoutParams = android.widget.RadioGroup.LayoutParams(0, (40 * d).toInt(), 1f)
            })
            if (key == lib.view) seg.check(seg.getChildAt(i).id)
        }
        seg.setOnCheckedChangeListener { g, id -> lib.view = g.findViewById<View>(id).tag as String; applyView() }
        box.addView(seg)
        box.addView(TextView(ctx, null, 0, R.style.Label).apply { text = "Сортировка" })
        val opts = listOf("date" to "Сначала новые", "name" to "По имени", "size" to "Сначала большие",
            "duration" to "Сначала длинные", "watched" to "Недавно смотрел")
        val rg = android.widget.RadioGroup(ctx)
        opts.forEach { (key, label) ->
            rg.addView(android.widget.RadioButton(ctx).apply {
                id = View.generateViewId(); text = label; tag = key
                setTextColor(ctx.tc(R.color.text)); textSize = 15f
                buttonTintList = android.content.res.ColorStateList.valueOf(ctx.tc(R.color.accent))
                minHeight = (42 * d).toInt()
                isChecked = key == lib.sort
            })
        }
        rg.setOnCheckedChangeListener { g, id -> lib.sort = g.findViewById<View>(id).tag as String; apply() }
        box.addView(rg)
        AlertDialog.Builder(ctx).setTitle("Вид и сортировка").setView(android.widget.ScrollView(ctx).apply { addView(box) })
            .setPositiveButton("Готово", null).show()
    }

    /** Cards per row from the settings (a list = one per row, the small layout). */
    private var appliedView = ""

    private fun applyView() {
        stopPreview()
        val v = lib.view
        appliedView = v
        grid.numColumns = when (v) { "1", "list" -> 1; "3" -> 3; else -> 2 }
        grid.horizontalSpacing = ((if (v == "3") 6 else 10) * d).toInt()
        grid.verticalSpacing = ((if (v == "list") 8 else if (v == "3") 10 else 14) * d).toInt()
        grid.adapter = adapter   // fresh cells for the new layout
    }

    // ================================================================ folders

    /** «Папки плеера»: add / remove (also from ⚙). */
    fun showFolders() {
        val folders = lib.folders
        val items = folders.map { "📁 " + it.removePrefix("/storage/emulated/0/").ifEmpty { "Вся память" } } +
            listOf("＋ Добавить папку", "Открыть другое видео…")
        AlertDialog.Builder(s.act).setTitle("Папки плеера")
            .setItems(items.toTypedArray()) { _, i ->
                when {
                    i < folders.size -> AlertDialog.Builder(s.act)
                        .setMessage("Убрать папку «${File(folders[i]).name}» из плеера? (Файлы не удаляются)")
                        .setPositiveButton("Убрать") { _, _ -> lib.removeFolder(folders[i]); refresh() }
                        .setNegativeButton("Отмена", null).show()
                    i == folders.size -> s.act.pickPlayerFolder()
                    else -> s.act.openBrowserForPlayer()
                }
            }
            .setNegativeButton("Закрыть", null).show()
    }

    fun onFolderPicked(path: String) {
        lib.addFolder(path)
        s.act.toast("Папка добавлена: ${File(path).name.ifEmpty { "Вся память" }}")
        filter = "all"
        refresh()
    }

    // ================================================================ hold a card = preview

    private var prevMp: android.media.MediaPlayer? = null
    private var prevView: android.view.TextureView? = null
    private var prevFrame: FrameLayout? = null
    private var prevTimes: List<Long> = emptyList()
    private val hop = object : Runnable {
        override fun run() {
            val p = prevMp ?: return
            runCatching { p.seekTo(prevTimes.random() / 1000, android.media.MediaPlayer.SEEK_PREVIOUS_SYNC) }
            s.ui.postDelayed(this, 2600)
        }
    }

    /** Muted snippets of the video in the card: a random place, then another every ~2.5 s. */
    private fun startPreview(frame: FrameLayout, item: Library.Item) {
        stopPreview()
        val ctx = s.act
        frame.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        // hot places if the video was analysed, otherwise anywhere from 5 % to 90 %
        val w = watched[item.id]
        val durUs = (w?.durationUs?.takeIf { it > 0 } ?: lib.cachedDuration(item.file) * 1000).coerceAtLeast(1)
        prevTimes = w?.activity?.let { com.dave.framegrabber.player.ActivityScan.hotMoments(it, durUs, 0.3f) }
            ?: List(12) { (durUs * (0.05 + Math.random() * 0.85)).toLong() }
        val tv = android.view.TextureView(ctx)
        tv.alpha = 0f
        frame.addView(tv, 1, FrameLayout.LayoutParams(-1, -1))
        prevView = tv; prevFrame = frame
        tv.surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, width: Int, height: Int) {
                if (prevView !== tv) return
                val p = android.media.MediaPlayer()
                prevMp = p
                runCatching {
                    p.setDataSource(item.file.path)
                    p.setSurface(android.view.Surface(st))
                    p.setVolume(0f, 0f)
                    p.isLooping = true
                    p.setOnVideoSizeChangedListener { _, vw, vh -> cropFill(tv, vw, vh) }
                    p.setOnInfoListener { _, what, _ ->
                        if (what == android.media.MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) tv.animate().alpha(1f).setDuration(150).start()
                        false
                    }
                    p.setOnPreparedListener {
                        if (prevMp !== it) return@setOnPreparedListener
                        it.seekTo(prevTimes.random() / 1000, android.media.MediaPlayer.SEEK_PREVIOUS_SYNC)
                        it.start()
                        s.ui.postDelayed(hop, 2600)
                    }
                    p.setOnErrorListener { _, _, _ -> stopPreview(); true }
                    p.prepareAsync()
                }.onFailure { stopPreview() }
            }
            override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
        }
    }

    /** Fill the card (crop the edges) like the thumbnail does. */
    private fun cropFill(tv: android.view.TextureView, vw: Int, vh: Int) {
        if (vw <= 0 || vh <= 0 || tv.width <= 0) return
        val k = maxOf(tv.width.toFloat() / vw, tv.height.toFloat() / vh)
        val m = android.graphics.Matrix()
        m.setScale(vw * k / tv.width, vh * k / tv.height, tv.width / 2f, tv.height / 2f)
        tv.setTransform(m)
    }

    private val stopLater = Runnable { stopPreview() }

    /** Finger lifted: stop in 4 s, or keep playing until another preview starts (⚙). */
    private fun releasePreview() {
        s.ui.removeCallbacks(stopLater)
        if (!lib.previewKeep) s.ui.postDelayed(stopLater, 4000)
    }

    fun stopPreview() {
        s.ui.removeCallbacks(stopLater)
        s.ui.removeCallbacks(hop)
        prevMp?.let { p -> prevMp = null; runCatching { p.stop() }; p.release() }
        prevView?.let { v -> prevFrame?.removeView(v) }
        prevView = null; prevFrame = null
    }

    // ================================================================ a card's ⋮ menu

    private fun actions(i: Library.Item) {
        val v = watched[i.id]
        val opts = mutableListOf("▶ Смотреть", "⏮ Смотреть с начала", "🎞 Открыть для кадров")
        if (v != null) opts += "Забыть прогресс и моменты"
        val deleteAt = opts.size
        opts += "🗑 Удалить видео"
        AlertDialog.Builder(s.act).setTitle(i.name)
            .setItems(opts.toTypedArray()) { _, k ->
                val uri = Uri.fromFile(i.file)
                when (k) {
                    0 -> play(uri, -1, i.name)
                    1 -> play(uri, 0, i.name)
                    2 -> { s.video.load(uri); s.nav.show(NavController.Page.CAPTURE) }
                    deleteAt -> delete(i)
                    3 -> { PlayerStore(s.act).delete(i.id); refresh() }
                }
            }.show()
    }

    // ================================================================ 🔀 random video

    /** A random video from what's shown now (so it respects the filter / search), from its start. */
    private fun randomVideo() {
        val pool = shown.ifEmpty { all }
        if (pool.isEmpty()) {
            s.act.toast(if (lib.folders.isEmpty()) "Сначала добавь папку с видео" else "Нет видео")
            return
        }
        // the one played just now doesn't come up twice in a row
        val pick = pool.filter { it.file.path != lastRandom }.ifEmpty { pool }.random()
        lastRandom = pick.file.path
        play(Uri.fromFile(pick.file), -1, pick.name)
    }

    private var lastRandom: String? = null

    // ================================================================ deleting

    private var deleting: Library.Item? = null

    /**
     * Android asks itself ("Разрешить удалить это видео?") for files of other apps, so the
     * request goes through MediaStore; a file not in MediaStore is deleted directly (after asking).
     */
    private fun delete(i: Library.Item) {
        stopPreview()
        val ms = mediaStoreUri(i.file)
        if (ms != null) {
            deleting = i
            val req = android.provider.MediaStore.createDeleteRequest(s.act.contentResolver, listOf(ms))
            s.act.askSystem(req.intentSender)
            return
        }
        AlertDialog.Builder(s.act)
            .setTitle("Удалить видео?")
            .setMessage("${i.name}\n${size(i.size)}\n\nФайл будет удалён с телефона.")
            .setPositiveButton("Удалить") { _, _ ->
                if (i.file.delete()) onDeleted(i) else s.act.toast("Android не разрешил удалить этот файл")
            }
            .setNegativeButton("Отмена", null).show()
    }

    /** Back from the system "delete?" question. */
    fun onDeleteResult(ok: Boolean) {
        val i = deleting ?: return
        deleting = null
        if (ok) onDeleted(i)
    }

    private fun onDeleted(i: Library.Item) {
        PlayerStore(s.act).delete(i.id)
        s.act.toast("Удалено: ${i.name}")
        all = all.filter { it.file.path != i.file.path }
        buildChips(); apply()
    }

    private fun mediaStoreUri(f: File): Uri? = runCatching {
        val col = android.provider.MediaStore.Video.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL)
        @Suppress("DEPRECATION")
        s.act.contentResolver.query(col, arrayOf(android.provider.MediaStore.Video.Media._ID),
            "${android.provider.MediaStore.Video.Media.DATA}=?", arrayOf(f.path), null)?.use { c ->
            if (c.moveToFirst()) android.content.ContentUris.withAppendedId(col, c.getLong(0)) else null
        }
    }.getOrNull()

    // ================================================================ 🎲

    private fun random() {
        if (busy) return
        busy = true
        btnRandom.isEnabled = false; btnRandom.alpha = 0.5f
        status.visibility = View.VISIBLE
        status.text = "🎲 Выбираю видео…"
        val ctx = s.act.applicationContext
        // from the library when it has videos, otherwise from the whole phone
        val pool = all.filter { (lib.cachedDuration(it.file).takeIf { d -> d > 1 } ?: Long.MAX_VALUE) >= 60_000 }
        Thread {
            val pick = runCatching {
                if (pool.isNotEmpty()) RandomMoment.pickFrom(ctx, pool.map { Uri.fromFile(it.file) to it.name }) { m -> s.ui.post { status.text = m }; true }
                else RandomMoment.pick(ctx) { m -> s.ui.post { status.text = m }; true }
            }
            s.ui.post {
                busy = false
                btnRandom.isEnabled = true; btnRandom.alpha = 1f
                status.visibility = View.GONE
                pick.onSuccess { p ->
                    if (p == null) s.act.toast("Не нашлось видео длиннее минуты")
                    else play(p.uri, p.startUs, p.name)
                }.onFailure { s.act.toast("Не получилось: ${it.message}") }
            }
        }.start()
    }

    // ================================================================ the grid

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(p: Int) = shown[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(p: Int) = if (lib.view == "list") 1 else 0

        private inner class Holder(val thumb: ImageView, val dur: TextView, val badge: TextView,
                                   val progress: ProgressBar, val name: TextView, val sub: TextView) {
            var path = ""
            var item: Library.Item? = null
        }

        override fun getView(pos: Int, convert: View?, parent: ViewGroup): View {
            val v = convert ?: make(parent, list = lib.view == "list")
            val h = v.tag as Holder
            val item = shown[pos]
            val w = watched[item.id]
            h.path = item.file.path
            h.item = item
            h.name.text = item.name.substringBeforeLast('.')
            h.sub.text = buildString {
                if (lib.view == "list") lib.cachedDuration(item.file).takeIf { it > 1 }?.let { append(TimeUtil.format(it * 1000).substringBefore('.')).append(" · ") }
                append(size(item.size))
                if (filter == "all" || filter == "new" || filter == "continue") append(" · ${item.folder.name}")
            }
            val durMs = lib.cachedDuration(item.file)
            h.dur.text = if (durMs > 1) TimeUtil.format(durMs * 1000).substringBefore('.') else ""
            h.dur.visibility = if (durMs > 1) View.VISIBLE else View.GONE
            // badge: VR, NEW or ★ count
            val vr = w?.vr?.takeIf { it.isNotEmpty() && it != "flat" } != null ||
                listOf("vr180", "_180", "180_", "_lr", "sbs", "360").any { item.name.lowercase().contains(it) }
            h.badge.visibility = View.VISIBLE
            when {
                vr -> h.badge.text = "VR"
                w == null -> h.badge.text = "NEW"
                w.moments.isNotEmpty() -> h.badge.text = "★ ${w.moments.size}"
                else -> h.badge.visibility = View.GONE
            }
            // how far you watched
            if (w != null && w.positionUs > 0 && w.durationUs > 0) {
                h.progress.visibility = View.VISIBLE
                h.progress.progress = (w.positionUs * 1000 / w.durationUs).toInt()
            } else h.progress.visibility = View.GONE
            // thumbnail in the background
            h.thumb.setImageDrawable(null)
            val f = item.file
            Library.loader.execute {
                val b = lib.thumb(f)
                s.ui.post {
                    if (h.path != f.path) return@post
                    if (b != null) h.thumb.setImageBitmap(b)
                    val dm = lib.cachedDuration(f)
                    if (dm > 1 && h.dur.visibility != View.VISIBLE) { h.dur.text = TimeUtil.format(dm * 1000).substringBefore('.'); h.dur.visibility = View.VISIBLE }
                }
            }
            return v
        }

        private fun make(parent: ViewGroup, list: Boolean): View {
            val ctx = s.act
            val small = lib.view == "3"
            // list: the picture on the left, the text on the right
            val col = LinearLayout(ctx).apply {
                orientation = if (list) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                if (list) { gravity = Gravity.CENTER_VERTICAL; setBackgroundResource(R.drawable.bg_row); setPadding((6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt()) }
            }
            val frame = FrameLayout(ctx).apply {
                setBackgroundResource(R.drawable.bg_thumb)
                clipToOutline = true
            }
            val thumb = object : ImageView(ctx) {
                override fun onMeasure(w: Int, h: Int) {
                    val width = MeasureSpec.getSize(w)
                    setMeasuredDimension(width, width * 9 / 16)
                }
            }.apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            frame.addView(thumb, FrameLayout.LayoutParams(-1, -2))
            val dur = TextView(ctx).apply {
                textSize = 11f; setTextColor(ctx.tc(R.color.text)); typeface = Typeface.MONOSPACE
                setBackgroundResource(R.drawable.bg_player_pill)
                setPadding((6 * d).toInt(), (1 * d).toInt(), (6 * d).toInt(), (1 * d).toInt())
            }
            frame.addView(dur, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, (6 * d).toInt(), (8 * d).toInt()) })
            val badge = TextView(ctx).apply {
                textSize = 10f; setTextColor(ctx.tc(R.color.text)); typeface = Typeface.DEFAULT_BOLD
                setBackgroundResource(R.drawable.bg_chip_on)
                setPadding((7 * d).toInt(), (1 * d).toInt(), (7 * d).toInt(), (1 * d).toInt())
            }
            frame.addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins((6 * d).toInt(), (6 * d).toInt(), 0, 0) })
            val progress = ProgressBar(ctx, null, 0, android.R.style.Widget_Material_ProgressBar_Horizontal).apply {
                max = 1000
                progressTintList = android.content.res.ColorStateList.valueOf(ctx.tc(R.color.accent))
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x66000000)
            }
            frame.addView(progress, FrameLayout.LayoutParams(-1, (4 * d).toInt(), Gravity.BOTTOM))
            // ⋮ : the card's menu
            val more = android.widget.ImageButton(ctx).apply {
                setImageResource(R.drawable.ic_more)
                imageTintList = android.content.res.ColorStateList.valueOf(ctx.tc(R.color.text))
                setBackgroundResource(R.drawable.bg_player_pill)
                contentDescription = "Действия"
                isFocusable = false
            }
            frame.addView(more, FrameLayout.LayoutParams((30 * d).toInt(), (30 * d).toInt(), Gravity.TOP or Gravity.END)
                .apply { setMargins(0, (5 * d).toInt(), (5 * d).toInt(), 0) })
            if (list) col.addView(frame, LinearLayout.LayoutParams((132 * d).toInt(), -2))
            else col.addView(frame)
            if (small) more.layoutParams = FrameLayout.LayoutParams((24 * d).toInt(), (24 * d).toInt(), Gravity.TOP or Gravity.END)
                .apply { setMargins(0, (3 * d).toInt(), (3 * d).toInt(), 0) }
            val name = TextView(ctx).apply {
                textSize = 13f; setTextColor(ctx.tc(R.color.text)); maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                setPadding((2 * d).toInt(), (6 * d).toInt(), (2 * d).toInt(), 0)
            }
            val sub = TextView(ctx).apply {
                textSize = 11f; setTextColor(ctx.tc(R.color.textDim)); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                setPadding((2 * d).toInt(), (1 * d).toInt(), (2 * d).toInt(), 0)
            }
            if (small) { name.textSize = 11f; name.maxLines = 1; sub.textSize = 10f }
            if (list) {
                val texts = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding((10 * d).toInt(), 0, 0, 0)
                    addView(name); addView(sub)
                }
                name.setPadding(0, 0, 0, 0)
                col.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
                // list: ⋮ at the end of the row, not over the small picture
                frame.removeView(more)
                more.background = null
                col.addView(more, LinearLayout.LayoutParams((40 * d).toInt(), (40 * d).toInt()))
            } else { col.addView(name); col.addView(sub) }
            val holder = Holder(thumb, dur, badge, progress, name, sub)
            col.tag = holder
            more.setOnClickListener { stopPreview(); holder.item?.let { actions(it) } }
            // tap = play; hold = preview (muted snippets) while the finger is down
            var previewing = false
            val startHold = Runnable { holder.item?.let { previewing = true; startPreview(frame, it) } }
            col.setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        previewing = false
                        v.isPressed = true
                        s.ui.postDelayed(startHold, 280)
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        s.ui.removeCallbacks(startHold)
                        v.isPressed = false
                        if (previewing) releasePreview()
                        else holder.item?.let { stopPreview(); play(Uri.fromFile(it.file), -1, it.name) }
                    }
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        s.ui.removeCallbacks(startHold)
                        v.isPressed = false
                        if (previewing) releasePreview()
                    }
                }
                true
            }
            return col
        }
    }

    private fun size(b: Long): String {
        val mb = b / 1_048_576.0
        return if (mb >= 1000) "%.2f ГБ".format(mb / 1000) else "%.0f МБ".format(mb)
    }
}
