package com.dave.framegrabber.ui.main

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
        grid.adapter = adapter
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

    private fun chooseSort() {
        val opts = listOf("date" to "Сначала новые", "name" to "По имени", "size" to "Сначала большие",
            "duration" to "Сначала длинные", "watched" to "Недавно смотрел")
        AlertDialog.Builder(s.act).setTitle("Сортировка")
            .setSingleChoiceItems(opts.map { it.second }.toTypedArray(), opts.indexOfFirst { it.first == lib.sort }) { dlg, i ->
                lib.sort = opts[i].first; apply(); dlg.dismiss()
            }.show()
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

    fun stopPreview() {
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
        AlertDialog.Builder(s.act).setTitle(i.name)
            .setItems(opts.toTypedArray()) { _, k ->
                val uri = Uri.fromFile(i.file)
                when (k) {
                    0 -> play(uri, -1, i.name)
                    1 -> play(uri, 0, i.name)
                    2 -> { s.video.load(uri); s.nav.show(NavController.Page.CAPTURE) }
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

        private inner class Holder(val thumb: ImageView, val dur: TextView, val badge: TextView,
                                   val progress: ProgressBar, val name: TextView, val sub: TextView) {
            var path = ""
            var item: Library.Item? = null
        }

        override fun getView(pos: Int, convert: View?, parent: ViewGroup): View {
            val v = convert ?: make(parent)
            val h = v.tag as Holder
            val item = shown[pos]
            val w = watched[item.id]
            h.path = item.file.path
            h.item = item
            h.name.text = item.name.substringBeforeLast('.')
            h.sub.text = buildString {
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

        private fun make(parent: ViewGroup): View {
            val ctx = s.act
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
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
                textSize = 11f; setTextColor(ctx.getColor(R.color.text)); typeface = Typeface.MONOSPACE
                setBackgroundResource(R.drawable.bg_player_pill)
                setPadding((6 * d).toInt(), (1 * d).toInt(), (6 * d).toInt(), (1 * d).toInt())
            }
            frame.addView(dur, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, (6 * d).toInt(), (8 * d).toInt()) })
            val badge = TextView(ctx).apply {
                textSize = 10f; setTextColor(ctx.getColor(R.color.text)); typeface = Typeface.DEFAULT_BOLD
                setBackgroundResource(R.drawable.bg_chip_on)
                setPadding((7 * d).toInt(), (1 * d).toInt(), (7 * d).toInt(), (1 * d).toInt())
            }
            frame.addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins((6 * d).toInt(), (6 * d).toInt(), 0, 0) })
            val progress = ProgressBar(ctx, null, 0, android.R.style.Widget_Material_ProgressBar_Horizontal).apply {
                max = 1000
                progressTintList = android.content.res.ColorStateList.valueOf(ctx.getColor(R.color.accent))
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x66000000)
            }
            frame.addView(progress, FrameLayout.LayoutParams(-1, (4 * d).toInt(), Gravity.BOTTOM))
            // ⋮ : the card's menu
            val more = android.widget.ImageButton(ctx).apply {
                setImageResource(R.drawable.ic_more)
                imageTintList = android.content.res.ColorStateList.valueOf(ctx.getColor(R.color.text))
                setBackgroundResource(R.drawable.bg_player_pill)
                contentDescription = "Действия"
                isFocusable = false
            }
            frame.addView(more, FrameLayout.LayoutParams((30 * d).toInt(), (30 * d).toInt(), Gravity.TOP or Gravity.END)
                .apply { setMargins(0, (5 * d).toInt(), (5 * d).toInt(), 0) })
            col.addView(frame)
            val name = TextView(ctx).apply {
                textSize = 13f; setTextColor(ctx.getColor(R.color.text)); maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                setPadding((2 * d).toInt(), (6 * d).toInt(), (2 * d).toInt(), 0)
            }
            val sub = TextView(ctx).apply {
                textSize = 11f; setTextColor(ctx.getColor(R.color.textDim)); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                setPadding((2 * d).toInt(), (1 * d).toInt(), (2 * d).toInt(), 0)
            }
            col.addView(name); col.addView(sub)
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
                        if (previewing) stopPreview()
                        else holder.item?.let { stopPreview(); play(Uri.fromFile(it.file), -1, it.name) }
                    }
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        s.ui.removeCallbacks(startHold)
                        v.isPressed = false
                        if (previewing) stopPreview()
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
