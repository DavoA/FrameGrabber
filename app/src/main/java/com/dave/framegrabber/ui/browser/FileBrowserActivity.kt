package com.dave.framegrabber.ui.browser

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
import com.dave.framegrabber.core.*
import com.dave.framegrabber.data.*
import com.dave.framegrabber.export.*
import com.dave.framegrabber.batch.*
import com.dave.framegrabber.ocr.*
import com.dave.framegrabber.ui.*
import com.dave.framegrabber.ui.widget.*
import com.dave.framegrabber.R

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
        /** Ask: pick several videos. Result: [EXTRA_REFS] (file paths or content:// Uris). */
        const val EXTRA_MULTI = "multi"
        const val EXTRA_REFS = "refs"
        private const val REQ_PERM = 10
        private const val REQ_OTHER = 11
    }

    private enum class Mode { BROWSE, ALL, SEARCH }

    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
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
    private val multi by lazy { intent.getBooleanExtra(EXTRA_MULTI, false) }
    /** Multi-select: chosen videos in tap order (key -> ref). */
    private val chosen = LinkedHashMap<String, String>()
    private val adapter by lazy { BrowserAdapter(this, entries, if (multi) chosen.keys else null) }

    private val dp get() = resources.displayMetrics.density
    private val searchRunnable = Runnable { runSearch() }

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
                val wasFling = adapter.flinging
                adapter.flinging = state == android.widget.AbsListView.OnScrollListener.SCROLL_STATE_FLING
                // after a fling, load thumbnails for whatever is now on screen
                if (wasFling && !adapter.flinging) adapter.notifyDataSetChanged()
            }
            override fun onScroll(v: android.widget.AbsListView, first: Int, visible: Int, total: Int) {}
        })

        findViewById<View>(R.id.btnBack).setOnClickListener { onBackPressedCompat() }
        findViewById<View>(R.id.btnOther).setOnClickListener { pickWithOtherApp() }
        if (multi) {
            findViewById<View>(R.id.btnOther).visibility = View.GONE   // other apps give one video
            findViewById<View>(R.id.btnDone).setOnClickListener { finishMulti() }
            updateDone()
        }
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
        io.shutdownNow(); adapter.shutdown()
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
            val extra = if (multi) 76 else 12   // room for "Готово (N)"
            list.setPadding(list.paddingLeft, list.paddingTop, list.paddingRight, max(bars.bottom, ime.bottom) + (extra * dp).toInt())
            findViewById<View>(R.id.btnDone).let { b ->
                (b.layoutParams as android.widget.FrameLayout.LayoutParams).bottomMargin = max(bars.bottom, ime.bottom) + (16 * dp).toInt()
                b.requestLayout()
            }
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
        Places.available(storageRoot).forEachIndexed { i, p ->
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
        load { VideoSource.listFolder(d) }
    }

    private fun loadAll() {
        mode = Mode.ALL
        txtPath.text = "Все видео на телефоне"
        btnUp.isEnabled = true
        btnUp.animate().alpha(1f).setDuration(150).start()
        syncChips()
        load { VideoSource.allVideos(this) }
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
        load { VideoSource.search(this, here, q) }
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
            is Entry.Video -> if (multi) {
                if (chosen.remove(e.key) == null) chosen[e.key] = e.file?.path ?: e.uri.toString()
                adapter.notifyDataSetChanged()
                updateDone()
            } else {
                val data = Intent()
                if (e.file != null) data.putExtra(EXTRA_PATH, e.file.path) else data.data = e.uri
                setResult(RESULT_OK, data)
                finish()
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            }
            is Entry.Header -> {}
        }
    }

    /** Multi-select: title and the "Готово (N)" button. */
    private fun updateDone() {
        val n = chosen.size
        findViewById<TextView>(R.id.txtTitle).text = if (n == 0) "Выбери видео" else "Выбрано: $n"
        val b = findViewById<android.widget.Button>(R.id.btnDone)
        b.text = "Готово ($n)"
        if (n > 0 && b.visibility != View.VISIBLE) {
            b.visibility = View.VISIBLE
            b.translationY = 80 * dp
            b.animate().translationY(0f).setDuration(250).start()
        } else if (n == 0) b.visibility = View.GONE
    }

    private fun finishMulti() {
        setResult(RESULT_OK, Intent().putStringArrayListExtra(EXTRA_REFS, ArrayList(chosen.values)))
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun goUp() {
        if (mode != Mode.BROWSE) { editSearch.setText(""); openDir(dir); return }
        val p = dir.parentFile ?: return
        if (dir == storageRoot) return
        openDir(p)
    }

    private fun pickWithOtherApp() {
        val get = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, com.dave.framegrabber.MainActivity.VIDEO_MIME_TYPES)
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
}
