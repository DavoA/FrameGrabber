package com.dave.framegrabber

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.text.format.Formatter
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.AnimationUtils
import android.view.animation.LayoutAnimationController
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.CheckedTextView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/**
 * In-app file manager for picking a video (ZArchiver-style):
 * folders + videos with thumbnails, path bar with "up", quick places, and search across the whole phone.
 *
 * Result: extra [EXTRA_PATH] (a file path) or data Uri (MediaStore item / other app).
 * A file:// Uri can't be passed through setResult on Android 7+, hence the path extra.
 */
class FileBrowserActivity : Activity() {

    companion object {
        const val EXTRA_PATH = "path"
        private const val REQ_PERM = 10
        private const val REQ_OTHER = 11
        private val VIDEO_EXT = setOf(
            "mp4", "m4v", "mkv", "webm", "mov", "3gp", "3g2", "avi", "ts", "mts", "m2ts",
            "flv", "wmv", "mpg", "mpeg", "ogv", "vob"
        )
    }

    private sealed class Entry {
        class Dir(val file: File) : Entry()
        class Video(
            val name: String, val size: Long, val dateMs: Long, val durationMs: Long,
            val sub: String?, val file: File?, val uri: Uri?
        ) : Entry() {
            val key: String get() = file?.path ?: uri.toString()
        }
        class Header(val title: String) : Entry()
    }

    private enum class Mode { BROWSE, ALL, SEARCH }

    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    /** Low-priority threads so thumbnail decoding never competes with scrolling. */
    private val thumbs = Executors.newFixedThreadPool(2) { r ->
        Thread { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() }
    }
    private var flinging = false
    private val dateFmt: DateFormat = DateFormat.getDateInstance(DateFormat.SHORT)
    private val thumbCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val failedThumbs = HashSet<String>()
    private val loadGen = AtomicInteger()

    private val storageRoot: File = Environment.getExternalStorageDirectory()
    private var dir: File = storageRoot
    private var mode = Mode.BROWSE
    private val entries = mutableListOf<Entry>()
    private var askedOnce = false

    private lateinit var bgView: SynthwaveView
    private lateinit var editSearch: EditText
    private lateinit var btnClearSearch: View
    private lateinit var chipsBox: LinearLayout
    private lateinit var txtPath: TextView
    private lateinit var pathScroll: HorizontalScrollView
    private lateinit var txtCount: TextView
    private lateinit var btnUp: View
    private lateinit var list: ListView
    private lateinit var stateBox: View
    private lateinit var stateIcon: ImageView
    private lateinit var stateText: TextView
    private lateinit var stateBtn: TextView
    private lateinit var stateBtn2: View
    private val adapter = Adapter()

    private val dp get() = resources.displayMetrics.density
    private val searchRunnable = Runnable { runSearch() }

    private class Place(val title: String, val dir: File?, val all: Boolean = false)
    private val chipViews = mutableListOf<Pair<Place, CheckedTextView>>()

    // ================================================================ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        setContentView(R.layout.activity_browser)
        bgView = findViewById(R.id.bgView)
        AppTheme.apply(this, bgView, AppTheme.get(this), animate = false)
        editSearch = findViewById(R.id.editSearch)
        btnClearSearch = findViewById(R.id.btnClearSearch)
        chipsBox = findViewById(R.id.chips)
        txtPath = findViewById(R.id.txtPath)
        pathScroll = findViewById(R.id.pathScroll)
        txtCount = findViewById(R.id.txtCount)
        btnUp = findViewById(R.id.btnUp)
        list = findViewById(R.id.list)
        stateBox = findViewById(R.id.stateBox)
        stateIcon = findViewById(R.id.stateIcon)
        stateText = findViewById(R.id.stateText)
        stateBtn = findViewById(R.id.stateBtn)
        stateBtn2 = findViewById(R.id.stateBtn2)

        applyInsets()
        list.adapter = adapter
        list.selector = ColorDrawable(Color.TRANSPARENT)
        list.layoutAnimation = LayoutAnimationController(AnimationUtils.loadAnimation(this, R.anim.item_in), 0.06f)
        list.setOnItemClickListener { _, _, pos, _ -> onEntryClick(entries[pos]) }
        list.setOnScrollListener(object : android.widget.AbsListView.OnScrollListener {
            override fun onScrollStateChanged(v: android.widget.AbsListView, state: Int) {
                bgView.scrolling = state != android.widget.AbsListView.OnScrollListener.SCROLL_STATE_IDLE
                val wasFling = flinging
                flinging = state == android.widget.AbsListView.OnScrollListener.SCROLL_STATE_FLING
                // after a fling, load thumbnails for whatever is now on screen
                if (wasFling && !flinging) adapter.notifyDataSetChanged()
            }
            override fun onScroll(v: android.widget.AbsListView, first: Int, visible: Int, total: Int) {}
        })

        findViewById<View>(R.id.btnBack).setOnClickListener { onBackPressedCompat() }
        findViewById<View>(R.id.btnOther).setOnClickListener { pickWithOtherApp() }
        stateBtn2.setOnClickListener { pickWithOtherApp() }
        btnUp.setOnClickListener { goUp() }
        btnClearSearch.setOnClickListener { editSearch.setText("") }
        editSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val has = !s.isNullOrBlank()
                if (has && btnClearSearch.visibility != View.VISIBLE) {
                    btnClearSearch.visibility = View.VISIBLE
                    btnClearSearch.scaleX = 0f; btnClearSearch.scaleY = 0f
                    btnClearSearch.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
                } else if (!has) btnClearSearch.visibility = View.GONE
                ui.removeCallbacks(searchRunnable)
                ui.postDelayed(searchRunnable, 300)
            }
        })
        editSearch.setOnEditorActionListener { v, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEARCH) {
                ui.removeCallbacks(searchRunnable); runSearch(); hideKeyboard(v); true
            } else false
        }

        playEntrance()
        if (hasPermission()) start() else requestPerm()
    }

    override fun onResume() { super.onResume(); bgView.running = AppTheme.get(this) == AppTheme.WAVE }
    override fun onPause() { bgView.running = false; super.onPause() }

    override fun onDestroy() {
        io.shutdownNow(); thumbs.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = onBackPressedCompat()

    private fun onBackPressedCompat() {
        when {
            editSearch.text.isNotBlank() -> editSearch.setText("")
            mode != Mode.BROWSE -> openDir(dir)
            dir != storageRoot && dir.parentFile != null -> goUp()
            else -> { finish(); overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out) }
        }
    }

    private fun applyInsets() {
        if (Build.VERSION.SDK_INT < 30) return
        val content = findViewById<View>(R.id.content)
        content.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, 0)
            list.setPadding(list.paddingLeft, list.paddingTop, list.paddingRight, max(bars.bottom, ime.bottom) + (12 * dp).toInt())
            insets
        }
    }

    // ================================================================ permission

    private fun perms(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun hasPermission() = perms().any { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun requestPerm() {
        askedOnce = true
        requestPermissions(perms(), REQ_PERM)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERM) return
        if (hasPermission()) start() else showPermissionState()
    }

    private fun showPermissionState() {
        list.visibility = View.GONE
        showState(R.drawable.ic_folder, "Нужен доступ к видео на телефоне,\nчтобы показать папки и файлы",
            "Разрешить доступ") {
            val canAsk = perms().any { shouldShowRequestPermissionRationale(it) }
            if (canAsk || !askedOnce) requestPerm()
            else startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
        stateBtn2.visibility = View.VISIBLE
    }

    private fun start() {
        buildChips()
        // reopen the folder used last time
        val last = Prefs(this).lastDir?.let { File(it) }
        openDir(if (last != null && last.isDirectory && last.path.startsWith(storageRoot.path)) last else storageRoot)
    }

    // ================================================================ places (chips)

    private fun buildChips() {
        chipsBox.removeAllViews(); chipViews.clear()
        val r = storageRoot
        val candidates = listOf(
            Place("Все видео", null, all = true),
            Place("Память", r),
            Place("Камера", File(r, "DCIM/Camera")),
            Place("Загрузки", File(r, Environment.DIRECTORY_DOWNLOADS)),
            Place("Telegram", listOf(
                File(r, "Android/media/org.telegram.messenger/Telegram/Telegram Video"),
                File(r, "Telegram/Telegram Video"),
                File(r, "Download/Telegram")
            ).firstOrNull { it.isDirectory }),
            Place("WhatsApp", File(r, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video")),
            Place("Фильмы", File(r, Environment.DIRECTORY_MOVIES)),
            Place("Записи экрана", listOf(
                File(r, "Movies/Screen recordings"), File(r, "DCIM/Screen recordings"),
                File(r, "DCIM/ScreenRecorder"), File(r, "Pictures/Screenshots")
            ).firstOrNull { it.isDirectory }),
        )
        candidates.filter { it.all || (it.dir != null && it.dir.isDirectory) }.forEachIndexed { i, p ->
            val chip = CheckedTextView(this).apply {
                text = p.title
                setTextColor(getColorStateList(R.color.segment_text))
                textSize = 13f
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.bg_chip_toggle)
                setPadding((16 * dp).toInt(), 0, (16 * dp).toInt(), 0)
                minHeight = (36 * dp).toInt()
                layoutParams = LinearLayout.LayoutParams(-2, (36 * dp).toInt()).apply { marginEnd = (8 * dp).toInt() }
                stateListAnimator = android.animation.AnimatorInflater.loadStateListAnimator(context, R.animator.press_scale)
                setOnClickListener {
                    editSearch.setText("")
                    if (p.all) loadAll() else openDir(p.dir!!)
                }
                alpha = 0f; translationX = 30 * dp
                animate().alpha(1f).translationX(0f).setStartDelay(150L + 50L * i).setDuration(300).start()
            }
            chipViews += p to chip
            chipsBox.addView(chip)
        }
    }

    private fun syncChips() {
        for ((p, chip) in chipViews) {
            chip.isChecked = when (mode) {
                Mode.ALL -> p.all
                Mode.BROWSE -> !p.all && p.dir == dir
                Mode.SEARCH -> false
            }
        }
    }

    // ================================================================ loading

    private fun openDir(d: File) {
        mode = Mode.BROWSE
        dir = d
        Prefs(this).lastDir = d.path
        val rel = d.path.removePrefix(storageRoot.path).trimStart('/')
        txtPath.text = if (rel.isEmpty()) "Память" else "Память / " + rel.replace("/", " / ")
        pathScroll.post { pathScroll.fullScroll(View.FOCUS_RIGHT) }
        btnUp.isEnabled = d != storageRoot
        btnUp.animate().alpha(if (d != storageRoot) 1f else 0.35f).setDuration(150).start()
        syncChips()
        load {
            val files = d.listFiles().orEmpty().filter { !it.name.startsWith(".") }
            val dirs: List<Entry> = files.filter { it.isDirectory }.sortedBy { it.name.lowercase() }.map { Entry.Dir(it) }
            val vids: List<Entry> = files.filter { it.isFile && it.extension.lowercase() in VIDEO_EXT }
                .sortedByDescending { it.lastModified() }
                .map { Entry.Video(it.name, it.length(), it.lastModified(), -1, null, it, null) }
            dirs + vids
        }
    }

    private fun loadAll() {
        mode = Mode.ALL
        txtPath.text = "Все видео на телефоне"
        btnUp.isEnabled = true
        btnUp.animate().alpha(1f).setDuration(150).start()
        syncChips()
        load { queryMediaStore(null) }
    }

    private fun runSearch() {
        val q = editSearch.text.toString().trim()
        if (q.isEmpty()) {
            if (mode == Mode.SEARCH) openDir(dir)
            return
        }
        mode = Mode.SEARCH
        txtPath.text = "Поиск: «$q»"
        syncChips()
        val here = dir
        load {
            val ql = q.lowercase()
            // folders in the current folder whose name matches + videos anywhere on the phone
            val folders = here.listFiles().orEmpty()
                .filter { it.isDirectory && !it.name.startsWith(".") && ql in it.name.lowercase() }
                .sortedBy { it.name.lowercase() }.map { Entry.Dir(it) }
            val vids = queryMediaStore(q)
            buildList {
                if (folders.isNotEmpty()) { add(Entry.Header("Папки здесь")); addAll(folders) }
                if (vids.isNotEmpty()) { add(Entry.Header("Видео на телефоне")); addAll(vids) }
            }
        }
    }

    private fun queryMediaStore(q: String?): List<Entry> {
        val col = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val proj = arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_MODIFIED, MediaStore.Video.Media.DURATION, MediaStore.Video.Media.RELATIVE_PATH
        )
        val sel = q?.let { "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ? OR ${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?" }
        val args = q?.let { arrayOf("%$it%", "%$it%") }
        val out = mutableListOf<Entry>()
        contentResolver.query(col, proj, sel, args, "${MediaStore.Video.Media.DATE_MODIFIED} DESC")?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                out += Entry.Video(
                    name = c.getString(1) ?: "video",
                    size = c.getLong(2),
                    dateMs = c.getLong(3) * 1000,
                    durationMs = c.getLong(4),
                    sub = c.getString(5)?.trimEnd('/'),
                    file = null,
                    uri = ContentUris.withAppendedId(col, id)
                )
            }
        }
        return out
    }

    /** Runs [block] off the main thread; only the latest request is shown. */
    private fun load(block: () -> List<Entry>) {
        val gen = loadGen.incrementAndGet()
        io.execute {
            val res = runCatching(block)
            ui.post {
                if (gen != loadGen.get()) return@post
                entries.clear()
                res.onSuccess { entries.addAll(it) }
                adapter.notifyDataSetChanged()
                list.setSelection(0)
                list.scheduleLayoutAnimation()
                val videos = entries.count { it is Entry.Video }
                val dirs = entries.count { it is Entry.Dir }
                txtCount.text = buildString {
                    if (dirs > 0) append("📁 $dirs  ")
                    append("🎬 $videos")
                }
                when {
                    res.isFailure -> showState(R.drawable.ic_folder, "Не удалось открыть папку\n${res.exceptionOrNull()?.message ?: ""}", null, null)
                    entries.isEmpty() -> showState(
                        if (mode == Mode.SEARCH) R.drawable.ic_search else R.drawable.ic_movie,
                        if (mode == Mode.SEARCH) "Ничего не найдено" else "Здесь нет видео", null, null
                    )
                    else -> { stateBox.visibility = View.GONE; list.visibility = View.VISIBLE }
                }
            }
        }
    }

    private fun showState(icon: Int, text: String, btn: String?, onBtn: (() -> Unit)?) {
        stateIcon.setImageResource(icon)
        stateText.text = text
        if (btn != null) {
            stateBtn.visibility = View.VISIBLE
            stateBtn.text = btn
            stateBtn.setOnClickListener { onBtn?.invoke() }
        } else stateBtn.visibility = View.GONE
        stateBtn2.visibility = View.GONE
        if (stateBox.visibility != View.VISIBLE) {
            stateBox.visibility = View.VISIBLE
            stateBox.alpha = 0f; stateBox.translationY = 20 * dp
            stateBox.animate().alpha(1f).translationY(0f).setDuration(300).start()
        }
    }

    // ================================================================ actions

    private fun onEntryClick(e: Entry) {
        when (e) {
            is Entry.Dir -> { editSearch.setText(""); openDir(e.file) }
            is Entry.Video -> {
                val data = Intent()
                if (e.file != null) data.putExtra(EXTRA_PATH, e.file.path) else data.data = e.uri
                setResult(RESULT_OK, data)
                finish()
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            }
            is Entry.Header -> {}
        }
    }

    private fun goUp() {
        if (mode != Mode.BROWSE) { editSearch.setText(""); openDir(dir); return }
        val p = dir.parentFile ?: return
        if (dir == storageRoot) return
        openDir(p)
    }

    private fun pickWithOtherApp() {
        val get = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "video/*"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(Intent.createChooser(get, "Выбрать видео через…"), REQ_OTHER)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_OTHER && resultCode == RESULT_OK && data?.data != null) {
            setResult(RESULT_OK, Intent().setData(data.data).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            finish()
        }
    }

    private fun hideKeyboard(v: View) {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
    }

    private fun playEntrance() {
        val ids = listOf(R.id.topBar, R.id.editSearch, R.id.pathBar)
        ids.forEachIndexed { i, id ->
            val v = findViewById<View>(id)
            v.alpha = 0f; v.translationY = -16 * dp
            v.animate().alpha(1f).translationY(0f).setStartDelay(60L * i).setDuration(350).start()
        }
    }

    // ================================================================ list

    private fun fmtDuration(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    private class Holder(
        val thumb: ImageView, val icon: ImageView, val badge: TextView,
        val name: TextView, val sub: TextView, val chevron: ImageView
    ) { var key: String? = null }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = entries.size
        override fun getItem(position: Int): Any = entries[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (entries[position] is Entry.Header) 1 else 0
        override fun isEnabled(position: Int) = entries[position] !is Entry.Header

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val e = entries[position]
            if (e is Entry.Header) {
                val tv = (convertView as? TextView) ?: TextView(this@FileBrowserActivity).apply {
                    setTextColor(getColor(R.color.accent))
                    textSize = 12f
                    letterSpacing = 0.08f
                    setPadding((8 * dp).toInt(), (14 * dp).toInt(), 0, (6 * dp).toInt())
                }
                tv.text = e.title.uppercase()
                return tv
            }
            val v = convertView ?: makeRow()
            val h = v.tag as Holder
            when (e) {
                is Entry.Dir -> {
                    h.key = null
                    h.thumb.setImageDrawable(null)
                    h.icon.visibility = View.VISIBLE
                    h.icon.setImageResource(R.drawable.ic_folder)
                    h.badge.visibility = View.GONE
                    h.chevron.visibility = View.VISIBLE
                    h.name.text = e.file.name
                    h.sub.text = "Папка"
                }
                is Entry.Video -> {
                    h.icon.visibility = View.VISIBLE
                    h.icon.setImageResource(R.drawable.ic_movie)
                    h.chevron.visibility = View.GONE
                    h.name.text = e.name
                    val date = dateFmt.format(Date(e.dateMs))
                    h.sub.text = listOfNotNull(Formatter.formatShortFileSize(this@FileBrowserActivity, e.size), date, e.sub)
                        .joinToString(" · ")
                    if (e.durationMs > 0) { h.badge.visibility = View.VISIBLE; h.badge.text = fmtDuration(e.durationMs) }
                    else h.badge.visibility = View.GONE
                    bindThumb(h, e)
                }
                else -> {}
            }
            return v
        }

        private fun makeRow(): View {
            val ctx = this@FileBrowserActivity
            val outer = FrameLayout(ctx).apply { setPadding(0, (3 * dp).toInt(), 0, (3 * dp).toInt()) }
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_row)
                val p = (8 * dp).toInt()
                setPadding(p, p, p * 2, p)
            }
            val box = FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams((76 * dp).toInt(), (54 * dp).toInt())
                setBackgroundResource(R.drawable.bg_thumb)
                clipToOutline = true
            }
            val icon = ImageView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams((28 * dp).toInt(), (28 * dp).toInt(), Gravity.CENTER)
                imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.accent))
            }
            val thumb = ImageView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(-1, -1)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            val badge = TextView(ctx).apply {
                setBackgroundResource(R.drawable.bg_chip)
                setTextColor(getColor(R.color.text))
                textSize = 9f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding((4 * dp).toInt(), 0, (4 * dp).toInt(), 0)
                layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
                    setMargins(0, 0, (3 * dp).toInt(), (3 * dp).toInt())
                }
            }
            box.addView(icon); box.addView(thumb); box.addView(badge)
            val texts = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = (12 * dp).toInt() }
            }
            val name = TextView(ctx).apply {
                setTextColor(getColor(R.color.text)); textSize = 15f
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            }
            val sub = TextView(ctx).apply {
                setTextColor(getColor(R.color.textDim)); textSize = 12f
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            }
            texts.addView(name); texts.addView(sub)
            val chevron = ImageView(ctx).apply {
                setImageResource(R.drawable.ic_chev_right)
                imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.textDim))
            }
            row.addView(box); row.addView(texts); row.addView(chevron)
            outer.addView(row)
            outer.tag = Holder(thumb, icon, badge, name, sub, chevron)
            return outer
        }
    }

    private fun bindThumb(h: Holder, e: Entry.Video) {
        val key = e.key
        h.key = key
        val cached = thumbCache.get(key)
        if (cached != null) { h.thumb.setImageBitmap(cached); h.thumb.alpha = 1f; return }
        h.thumb.setImageDrawable(null)
        if (key in failedThumbs || flinging) return // during a fling: load once it stops
        thumbs.execute {
            if (h.key != key) return@execute // row was reused for another file meanwhile
            val b = runCatching {
                val size = Size(256, 192)
                if (e.file != null) ThumbnailUtils.createVideoThumbnail(e.file, size, null)
                else contentResolver.loadThumbnail(e.uri!!, size, null)
            }.getOrNull()
            ui.post {
                if (b == null) { failedThumbs += key; return@post }
                thumbCache.put(key, b)
                if (h.key == key) {
                    h.thumb.setImageBitmap(b)
                    h.thumb.alpha = 0f
                    h.thumb.animate().alpha(1f).setDuration(220).start()
                }
            }
        }
    }
}
