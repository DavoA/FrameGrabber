package com.dave.framegrabber

import android.animation.AnimatorInflater
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.max

class MainActivity : Activity() {

    companion object {
        private const val REQ_PICK = 1
        private const val REQ_TREE = 2
        private const val ALBUM = "FrameGrabber"
        private const val FILMSTRIP_COUNT = 10
        private const val MAX_BATCH = 2000
        private const val VIEW_MAX_SIDE = 1600
    }

    private val ui = Handler(Looper.getMainLooper())
    /** All calls to [engine] go through this single thread (MediaMetadataRetriever is not thread-safe). */
    private val exec = Executors.newSingleThreadExecutor()
    /** Decoding saved captures for the viewer. */
    private val io = Executors.newSingleThreadExecutor()
    private val previewGen = AtomicInteger()
    private val loadGen = AtomicInteger()
    private val viewGen = AtomicInteger()

    @Volatile private var engine: FrameEngine? = null
    private var baseName = "video"
    private var currentUs = 0L

    /** Frames live in a process-wide store so the background batch can keep adding them. */
    private val captures get() = CaptureStore.captures
    private var videoUri: Uri? = null
    private lateinit var prefs: Prefs
    /** Restoring saved settings into the widgets: don't write them back meanwhile. */
    private var restoring = false
    private var player: android.media.MediaPlayer? = null
    private var playerReady = false
    private val playTicker = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (!p.isPlaying) return
            currentUs = p.currentPosition * 1000L
            seek.progress = p.currentPosition
            txtTimeBadge.text = TimeUtil.format(currentUs)
            ui.postDelayed(this, 40)
        }
    }
    private lateinit var capDir: File
    /** Capture currently opened in the big preview; null = showing the video. */
    private var selected: Capture? = null
    /** Captures that should play the "pop in" animation the first time they're shown. */
    private val pendingAnim = HashSet<Capture>()

    private var batchPulse: ObjectAnimator? = null

    private val dp get() = resources.displayMetrics.density

    // pinned views
    private lateinit var bgView: SynthwaveView
    private lateinit var root: FrameLayout
    private lateinit var content: View
    private lateinit var logo: ImageView
    private lateinit var titleBox: View
    private lateinit var btnPick: ImageButton
    private lateinit var txtInfo: TextView
    private lateinit var previewCard: FrameLayout
    private lateinit var imgPreview: ImageView
    private lateinit var imgCapture: ZoomImageView
    private lateinit var videoSurface: android.view.TextureView
    private lateinit var btnPlay: ImageButton
    private lateinit var placeholder: View
    private lateinit var phIcon: View
    private lateinit var chipMode: View
    private lateinit var chipDot: View
    private lateinit var chipText: TextView
    private lateinit var txtTimeBadge: TextView
    private lateinit var btnCloseCap: View
    private lateinit var btnCapPrev: View
    private lateinit var btnCapNext: View
    private lateinit var flash: View
    private lateinit var actionBar: View
    private lateinit var videoBar: View
    private lateinit var captureBar: View
    private lateinit var editTime: EditText
    private lateinit var btnCapture: View
    private lateinit var list: ListView

    // header (scrolling) views
    private lateinit var header: View
    private lateinit var cardRecent: View
    private lateinit var recentRow: LinearLayout
    private lateinit var txtRangeLen: TextView
    private lateinit var cardExport: View
    private lateinit var swSkipBlur: android.widget.Switch
    private lateinit var swSkipDup: android.widget.Switch
    private lateinit var swSharpest: android.widget.Switch
    private lateinit var radioGifFps: RadioGroup
    private lateinit var radioGifW: RadioGroup
    private lateinit var progressExport: ProgressBar
    private lateinit var txtExport: TextView
    private lateinit var txtDuration: TextView
    private lateinit var seek: SeekBar
    private lateinit var filmstrip: LinearLayout
    private lateinit var radioTabs: RadioGroup
    private lateinit var cardBatch: View
    private lateinit var cardSettings: View
    private lateinit var radioSize: RadioGroup
    private lateinit var customSizeRow: View
    private lateinit var editW: EditText
    private lateinit var editH: EditText
    private lateinit var radioFormat: RadioGroup
    private lateinit var txtQuality: TextView
    private lateinit var seekQuality: SeekBar
    private lateinit var radioBatch: RadioGroup
    private lateinit var lblBatch: TextView
    private lateinit var editBatch: EditText
    private lateinit var editFrom: EditText
    private lateinit var editTo: EditText
    private lateinit var txtBatchHint: TextView
    private lateinit var btnBatch: Button
    private lateinit var progressBatch: ProgressBar
    private lateinit var txtProgress: TextView
    private lateinit var txtCount: TextView
    private lateinit var txtTapHint: View
    private lateinit var capActions: View
    private lateinit var emptyBox: View
    private lateinit var emptyIcon: View

    private val grid = GridAdapter()

    // ================================================================ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        // filesDir, not cacheDir: the system or cleaner apps may wipe the cache while the app is open
        capDir = File(filesDir, "captures").apply {
            // keep frames if this screen is re-created while frames exist / a batch runs in the background
            if (CaptureStore.captures.isEmpty() && !BatchState.running) deleteRecursively()
            mkdirs()
        }
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false) // background under the bars
        setContentView(R.layout.activity_main)
        bgView = findViewById(R.id.bgView)
        AppTheme.apply(this, bgView, AppTheme.get(this), animate = false)
        bindViews()
        header = layoutInflater.inflate(R.layout.header, list, false)
        bindHeader(header)
        list.addHeaderView(header, null, false)
        list.adapter = grid
        list.itemsCanFocus = true
        list.selector = ColorDrawable(Color.TRANSPARENT)
        list.setOnScrollListener(object : android.widget.AbsListView.OnScrollListener {
            override fun onScrollStateChanged(v: android.widget.AbsListView, state: Int) {
                bgView.scrolling = state != android.widget.AbsListView.OnScrollListener.SCROLL_STATE_IDLE
            }
            override fun onScroll(v: android.widget.AbsListView, first: Int, visible: Int, total: Int) {}
        })

        applyInsets()
        restorePrefs()
        setupListeners()
        setupPlayer()
        updateCapturesUi(animate = false)
        updateBatchHint()
        buildRecent()
        if (BatchState.running) showBatchRunning()
        playEntrance()
        startIdleAnimations()

        handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (selected != null) {
            closeCapture()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        bgView.running = AppTheme.get(this) == AppTheme.WAVE
        CaptureStore.listener = captureListener
        BatchState.listener = batchListener
        grid.notifyDataSetChanged(); updateCapturesUi(animate = false)
        if (BatchState.running) batchListener.onProgress()
        else if (batchPulse != null) batchListener.onFinished()   // finished while we were away
    }

    override fun onPause() {
        bgView.running = false
        pausePlayback()
        // while away, the service shows a "done" notification instead of updating this screen
        CaptureStore.listener = null
        BatchState.listener = null
        super.onPause()
    }

    override fun onDestroy() {
        player?.release(); player = null
        val e = engine
        exec.execute { e?.close() }
        exec.shutdown()
        io.shutdown()
        super.onDestroy()
    }

    /** Android 15 draws edge-to-edge: keep content out from under status/nav bars and the keyboard. */
    private fun applyInsets() {
        if (Build.VERSION.SDK_INT < 30) return
        content.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, 0)
            list.setPadding(0, 0, 0, max(bars.bottom, ime.bottom) + (12 * dp).toInt())
            insets
        }
    }

    private fun bindViews() {
        root = findViewById(R.id.root)
        content = findViewById(R.id.content)
        logo = findViewById(R.id.logo)
        titleBox = findViewById(R.id.titleBox)
        btnPick = findViewById(R.id.btnPick)
        txtInfo = findViewById(R.id.txtInfo)
        previewCard = findViewById(R.id.previewCard)
        imgPreview = findViewById(R.id.imgPreview)
        imgCapture = findViewById(R.id.imgCapture)
        placeholder = findViewById(R.id.placeholder)
        phIcon = findViewById(R.id.phIcon)
        chipMode = findViewById(R.id.chipMode)
        chipDot = findViewById(R.id.chipDot)
        chipText = findViewById(R.id.chipText)
        txtTimeBadge = findViewById(R.id.txtTimeBadge)
        btnCloseCap = findViewById(R.id.btnCloseCap)
        btnCapPrev = findViewById(R.id.btnCapPrev)
        btnCapNext = findViewById(R.id.btnCapNext)
        flash = findViewById(R.id.flash)
        actionBar = findViewById(R.id.actionBar)
        videoBar = findViewById(R.id.videoBar)
        captureBar = findViewById(R.id.captureBar)
        editTime = findViewById(R.id.editTime)
        btnCapture = findViewById(R.id.btnCapture)
        list = findViewById(R.id.list)
        videoSurface = findViewById(R.id.videoSurface)
        btnPlay = findViewById(R.id.btnPlay)
        previewCard.clipToOutline = true
        // Preview height adapts to the screen: ~16:10 of the width, but never more than a third of the height
        previewCard.addOnLayoutChangeListener { v, l, _, r, _, ol, _, or_, _ ->
            if (r - l == or_ - ol) return@addOnLayoutChangeListener
            val want = ((r - l) * 0.62f).toInt()
                .coerceIn((170 * dp).toInt(), (resources.displayMetrics.heightPixels * 0.34f).toInt())
            if (v.layoutParams.height != want) v.post { v.layoutParams.height = want; v.requestLayout() }
        }
    }

    private fun bindHeader(h: View) {
        txtDuration = h.findViewById(R.id.txtDuration)
        seek = h.findViewById(R.id.seek)
        filmstrip = h.findViewById(R.id.filmstrip)
        h.findViewById<View>(R.id.timelineBox).clipToOutline = true
        radioTabs = h.findViewById(R.id.radioTabs)
        cardBatch = h.findViewById(R.id.cardBatch)
        cardSettings = h.findViewById(R.id.cardSettings)
        radioSize = h.findViewById(R.id.radioSize)
        customSizeRow = h.findViewById(R.id.customSizeRow)
        editW = h.findViewById(R.id.editW)
        editH = h.findViewById(R.id.editH)
        radioFormat = h.findViewById(R.id.radioFormat)
        txtQuality = h.findViewById(R.id.txtQuality)
        seekQuality = h.findViewById(R.id.seekQuality)
        radioBatch = h.findViewById(R.id.radioBatch)
        lblBatch = h.findViewById(R.id.lblBatch)
        editBatch = h.findViewById(R.id.editBatch)
        editFrom = h.findViewById(R.id.editFrom)
        editTo = h.findViewById(R.id.editTo)
        txtBatchHint = h.findViewById(R.id.txtBatchHint)
        btnBatch = h.findViewById(R.id.btnBatch)
        progressBatch = h.findViewById(R.id.progressBatch)
        txtProgress = h.findViewById(R.id.txtProgress)
        txtCount = h.findViewById(R.id.txtCount)
        txtTapHint = h.findViewById(R.id.txtTapHint)
        capActions = h.findViewById(R.id.capActions)
        emptyBox = h.findViewById(R.id.emptyBox)
        emptyIcon = h.findViewById(R.id.emptyIcon)
        cardRecent = h.findViewById(R.id.cardRecent)
        recentRow = h.findViewById(R.id.recentRow)
        txtRangeLen = h.findViewById(R.id.txtRangeLen)
        cardExport = h.findViewById(R.id.cardExport)
        swSkipBlur = h.findViewById(R.id.swSkipBlur)
        swSkipDup = h.findViewById(R.id.swSkipDup)
        swSharpest = h.findViewById(R.id.swSharpest)
        radioGifFps = h.findViewById(R.id.radioGifFps)
        radioGifW = h.findViewById(R.id.radioGifW)
        progressExport = h.findViewById(R.id.progressExport)
        txtExport = h.findViewById(R.id.txtExport)
    }

    private fun setupListeners() {
        btnPick.setOnClickListener { openBrowser() }
        findViewById<View>(R.id.btnSettings).setOnClickListener { v ->
            v.anim().rotationBy(120f).setDuration(450).setInterpolator(OvershootInterpolator()).start()
            showSettings()
        }
        btnPick.setOnLongClickListener { pickVideo(); true } // long press: pick with another app
        findViewById<View>(R.id.btnPickBig).setOnClickListener { openBrowser() }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                currentUs = p * 1000L
                showTime()
                requestPreview(exact = false)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {
                if (selected != null) closeCapture()
            }
            override fun onStopTrackingTouch(sb: SeekBar) = requestPreview(exact = true)
        })

        findViewById<View>(R.id.btnPrev).setOnClickListener { stepFrame(-1) }
        findViewById<View>(R.id.btnNext).setOnClickListener { stepFrame(+1) }
        editTime.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                val us = TimeUtil.parse(v.text.toString())
                if (us == null) toast("Формат времени: 12.5 или 1:23.5") else seekTo(us)
                hideKeyboard(v)
                true
            } else false
        }
        btnCapture.setOnClickListener { captureCurrent() }

        // capture viewer
        btnCloseCap.setOnClickListener { closeCapture() }
        btnCapPrev.setOnClickListener { stepCapture(-1) }
        btnCapNext.setOnClickListener { stepCapture(+1) }
        findViewById<View>(R.id.actShare).setOnClickListener { selected?.let { shareCapture(it) } }
        findViewById<View>(R.id.actSave).setOnClickListener { selected?.let { saveOne(it) } }
        findViewById<View>(R.id.actJump).setOnClickListener {
            selected?.let { c -> closeCapture(); seekTo(c.timeUs) }
        }
        findViewById<View>(R.id.actDelete).setOnClickListener { deleteSelected() }
        findViewById<View>(R.id.actText).setOnClickListener { selected?.let { recognizeText(it) } }
        imgCapture.onSwipe = { d -> stepCapture(d) }
        btnPlay.setOnClickListener { togglePlayback() }

        // tabs (Пачка / Экспорт / Формат)
        val panels = listOf(cardBatch, cardExport, cardSettings)
        val tabIds = listOf(R.id.tabBatch, R.id.tabExport, R.id.tabSettings)
        radioTabs.setOnCheckedChangeListener { _, id ->
            val to = tabIds.indexOf(id).coerceAtLeast(0)
            val from = panels.indexOfFirst { it.visibility == View.VISIBLE }.coerceAtLeast(0)
            if (from != to) switchPanel(panels[from], panels[to], if (to > from) 1 else -1)
            if (!restoring) prefs.tab = to
        }

        // range
        header.findViewById<View>(R.id.btnMarkIn).setOnClickListener {
            if (engine == null) return@setOnClickListener nudge(btnPick, "Сначала выбери видео")
            editFrom.setText(TimeUtil.format(currentUs)); bump(editFrom)
        }
        header.findViewById<View>(R.id.btnMarkOut).setOnClickListener {
            if (engine == null) return@setOnClickListener nudge(btnPick, "Сначала выбери видео")
            editTo.setText(TimeUtil.format(currentUs)); bump(editTo)
        }

        // export
        header.findViewById<View>(R.id.btnGif).setOnClickListener { exportGif() }
        header.findViewById<View>(R.id.btnClip).setOnClickListener { exportClip() }
        header.findViewById<View>(R.id.btnSheet).setOnClickListener { exportSheet() }
        radioGifFps.setOnCheckedChangeListener { _, id ->
            if (!restoring) prefs.gifFps = when (id) { R.id.fps5 -> 5; R.id.fps15 -> 15; else -> 10 }
        }
        radioGifW.setOnCheckedChangeListener { _, id ->
            if (!restoring) prefs.gifWidth = when (id) { R.id.gw320 -> 320; R.id.gw720 -> 720; else -> 480 }
        }

        // smart capture & batch filters
        swSharpest.setOnCheckedChangeListener { _, on -> if (!restoring) prefs.sharpest = on }
        swSkipBlur.setOnCheckedChangeListener { _, on -> if (!restoring) prefs.skipBlurry = on }
        swSkipDup.setOnCheckedChangeListener { _, on -> if (!restoring) prefs.skipDupes = on }

        // settings
        radioSize.setOnCheckedChangeListener { _, id ->
            if (id == R.id.sizeCustom) showAnimated(customSizeRow) else customSizeRow.visibility = View.GONE
            if (!restoring) prefs.size = when (id) { R.id.size720 -> "720"; R.id.size1080 -> "1080"; R.id.sizeCustom -> "custom"; else -> "orig" }
        }
        radioFormat.setOnCheckedChangeListener { _, id ->
            if (!restoring) prefs.format = when (id) { R.id.fmtPng -> "PNG"; R.id.fmtWebp -> "WEBP"; else -> "JPG" }
            val lossless = id == R.id.fmtPng
            seekQuality.isEnabled = !lossless
            seekQuality.anim().alpha(if (lossless) 0.35f else 1f).setDuration(200).start()
            txtQuality.text = if (lossless) "Качество: без потерь" else "Качество: ${seekQuality.progress}"
        }
        seekQuality.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (p < 1) sb.progress = 1
                txtQuality.text = "Качество: ${sb.progress}"
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) { prefs.quality = sb.progress }
        })

        // batch
        radioBatch.setOnCheckedChangeListener { _, id ->
            if (!restoring) prefs.batchCount = id == R.id.batchCount
            if (id == R.id.batchInterval) { lblBatch.text = "Интервал, сек"; editBatch.setText(prefs.batchInterval) }
            else { lblBatch.text = "Сколько кадров"; editBatch.setText(prefs.batchN) }
            editBatch.anim().cancel()
            editBatch.alpha = 0f
            editBatch.anim().alpha(1f).setDuration(250).start()
            updateBatchHint()
        }
        val hintWatcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { updateBatchHint(); updateRangeLen() }
        }
        editBatch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (restoring) return
                if (radioBatch.checkedRadioButtonId == R.id.batchCount) prefs.batchN = s.toString() else prefs.batchInterval = s.toString()
            }
        })
        val sizeWatcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (!restoring) { prefs.customW = editW.text.toString(); prefs.customH = editH.text.toString() }
            }
        }
        editW.addTextChangedListener(sizeWatcher); editH.addTextChangedListener(sizeWatcher)
        editBatch.addTextChangedListener(hintWatcher)
        editFrom.addTextChangedListener(hintWatcher)
        editTo.addTextChangedListener(hintWatcher)

        btnBatch.setOnClickListener {
            if (BatchState.running) { BatchState.stop(this); btnBatch.text = "Останавливаю…" } else startBatch()
        }
        header.findViewById<View>(R.id.btnSaveAll).setOnClickListener { saveAll() }
        header.findViewById<View>(R.id.btnZip).setOnClickListener { makeZip() }
        header.findViewById<View>(R.id.btnClear).setOnClickListener { confirmClear() }
    }

    // ================================================================ loading video

    /** Our own ZArchiver-style file browser (FileBrowserActivity). */
    private fun openBrowser() {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(this, FileBrowserActivity::class.java), REQ_PICK)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    /**
     * ACTION_GET_CONTENT + chooser (not ACTION_OPEN_DOCUMENT): the system "storage" picker only lists
     * built-in document providers, while GET_CONTENT lets ANY app that can hand out a file answer —
     * ZArchiver, Gallery, Google Photos, file managers, Telegram etc.
     */
    private fun pickVideo() {
        val get = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(Intent.createChooser(get, "Выбрать видео через…"), REQ_PICK)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_TREE && resultCode == RESULT_OK) {
            val tree = data?.data ?: return
            runCatching {
                contentResolver.takePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            prefs.folderTree = tree.toString()
            prefs.folderTreeName = Uri.decode(DocumentsContract.getTreeDocumentId(tree)).replace("primary:", "Память/")
            settingsFolderLabel?.text = Output.describe(this)
            captures.forEach { it.galleryUri = null }  // next save goes to the new folder
            toast("Кадры будут сохраняться в: ${Output.describe(this)}")
            return
        }
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) return
        val path = data.getStringExtra(FileBrowserActivity.EXTRA_PATH)
        val uri = if (path != null) Uri.fromFile(File(path)) else data.data
        uri?.let { loadVideo(it) }
    }

    private fun handleIncoming(intent: Intent?) {
        if (intent == null) return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_SEND -> streamExtra(intent)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        uri?.let { loadVideo(it) }
    }

    @Suppress("DEPRECATION")
    private fun streamExtra(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_STREAM)

    private fun loadVideo(uri: Uri) {
        if (selected != null) closeCapture()
        pausePlayback()
        txtInfo.text = "Открываю…"
        val gen = loadGen.incrementAndGet()
        exec.execute {
            val res = runCatching { FrameEngine(this, uri) }
            ui.post {
                if (gen != loadGen.get()) { res.getOrNull()?.let { e -> exec.execute { e.close() } }; return@post }
                res.onSuccess { e -> onVideoLoaded(e, uri) }
                    .onFailure {
                        txtInfo.text = "Не удалось открыть видео"
                        showError("Не удалось открыть видео", it)
                    }
            }
        }
    }

    private fun onVideoLoaded(e: FrameEngine, uri: Uri) {
        engine?.let { old -> exec.execute { old.close() } }
        engine = e
        videoUri = uri
        baseName = displayName(uri)
        currentUs = 0
        prepareVideo(uri, e)
        rememberRecent(uri, e)
        if (cardRecent.visibility == View.VISIBLE) {
            cardRecent.anim().alpha(0f).translationY(-20 * dp).setDuration(220)
                .withEndAction { cardRecent.visibility = View.GONE; cardRecent.alpha = 1f; cardRecent.translationY = 0f }.start()
        }
        seek.max = (e.durationUs / 1000).toInt()
        seek.progress = 0
        editFrom.setText("")
        editTo.setText("")
        txtInfo.text = "$baseName · ${e.width}×${e.height} · %.0f fps".format(e.fps)
        txtDuration.text = TimeUtil.format(e.durationUs)
        showTime()
        updateBatchHint()
        updateRangeLen()

        // placeholder out, preview in
        if (placeholder.visibility == View.VISIBLE) {
            placeholder.anim().alpha(0f).scaleX(0.9f).scaleY(0.9f).setDuration(250)
                .withEndAction { placeholder.visibility = View.GONE }.start()
        }
        imgPreview.alpha = 0f
        imgPreview.scaleX = 1.08f; imgPreview.scaleY = 1.08f
        imgPreview.anim().alpha(1f).scaleX(1f).scaleY(1f).setDuration(550)
            .setInterpolator(DecelerateInterpolator(2f)).start()
        popIn(chipMode, 200)
        popIn(txtTimeBadge, 280)
        setChip(live = true, text = "ВИДЕО")

        requestPreview(exact = true)
        buildFilmstrip(e)
    }

    private fun displayName(uri: Uri): String {
        val name = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "video"
        return name.substringBeforeLast('.').replace(Regex("[^\\p{L}\\p{N}._-]+"), "_").take(60).ifEmpty { "video" }
    }

    /** Thumbnails fill the timeline track; the seek bar sits on top of them. */
    private fun buildFilmstrip(e: FrameEngine) {
        filmstrip.removeAllViews()
        val times = (0 until FILMSTRIP_COUNT).map { i -> e.lastUs * i / (FILMSTRIP_COUNT - 1) }
        val views = times.map {
            ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                scaleType = ImageView.ScaleType.CENTER_CROP
                alpha = 0f
            }.also { filmstrip.addView(it) }
        }
        times.forEachIndexed { i, t ->
            exec.execute {
                if (engine !== e) return@execute
                val b = runCatching { e.scaledFrame(t, 200, 200, exact = false) }.getOrNull()
                ui.post {
                    if (engine === e && b != null) {
                        views[i].setImageBitmap(b)
                        views[i].translationY = 14 * dp
                        views[i].anim().alpha(1f).translationY(0f).setStartDelay(40L * i)
                            .setDuration(320).setInterpolator(DecelerateInterpolator()).start()
                    }
                }
            }
        }
    }

    // ================================================================ seeking / preview

    private fun seekTo(us: Long) {
        val e = engine ?: return
        currentUs = us.coerceIn(0L, e.lastUs)
        seek.progress = (currentUs / 1000).toInt()
        showTime()
        requestPreview(exact = true, animate = true)
    }

    private fun stepFrame(dir: Int) {
        val e = engine ?: return
        if (selected != null) closeCapture()
        seekTo(currentUs + dir * e.frameUs)
    }

    /** Only the most recent request is rendered; stale ones are skipped while scrubbing. */
    private fun requestPreview(exact: Boolean, animate: Boolean = false) {
        val e = engine ?: return
        val gen = previewGen.incrementAndGet()
        val us = currentUs
        exec.execute {
            if (gen != previewGen.get() || engine !== e) return@execute
            val b = runCatching { e.scaledFrame(us, 1280, 1280, exact) }.getOrNull()
            ui.post {
                if (gen == previewGen.get() && b != null) {
                    imgPreview.setImageBitmap(b)
                    if (animate && selected == null) {
                        imgPreview.alpha = 0.55f
                        imgPreview.anim().alpha(1f).setDuration(220).start()
                    }
                }
            }
        }
    }

    // ================================================================ capturing

    private fun readSpec(): OutputSpec? {
        val fmt = when (radioFormat.checkedRadioButtonId) {
            R.id.fmtPng -> Fmt.PNG
            R.id.fmtWebp -> Fmt.WEBP
            else -> Fmt.JPG
        }
        val size = when (radioSize.checkedRadioButtonId) {
            R.id.size720 -> 1280 to 720
            R.id.size1080 -> 1920 to 1080
            R.id.sizeCustom -> {
                val w = editW.text.toString().toIntOrNull()
                val h = editH.text.toString().toIntOrNull()
                if (w == null || h == null || w !in 16..8192 || h !in 16..8192) {
                    toast("Свой размер: от 16 до 8192 пикселей"); return null
                }
                w to h
            }
            else -> null
        }
        return OutputSpec(size, fmt, seekQuality.progress.coerceAtLeast(1))
    }

    /** Runs on a background thread with the given engine. */
    private fun makeCapture(e: FrameEngine, us0: Long, spec: OutputSpec, sharpest: Boolean, index: Int): Capture {
        val us = if (sharpest) FrameCapture.sharpestNear(e, us0) else us0
        val stem = Naming.stem(prefs.namePattern, baseName, us, index)
        val shot = FrameCapture.capture(e, us, spec, capDir, baseName, stem)
        return Capture(shot.file, shot.thumb, us, spec.format, shot.width, shot.height, shot.exact, sharpened = us != us0)
    }

    private fun captureCurrent() {
        val e = engine ?: return nudge(btnPick, "Сначала выбери видео")
        val spec = readSpec() ?: return
        pausePlayback()
        val us = currentUs
        val sharpest = swSharpest.isChecked
        val index = captures.size + 1
        if (sharpest) {   // looking at neighbours takes a moment: spin the shutter meanwhile
            btnCapture.anim().rotationBy(360f).setDuration(900).setInterpolator(AccelerateDecelerateInterpolator()).start()
        }
        exec.execute {
            val res = runCatching { makeCapture(e, us, spec, sharpest, index) }
            ui.post {
                res.onSuccess { c ->
                    shutterFlash()
                    if (c.sharpened) toast("Взят более резкий кадр: ${TimeUtil.format(c.timeUs)}")
                    flyToCaptures(c.thumb) { CaptureStore.add(c) }
                }.onFailure { showError("Не получилось снять кадр", it) }
            }
        }
    }

    /** Computes the frame times for the batch, or returns null after telling the user what's wrong. */
    private fun batchTimes(e: FrameEngine, quiet: Boolean): List<Long>? {
        fun fail(msg: String): List<Long>? { if (!quiet) toast(msg); return null }
        val from = TimeUtil.parse(editFrom.text.toString())?.coerceIn(0L, e.lastUs) ?: 0L
        val to = TimeUtil.parse(editTo.text.toString())?.coerceIn(0L, e.lastUs) ?: e.lastUs
        if (to < from) return fail("Конец отрезка должен быть позже начала")
        val value = editBatch.text.toString().replace(',', '.').toDoubleOrNull()
        return if (radioBatch.checkedRadioButtonId == R.id.batchInterval) {
            if (value == null || value <= 0) return fail("Укажи интервал больше 0")
            val step = (value * 1_000_000).toLong().coerceAtLeast(e.frameUs)
            val count = (to - from) / step + 1
            if (count > MAX_BATCH) return fail("Получится $count кадров — максимум $MAX_BATCH. Увеличь интервал.")
            (0 until count).map { from + it * step }
        } else {
            val n = value?.toInt() ?: 0
            if (n < 1 || n > MAX_BATCH) return fail("Количество: от 1 до $MAX_BATCH")
            if (n == 1) listOf(from + (to - from) / 2)
            else (0 until n).map { from + (to - from) * it / (n - 1) }
        }
    }

    private fun updateBatchHint() {
        if (!::txtBatchHint.isInitialized) return
        val e = engine
        txtBatchHint.text = if (e == null) "Сначала выбери видео"
        else batchTimes(e, quiet = true)?.let { "Будет снято кадров: ${it.size}" } ?: "Проверь значения"
    }

    private fun startBatch() {
        val e = engine ?: return nudge(btnPick, "Сначала выбери видео")
        val uri = videoUri ?: return
        val spec = readSpec() ?: return
        hideKeyboard(editBatch)
        val times = batchTimes(e, quiet = false) ?: return
        val go = {
            askNotificationPermission()
            BatchState.start(this, BatchJob(
                uri, baseName, times, spec, capDir, prefs.namePattern,
                swSkipBlur.isChecked, swSkipDup.isChecked, swSharpest.isChecked
            ))
            showBatchRunning()
        }
        if (times.size > 300) {
            AlertDialog.Builder(this)
                .setMessage("Будет снято до ${times.size} кадров. Это может занять время — можно свернуть приложение, съёмка продолжится в фоне. Продолжить?")
                .setPositiveButton("Да") { _, _ -> go() }
                .setNegativeButton("Отмена", null)
                .show()
        } else go()
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 77)
        }
    }

    private fun showBatchRunning() {
        btnBatch.text = "Остановить"
        btnBatch.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_stop, 0, 0, 0)
        if (batchPulse == null) batchPulse = ObjectAnimator.ofFloat(btnBatch, View.ALPHA, 1f, 0.72f).apply {
            duration = 650; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE; start()
        }
        txtProgress.visibility = View.VISIBLE
        txtProgress.text = "Подготовка… (можно свернуть приложение)"
        progressBatch.max = max(1, BatchState.total)
        if (progressBatch.visibility != View.VISIBLE) showAnimated(progressBatch)
    }

    private val batchListener = object : BatchState.Listener {
        override fun onProgress() {
            val st = BatchState
            if (progressBatch.visibility != View.VISIBLE) showBatchRunning()
            progressBatch.max = max(1, st.total)
            progressBatch.setProgress(st.index, true)
            txtProgress.text = buildString {
                append("Кадр ${st.index} из ${st.total}")
                if (st.blurry + st.dupes > 0) append(" · пропущено ${st.blurry + st.dupes}")
            }
        }
        override fun onFinished() {
            batchPulse?.cancel(); batchPulse = null
            btnBatch.alpha = 1f
            btnBatch.text = "Снять пачку кадров"
            btnBatch.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_burst, 0, 0, 0)
            progressBatch.anim().alpha(0f).setStartDelay(600).setDuration(300)
                .withEndAction { progressBatch.visibility = View.GONE; progressBatch.alpha = 1f }.start()
            txtProgress.visibility = View.VISIBLE
            txtProgress.text = BatchState.summary ?: "Готово"
            if (BatchState.done > 0) list.post { list.smoothScrollToPositionFromTop(1, 0) }
        }
    }

    private var settingsFolderLabel: TextView? = null

    private val captureListener = object : CaptureStore.Listener {
        override fun onAdded(c: Capture) = addCapture(c, scroll = !BatchState.running)
    }

    /** UI side of a new frame (the frame is already in [CaptureStore]). */
    private fun addCapture(c: Capture, scroll: Boolean = true) {
        pendingAnim.add(c)
        grid.notifyDataSetChanged()
        updateCapturesUi()
        if (selected != null) updateViewerChrome()
        if (scroll) list.post { list.smoothScrollToPosition(grid.count) }
    }

    // ================================================================ capture viewer (big preview)

    private fun openCapture(c: Capture, dir: Int = 0) {
        val wasOpen = selected != null
        if (selected === c) return
        selected = c
        grid.notifyDataSetChanged()
        hideKeyboard(editTime)

        imgCapture.anim().cancel()
        imgCapture.resetZoom()
        imgCapture.setImageBitmap(c.thumb) // instant low-res, full image follows
        imgCapture.visibility = View.VISIBLE
        val w = max(previewCard.width, 1).toFloat()
        if (!wasOpen) {
            imgCapture.alpha = 0f
            imgCapture.translationX = 0f
            imgCapture.scaleX = 0.86f; imgCapture.scaleY = 0.86f
            imgCapture.anim().alpha(1f).scaleX(1f).scaleY(1f).setDuration(380)
                .setInterpolator(OvershootInterpolator(1.1f)).start()
            imgPreview.anim().alpha(0f).setDuration(250).start()
            enterViewerChrome()
        } else if (dir != 0) {
            imgCapture.translationX = dir * w * 0.35f
            imgCapture.alpha = 0.3f
            imgCapture.scaleX = 1f; imgCapture.scaleY = 1f
            imgCapture.anim().translationX(0f).alpha(1f).setDuration(300)
                .setInterpolator(DecelerateInterpolator(1.6f)).start()
        } else {
            imgCapture.alpha = 0.4f
            imgCapture.scaleX = 0.94f; imgCapture.scaleY = 0.94f
            imgCapture.anim().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260).start()
        }
        updateViewerChrome()

        val gen = viewGen.incrementAndGet()
        io.execute {
            val b = runCatching { decodeSampled(c.file, VIEW_MAX_SIDE) }.getOrNull()
            ui.post { if (gen == viewGen.get() && selected === c && b != null) imgCapture.setImageBitmap(b) }
        }
    }

    private fun closeCapture() {
        if (selected == null) return
        selected = null
        viewGen.incrementAndGet()
        grid.notifyDataSetChanged()
        imgCapture.anim().cancel()
        imgCapture.anim().alpha(0f).scaleX(0.9f).scaleY(0.9f).setDuration(220)
            .withEndAction { imgCapture.visibility = View.GONE; imgCapture.setImageDrawable(null) }.start()
        imgPreview.anim().alpha(1f).setDuration(260).start()
        exitViewerChrome()
        if (engine != null) setChip(live = true, text = "ВИДЕО") else chipMode.visibility = View.GONE
        showTime()
    }

    private fun stepCapture(d: Int) {
        val c = selected ?: return
        val i = captures.indexOf(c) + d
        if (i in captures.indices) openCapture(captures[i], d)
        else shake(imgCapture, d)
    }

    private fun deleteSelected() {
        val c = selected ?: return
        val idx = captures.indexOf(c)
        imgCapture.anim().cancel()
        imgCapture.anim().alpha(0f).scaleX(0.6f).scaleY(0.6f).translationY(40 * dp).setDuration(240)
            .setInterpolator(AccelerateInterpolator()).withEndAction {
                imgCapture.translationY = 0f
                captures.remove(c)
                pendingAnim.remove(c)
                c.file.delete()
                updateCapturesUi()
                if (captures.isEmpty()) closeCapture() // `selected` is still c, so the exit animation runs
                else openCaptureInPlace(captures[idx.coerceAtMost(captures.size - 1)])
            }.start()
    }

    /** Opens a capture while the viewer is already showing (used after delete). */
    private fun openCaptureInPlace(c: Capture) {
        selected = c
        grid.notifyDataSetChanged()
        imgCapture.resetZoom()
        imgCapture.setImageBitmap(c.thumb)
        imgCapture.alpha = 0f
        imgCapture.scaleX = 0.85f; imgCapture.scaleY = 0.85f
        imgCapture.anim().alpha(1f).scaleX(1f).scaleY(1f).setDuration(300)
            .setInterpolator(OvershootInterpolator(1.4f)).start()
        updateViewerChrome()
        val gen = viewGen.incrementAndGet()
        io.execute {
            val b = runCatching { decodeSampled(c.file, VIEW_MAX_SIDE) }.getOrNull()
            ui.post { if (gen == viewGen.get() && selected === c && b != null) imgCapture.setImageBitmap(b) }
        }
    }

    private fun updateViewerChrome() {
        val c = selected ?: return
        val i = captures.indexOf(c)
        setChip(live = false, text = "КАДР ${i + 1} ИЗ ${captures.size}")
        // real file resolution, so it's obvious the original quality was saved
        val size = android.text.format.Formatter.formatShortFileSize(this, c.file.length())
        txtTimeBadge.text = buildString {
            if (c.sharpened) append("★ ")   // the sharper neighbour was taken
            append("${c.width}×${c.height} · ${c.fmt.name} · $size")
            append("  ")
            if (!c.exact) append("≈")
            append(TimeUtil.format(c.timeUs))
        }
        btnCapPrev.anim().alpha(if (i > 0) 1f else 0.3f).setDuration(150).start()
        btnCapNext.anim().alpha(if (i < captures.size - 1) 1f else 0.3f).setDuration(150).start()
    }

    private fun enterViewerChrome() {
        btnPlay.anim().alpha(0f).setDuration(150).withEndAction { btnPlay.visibility = View.GONE }.start()
        listOf(btnCloseCap, btnCapPrev, btnCapNext).forEachIndexed { i, v -> popIn(v, 60L * i) }
        if (chipMode.visibility != View.VISIBLE) popIn(chipMode, 0)
        if (txtTimeBadge.visibility != View.VISIBLE) popIn(txtTimeBadge, 0)
        swapBars(out = videoBar, into = captureBar)
    }

    private fun exitViewerChrome() {
        listOf(btnCloseCap, btnCapPrev, btnCapNext).forEach { v ->
            v.anim().cancel()
            v.anim().alpha(0f).scaleX(0.6f).scaleY(0.6f).setDuration(150)
                .withEndAction { v.visibility = View.GONE }.start()
        }
        swapBars(out = captureBar, into = videoBar)
        if (playerReady) popIn(btnPlay, 150)
    }

    private fun decodeSampled(file: File, maxSide: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        var sample = 1
        while (max(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    // ================================================================ saving / sharing

    /** Saves to the chosen folder (General settings) or Pictures/FrameGrabber. */
    private fun saveToGallery(c: Capture): Uri {
        c.galleryUri?.let { return it }
        val uri = Output.save(this, c.file, c.file.name, c.fmt.mime, Output.Kind.IMAGE)
        c.galleryUri = uri
        return uri
    }

    private fun saveOne(c: Capture) = Thread {
        val r = runCatching { saveToGallery(c) }
        ui.post {
            r.onSuccess { toast("Сохранено: ${Output.describe(this)}"); shutterFlash(soft = true) }
                .onFailure { showError("Не удалось сохранить", it) }
        }
    }.start()

    private fun shareCapture(c: Capture) = Thread {
        val r = runCatching { saveToGallery(c) }
        ui.post { r.onSuccess { share(it, c.fmt.mime) }.onFailure { showError("Не удалось поделиться", it) } }
    }.start()

    private fun saveAll() {
        if (captures.isEmpty()) return
        val snapshot = captures.toList()
        toast("Сохраняю ${snapshot.size} в галерею…")
        Thread {
            var ok = 0
            for (c in snapshot) if (runCatching { saveToGallery(c) }.isSuccess) ok++
            ui.post { toast("Сохранено $ok из ${snapshot.size}: ${Output.describe(this)}") }
        }.start()
    }

    private fun makeZip() {
        if (captures.isEmpty()) return
        val snapshot = captures.toList()
        toast("Собираю ZIP…")
        Thread {
            val res = runCatching {
                val name = "${baseName}_frames_${System.currentTimeMillis() / 1000}.zip"
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$ALBUM")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val col = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val uri = contentResolver.insert(col, values) ?: error("Не удалось создать ZIP")
                contentResolver.openOutputStream(uri)!!.use { os ->
                    ZipOutputStream(os.buffered()).use { zip ->
                        zip.setLevel(1) // images are already compressed
                        for (c in snapshot) {
                            zip.putNextEntry(ZipEntry(c.file.name))
                            c.file.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
                name to uri
            }
            ui.post {
                res.onSuccess { (name, uri) ->
                    AlertDialog.Builder(this)
                        .setTitle("ZIP готов")
                        .setMessage("Сохранён в Загрузки/$ALBUM/$name\n(${snapshot.size} кадров)")
                        .setPositiveButton("Поделиться") { _, _ -> share(uri, "application/zip") }
                        .setNegativeButton("OK", null)
                        .show()
                }.onFailure { showError("Ошибка ZIP", it) }
            }
        }.start()
    }

    private fun share(uri: Uri, mime: String) {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(i, "Поделиться"))
    }

    private fun confirmClear() {
        if (captures.isEmpty()) return
        AlertDialog.Builder(this)
            .setMessage("Удалить все ${captures.size} снятых кадров из списка?\n(Уже сохранённые в галерею останутся.)")
            .setPositiveButton("Удалить") { _, _ ->
                if (selected != null) closeCapture()
                captures.forEach { it.file.delete() }
                captures.clear()
                pendingAnim.clear()
                grid.notifyDataSetChanged()
                updateCapturesUi()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ================================================================ general settings

    /** "Общие настройки": theme picker. Applies instantly, nothing is lost (no activity restart). */
    private fun showSettings() {
        val v = layoutInflater.inflate(R.layout.dialog_settings, null)
        val optClassic = v.findViewById<View>(R.id.optClassic)
        val optWave = v.findViewById<View>(R.id.optWave)
        val chkClassic = v.findViewById<View>(R.id.chkClassic)
        val chkWave = v.findViewById<View>(R.id.chkWave)
        val preview = v.findViewById<SynthwaveView>(R.id.wavePreview)
        v.findViewById<View>(R.id.waveSwatch).clipToOutline = true

        fun show(t: AppTheme, animate: Boolean) {
            optClassic.isSelected = t == AppTheme.CLASSIC
            optWave.isSelected = t == AppTheme.WAVE
            for ((chk, on) in listOf(chkClassic to (t == AppTheme.CLASSIC), chkWave to (t == AppTheme.WAVE))) {
                if (!animate) { chk.alpha = if (on) 1f else 0f; chk.scaleX = 1f; chk.scaleY = 1f; continue }
                if (on) { chk.scaleX = 0.3f; chk.scaleY = 0.3f; chk.anim().alpha(1f).scaleX(1f).scaleY(1f)
                    .setInterpolator(OvershootInterpolator(3f)).setDuration(300).start() }
                else chk.anim().alpha(0f).setDuration(150).start()
            }
        }
        fun choose(t: AppTheme) {
            if (AppTheme.get(this) == t) return
            AppTheme.set(this, t)
            show(t, animate = true)
            AppTheme.apply(this, bgView, t, animate = true)
        }
        show(AppTheme.get(this), animate = false)
        optClassic.setOnClickListener { choose(AppTheme.CLASSIC) }
        optWave.setOnClickListener { choose(AppTheme.WAVE) }

        // file names
        val editPattern = v.findViewById<EditText>(R.id.editPattern)
        val txtPreview = v.findViewById<TextView>(R.id.txtPatternPreview)
        fun updatePreview() {
            txtPreview.text = "Пример: " + Naming.stem(editPattern.text.toString(), baseName, 83_500_000L, 1) + ".jpg"
        }
        editPattern.setText(prefs.namePattern)
        updatePreview()
        editPattern.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                prefs.namePattern = s.toString().ifBlank { Prefs.DEFAULT_PATTERN }; updatePreview()
            }
        })

        // save folder
        val txtFolder = v.findViewById<TextView>(R.id.txtFolder)
        txtFolder.text = Output.describe(this)
        settingsFolderLabel = txtFolder
        v.findViewById<View>(R.id.rowFolder).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Куда сохранять кадры")
                .setItems(arrayOf("Выбрать папку на телефоне…", "По умолчанию: Pictures/${Prefs.ALBUM}")) { _, w ->
                    if (w == 0) {
                        @Suppress("DEPRECATION")
                        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_TREE)
                    } else {
                        prefs.folderTree?.let { old ->
                            runCatching { contentResolver.releasePersistableUriPermission(Uri.parse(old),
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                        }
                        prefs.folderTree = null; prefs.folderTreeName = null
                        txtFolder.text = Output.describe(this)
                        captures.forEach { it.galleryUri = null }
                    }
                }.show()
        }

        // OCR languages
        val langBox = v.findViewById<LinearLayout>(R.id.ocrLangs)
        val chosen = prefs.ocrLangs.split("+").toMutableSet()
        for ((code, title) in Ocr.LANGS) {
            langBox.addView(android.widget.CheckBox(this).apply {
                text = title
                setTextColor(getColor(R.color.text))
                buttonTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.accent))
                isChecked = code in chosen
                setOnCheckedChangeListener { cb, on ->
                    if (on) chosen += code else chosen -= code
                    if (chosen.isEmpty()) { chosen += code; cb.isChecked = true; toast("Нужен хотя бы один язык") }
                    prefs.ocrLangs = Ocr.LANGS.keys.filter { it in chosen }.joinToString("+")
                }
            })
        }

        val dlg = AlertDialog.Builder(this).setView(v).create()
        v.findViewById<View>(R.id.btnDone).setOnClickListener { dlg.dismiss() }
        dlg.setOnDismissListener { preview.running = false; settingsFolderLabel = null }
        dlg.show()
        // options slide in
        listOf(optClassic, optWave).forEachIndexed { i, o ->
            o.alpha = 0f; o.translationY = 24 * dp
            o.anim().alpha(1f).translationY(0f).setStartDelay(80L + 70L * i).setDuration(300).start()
        }
    }

    // ================================================================ remembered settings

    private fun restorePrefs() {
        restoring = true
        radioFormat.check(when (prefs.format) { "PNG" -> R.id.fmtPng; "WEBP" -> R.id.fmtWebp; else -> R.id.fmtJpg })
        radioSize.check(when (prefs.size) { "720" -> R.id.size720; "1080" -> R.id.size1080; "custom" -> R.id.sizeCustom; else -> R.id.sizeOrig })
        customSizeRow.visibility = if (prefs.size == "custom") View.VISIBLE else View.GONE
        editW.setText(prefs.customW); editH.setText(prefs.customH)
        seekQuality.progress = prefs.quality
        val lossless = prefs.format == "PNG"
        seekQuality.isEnabled = !lossless; seekQuality.alpha = if (lossless) 0.35f else 1f
        txtQuality.text = if (lossless) "Качество: без потерь" else "Качество: ${prefs.quality}"
        swSharpest.isChecked = prefs.sharpest
        swSkipBlur.isChecked = prefs.skipBlurry
        swSkipDup.isChecked = prefs.skipDupes
        if (prefs.batchCount) {
            radioBatch.check(R.id.batchCount); lblBatch.text = "Сколько кадров"; editBatch.setText(prefs.batchN)
        } else {
            radioBatch.check(R.id.batchInterval); lblBatch.text = "Интервал, сек"; editBatch.setText(prefs.batchInterval)
        }
        radioGifFps.check(when (prefs.gifFps) { 5 -> R.id.fps5; 15 -> R.id.fps15; else -> R.id.fps10 })
        radioGifW.check(when (prefs.gifWidth) { 320 -> R.id.gw320; 720 -> R.id.gw720; else -> R.id.gw480 })
        val panels = listOf(cardBatch, cardExport, cardSettings)
        val tab = prefs.tab.coerceIn(0, 2)
        panels.forEachIndexed { i, p -> p.visibility = if (i == tab) View.VISIBLE else View.GONE }
        radioTabs.check(listOf(R.id.tabBatch, R.id.tabExport, R.id.tabSettings)[tab])
        restoring = false
    }

    // ================================================================ range (shared by batch & export)

    /** Selected range in µs, or a toast + null. */
    private fun rangeUs(e: FrameEngine): Pair<Long, Long>? {
        val from = TimeUtil.parse(editFrom.text.toString())?.coerceIn(0L, e.lastUs) ?: 0L
        val to = TimeUtil.parse(editTo.text.toString())?.coerceIn(0L, e.lastUs) ?: e.lastUs
        if (to <= from) { toast("Конец отрезка должен быть позже начала"); return null }
        return from to to
    }

    private fun updateRangeLen() {
        if (!::txtRangeLen.isInitialized) return
        val e = engine ?: run { txtRangeLen.text = "всё видео"; return }
        val from = TimeUtil.parse(editFrom.text.toString())?.coerceIn(0L, e.lastUs) ?: 0L
        val to = TimeUtil.parse(editTo.text.toString())?.coerceIn(0L, e.lastUs) ?: e.lastUs
        txtRangeLen.text = if (editFrom.text.isBlank() && editTo.text.isBlank()) "всё видео · ${TimeUtil.format(e.durationUs)}"
            else if (to > from) "длина ${TimeUtil.format(to - from)}" else "проверь отрезок"
    }

    // ================================================================ export: GIF, clip, contact sheet

    private var exporting = false

    /**
     * Temp file for an export. The folder is (re)created every time: on some phones the app's cache
     * folder gets wiped by the system or a cleaner app while the app is open.
     */
    private fun tempFile(name: String): File {
        val dir = File(cacheDir, "export")
        if (!dir.isDirectory && !dir.mkdirs()) error("Не удалось создать временную папку: ${dir.path}")
        return File(dir, name).also { it.delete() }
    }

    private fun startExport(title: String): Boolean {
        if (exporting) { toast("Подожди, идёт экспорт"); return false }
        exporting = true
        progressExport.progress = 0
        showAnimated(progressExport)
        txtExport.visibility = View.VISIBLE
        txtExport.text = title
        return true
    }

    private fun exportProgress(f: Float, label: String) = ui.post {
        progressExport.setProgress((f * 1000).toInt(), true)
        txtExport.text = "$label ${(f * 100).toInt()}%"
    }

    private fun endExport(result: Result<Pair<Uri, String>>, label: String) = ui.post {
        exporting = false
        progressExport.anim().alpha(0f).setStartDelay(400).setDuration(250)
            .withEndAction { progressExport.visibility = View.GONE; progressExport.alpha = 1f }.start()
        result.onSuccess { (uri, mime) ->
            txtExport.text = "$label готов: ${Output.describe(this)}"
            AlertDialog.Builder(this)
                .setTitle("$label готов")
                .setMessage("Сохранено: ${Output.describe(this)}")
                .setPositiveButton("Поделиться") { _, _ -> share(uri, mime) }
                .setNegativeButton("OK", null)
                .show()
        }.onFailure { txtExport.text = "Ошибка: ${it.message}"; showError("Не получилось: $label", it) }
    }

    private fun exportGif() {
        val e = engine ?: return nudge(btnPick, "Сначала выбери видео")
        val uri = videoUri ?: return
        val (from, to) = rangeUs(e) ?: return
        val fps = prefs.gifFps
        val width = prefs.gifWidth
        val maxLen = 20_000_000L
        if (to - from > maxLen) {
            toast("GIF до 20 секунд — укажи отрезок короче (сейчас ${TimeUtil.format(to - from)})"); bump(header.findViewById(R.id.cardRange)); return
        }
        if (!startExport("Делаю GIF…")) return
        Thread {
            val r = runCatching {
                val tmp = tempFile("export.gif")
                Exporters.gif(this, uri, from, to, fps, width, tmp) { exportProgress(it, "GIF"); true }
                val name = Naming.stem(prefs.namePattern, baseName, from, 1) + ".gif"
                Output.save(this, tmp, name, "image/gif", Output.Kind.IMAGE).also { tmp.delete() } to "image/gif"
            }
            endExport(r, "GIF")
        }.start()
    }

    private fun exportClip() {
        val e = engine ?: return nudge(btnPick, "Сначала выбери видео")
        val uri = videoUri ?: return
        val (from, to) = rangeUs(e) ?: return
        if (!startExport("Вырезаю клип…")) return
        Thread {
            val r = runCatching {
                val tmp = tempFile("export.mp4")
                Exporters.clip(this, uri, from, to, tmp) { exportProgress(it, "Клип"); true }
                val name = Naming.stem(prefs.namePattern, baseName, from, 1) + "_clip.mp4"
                Output.save(this, tmp, name, "video/mp4", Output.Kind.VIDEO).also { tmp.delete() } to "video/mp4"
            }
            endExport(r, "Клип")
        }.start()
    }

    private fun exportSheet() {
        if (captures.isEmpty()) { toast("Сначала сними кадры — лист собирается из них"); return }
        val snapshot = captures.sortedBy { it.timeUs }
        val e = engine
        val sub = buildString {
            append("${snapshot.size} кадров")
            if (e != null) append(" · ${e.width}×${e.height} · ${TimeUtil.format(e.durationUs)}")
        }
        if (!startExport("Собираю лист кадров…")) return
        Thread {
            val r = runCatching {
                exportProgress(0.3f, "Лист")
                val bmp = ContactSheet.build(snapshot, baseName, sub)
                exportProgress(0.8f, "Лист")
                val tmp = tempFile("sheet.jpg")
                tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                bmp.recycle()
                val name = Naming.stem(prefs.namePattern, baseName, 0, 1) + "_sheet.jpg"
                Output.save(this, tmp, name, "image/jpeg", Output.Kind.IMAGE).also { tmp.delete() } to "image/jpeg"
            }
            endExport(r, "Лист кадров")
        }.start()
    }

    // ================================================================ playback with sound

    private fun setupPlayer() {
        videoSurface.surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                player?.setSurface(android.view.Surface(st)); fitVideo()
            }
            override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) = fitVideo()
            override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
        }
    }

    private fun prepareVideo(uri: Uri, e: FrameEngine) {
        player?.release()
        playerReady = false
        btnPlay.visibility = View.GONE
        val mp = android.media.MediaPlayer()
        player = mp
        runCatching {
            mp.setDataSource(this, uri)
            mp.setOnPreparedListener {
                playerReady = true
                popIn(btnPlay, 350)
                fitVideo()
            }
            mp.setOnCompletionListener { pausePlayback() }
            mp.setOnErrorListener { _, _, _ -> playerReady = false; btnPlay.visibility = View.GONE; true }
            if (videoSurface.isAvailable) mp.setSurface(android.view.Surface(videoSurface.surfaceTexture))
            mp.prepareAsync()
        }.onFailure { player = null }
    }

    /** Letterbox the video inside the TextureView (TextureView stretches by default). */
    private fun fitVideo() {
        val e = engine ?: return
        val vw = videoSurface.width.toFloat(); val vh = videoSurface.height.toFloat()
        if (vw == 0f || e.width == 0) return
        val s = minOf(vw / e.width, vh / e.height)
        val m = android.graphics.Matrix()
        m.setScale(e.width * s / vw, e.height * s / vh, vw / 2, vh / 2)
        videoSurface.setTransform(m)
    }

    private fun togglePlayback() {
        val p = player ?: return
        if (!playerReady) return
        if (p.isPlaying) { pausePlayback(); return }
        if (selected != null) closeCapture()
        if (currentUs >= (engine?.lastUs ?: 0) - 200_000) currentUs = 0
        p.seekTo(currentUs / 1000, android.media.MediaPlayer.SEEK_CLOSEST)
        videoSurface.visibility = View.VISIBLE
        videoSurface.alpha = 0f
        videoSurface.anim().alpha(1f).setDuration(200).start()
        p.start()
        btnPlay.setImageResource(R.drawable.ic_pause)
        setChip(live = true, text = "ВОСПРОИЗВЕДЕНИЕ")
        ui.post(playTicker)
    }

    private fun pausePlayback() {
        val p = player ?: return
        if (!playerReady) return
        val wasPlaying = runCatching { p.isPlaying }.getOrDefault(false)
        if (wasPlaying) p.pause()
        ui.removeCallbacks(playTicker)
        btnPlay.setImageResource(R.drawable.ic_play)
        if (videoSurface.visibility == View.VISIBLE) {
            if (wasPlaying) currentUs = p.currentPosition * 1000L
            seek.progress = (currentUs / 1000).toInt()
            showTime()
            requestPreview(exact = true)
            videoSurface.anim().alpha(0f).setStartDelay(150).setDuration(200)
                .withEndAction { videoSurface.visibility = View.GONE }.start()
            if (selected == null) setChip(live = true, text = "ВИДЕО")
        }
    }

    // ================================================================ recent videos

    private fun rememberRecent(uri: Uri, e: FrameEngine) {
        val isPath = uri.scheme == "file"
        val ref = if (isPath) uri.path!! else uri.toString()
        if (!isPath) runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val info = "${e.width}×${e.height} · ${TimeUtil.format(e.durationUs).substringBefore('.')}"
        val name = baseName
        exec.execute {
            val thumb = runCatching { e.scaledFrame(e.lastUs / 3, 320, 320, exact = false) }.getOrNull()
            Recent.add(this, ref, isPath, name, info, thumb)
        }
    }

    /** Horizontal strip of recently opened videos; shown on the start screen (no video loaded). */
    private fun buildRecent() {
        val items = Recent.list(this)
        recentRow.removeAllViews()
        if (items.isEmpty() || engine != null) { cardRecent.visibility = View.GONE; return }
        cardRecent.visibility = View.VISIBLE
        for ((i, it) in items.withIndex()) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams((132 * dp).toInt(), -2).apply { marginEnd = (10 * dp).toInt() }
                stateListAnimator = AnimatorInflater.loadStateListAnimator(context, R.animator.press_scale)
            }
            val img = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(-1, (76 * dp).toInt())
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundResource(R.drawable.bg_thumb)
                clipToOutline = true
                Recent.thumbFile(context, it)?.takeIf { f -> f.exists() }?.let { f -> setImageBitmap(BitmapFactory.decodeFile(f.path)) }
            }
            val name = TextView(this).apply {
                text = it.name; setTextColor(getColor(R.color.text)); textSize = 12f
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                setPadding(2, (6 * dp).toInt(), 2, 0)
            }
            val info = TextView(this).apply {
                text = it.info; setTextColor(getColor(R.color.textDim)); textSize = 10f; maxLines = 1
                setPadding(2, 0, 2, 0)
            }
            card.addView(img); card.addView(name); card.addView(info)
            card.setOnClickListener { _ ->
                val uri = if (it.isPath) Uri.fromFile(File(it.ref)) else Uri.parse(it.ref)
                if (it.isPath && !File(it.ref).exists()) {
                    toast("Файл больше не найден"); Recent.remove(this, it.ref); buildRecent()
                } else loadVideo(uri)
            }
            card.setOnLongClickListener { _ ->
                AlertDialog.Builder(this).setMessage("Убрать «${it.name}» из недавних?")
                    .setPositiveButton("Убрать") { _, _ -> Recent.remove(this, it.ref); buildRecent() }
                    .setNegativeButton("Отмена", null).show()
                true
            }
            card.alpha = 0f; card.translationX = 40 * dp
            card.anim().alpha(1f).translationX(0f).setStartDelay(250L + 60L * i).setDuration(320).start()
            recentRow.addView(card)
        }
    }

    // ================================================================ OCR

    private fun recognizeText(c: Capture) {
        val langs = prefs.ocrLangs.split("+").filter { it.isNotBlank() }
        val missing = Ocr.missing(this, langs)
        if (missing.isNotEmpty()) {
            val names = missing.joinToString { Ocr.LANGS[it] ?: it }
            AlertDialog.Builder(this)
                .setTitle("Распознавание текста")
                .setMessage("Один раз нужно скачать языковые файлы ($names, ≈ ${missing.size * 4} МБ). Потом всё работает без интернета.\n\nЯзыки можно поменять в общих настройках.")
                .setPositiveButton("Скачать") { _, _ -> downloadOcr(missing) { recognizeText(c) } }
                .setNegativeButton("Отмена", null)
                .show()
            return
        }
        val dlg = progressDialog("Читаю текст…")
        Thread {
            val r = runCatching {
                val bmp = BitmapFactory.decodeFile(c.file.path) ?: error("Не удалось открыть кадр")
                Ocr.recognize(this, bmp, langs).also { bmp.recycle() }
            }
            ui.post {
                dlg.dismiss()
                r.onSuccess { showText(it) }.onFailure { showError("Не удалось распознать текст", it) }
            }
        }.start()
    }

    private fun downloadOcr(langs: List<String>, then: () -> Unit) {
        val dlg = progressDialog("Скачиваю языковые файлы…")
        Thread {
            val r = runCatching {
                Ocr.download(this, langs) { lang, f ->
                    ui.post { (dlg.findViewById<TextView>(android.R.id.message))?.text = "Скачиваю: ${Ocr.LANGS[lang]} ${(f * 100).toInt()}%" }
                }
            }
            ui.post {
                dlg.dismiss()
                r.onSuccess { then() }.onFailure { showError("Не удалось скачать (нужен интернет)", it) }
            }
        }.start()
    }

    private fun progressDialog(msg: String): AlertDialog =
        AlertDialog.Builder(this).setMessage(msg).setCancelable(false).show()

    private fun showText(text: String) {
        if (text.isBlank()) { toast("Текст на кадре не найден"); return }
        val pad = (20 * dp).toInt()
        val tv = EditText(this).apply {
            setText(text)
            setTextColor(getColor(R.color.text))
            textSize = 15f
            background = null
            setTextIsSelectable(true)
            keyListener = null
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this)
            .setTitle("Текст на кадре")
            .setView(scroll)
            .setPositiveButton("Копировать") { _, _ ->
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("text", text))
                toast("Скопировано")
            }
            .setNeutralButton("Поделиться") { _, _ ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Поделиться"))
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    // ================================================================ UI state helpers

    private fun showTime() {
        val t = TimeUtil.format(currentUs)
        editTime.setText(t)
        if (selected == null) txtTimeBadge.text = t
    }

    private fun setChip(live: Boolean, text: String) {
        chipText.text = text
        chipDot.visibility = if (live) View.VISIBLE else View.GONE
        if (chipMode.visibility != View.VISIBLE) popIn(chipMode, 0)
    }

    private fun updateCapturesUi(animate: Boolean = true) {
        val n = captures.size
        if (txtCount.text.toString() != n.toString()) {
            txtCount.text = n.toString()
            if (animate) bump(txtCount)
        }
        if (n == 0) {
            if (emptyBox.visibility != View.VISIBLE) { if (animate) showAnimated(emptyBox) else emptyBox.visibility = View.VISIBLE }
            capActions.visibility = View.GONE
            txtTapHint.visibility = View.GONE
        } else {
            emptyBox.visibility = View.GONE
            if (capActions.visibility != View.VISIBLE) {
                if (animate) showAnimated(capActions) else capActions.visibility = View.VISIBLE
            }
        }
    }

    private fun showError(title: String, t: Throwable) {
        val msg = t.message ?: t.javaClass.simpleName
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .setNeutralButton("Копировать") { _, _ ->
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("error", msg))
            }
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun hideKeyboard(v: View) {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
        v.clearFocus()
    }

    // ================================================================ animations

    /** Logo spins in, title slides, preview and the rest rise up one after another. */
    private fun playEntrance() {
        logo.scaleX = 0f; logo.scaleY = 0f; logo.rotation = -140f
        logo.anim().scaleX(1f).scaleY(1f).rotation(0f).setDuration(750)
            .setInterpolator(OvershootInterpolator(1.8f)).start()
        titleBox.alpha = 0f; titleBox.translationX = -24 * dp
        titleBox.anim().alpha(1f).translationX(0f).setStartDelay(150).setDuration(450)
            .setInterpolator(DecelerateInterpolator()).start()
        btnPick.scaleX = 0f; btnPick.scaleY = 0f
        btnPick.anim().scaleX(1f).scaleY(1f).setStartDelay(300).setDuration(400)
            .setInterpolator(OvershootInterpolator(2f)).start()

        val rise = listOf(previewCard, actionBar) +
            listOf(R.id.cardTimeline, R.id.radioTabs, R.id.panelHost, R.id.capHeader).map { header.findViewById<View>(it) }
        rise.forEachIndexed { i, v ->
            v.alpha = 0f
            v.translationY = 36 * dp
            v.anim().alpha(1f).translationY(0f)
                .setStartDelay(200L + 70L * i).setDuration(480)
                .setInterpolator(DecelerateInterpolator(2f)).start()
        }
    }

    /** Gentle looping motion: floating placeholder logo, floating empty icon, pulsing "live" dot. */
    private fun startIdleAnimations() {
        fun float(v: View, distDp: Float, ms: Long) =
            ObjectAnimator.ofFloat(v, View.TRANSLATION_Y, 0f, -distDp * dp).apply {
                duration = ms; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator(); start()
            }
        float(phIcon, 8f, 1400)
        float(emptyIcon, 6f, 1700)
        ObjectAnimator.ofFloat(chipDot, View.ALPHA, 1f, 0.25f).apply {
            duration = 800; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE; start()
        }
    }

    /** Camera-like white flash over the preview + a small squeeze of the card and the shutter button. */
    private fun shutterFlash(soft: Boolean = false) {
        flash.anim().cancel()
        flash.alpha = if (soft) 0.35f else 0.9f
        flash.anim().alpha(0f).setDuration(if (soft) 250 else 380).start()
        previewCard.anim().cancel()
        previewCard.scaleX = 0.97f; previewCard.scaleY = 0.97f
        previewCard.anim().scaleX(1f).scaleY(1f).setDuration(320)
            .setInterpolator(OvershootInterpolator(2f)).start()
        if (!soft) {
            btnCapture.anim().cancel()
            btnCapture.rotation = 0f
            btnCapture.anim().rotationBy(90f).setDuration(350)
                .setInterpolator(OvershootInterpolator()).withEndAction { btnCapture.rotation = 0f }.start()
        }
    }

    /**
     * A copy of the new frame shrinks and flies from the preview down to the "Снятые кадры" counter
     * (or to the bottom of the screen if the counter is scrolled away), then [onLanded] runs.
     */
    private fun flyToCaptures(bmp: Bitmap, onLanded: () -> Unit) {
        if (previewCard.width == 0) { onLanded(); return }
        val rootLoc = IntArray(2).also { root.getLocationInWindow(it) }
        val src = IntArray(2).also { previewCard.getLocationInWindow(it) }
        val w = (previewCard.width * 0.55f).toInt()
        val h = (previewCard.height * 0.55f).toInt()
        val startX = (src[0] - rootLoc[0] + (previewCard.width - w) / 2).toFloat()
        val startY = (src[1] - rootLoc[1] + (previewCard.height - h) / 2).toFloat()

        val r = Rect()
        val (tx, ty) = if (txtCount.isShown && txtCount.getGlobalVisibleRect(r) && r.height() > 0) {
            val t = IntArray(2).also { txtCount.getLocationInWindow(it) }
            (t[0] - rootLoc[0] + txtCount.width / 2f) to (t[1] - rootLoc[1] + txtCount.height / 2f)
        } else {
            val l = IntArray(2).also { list.getLocationInWindow(it) }
            (l[0] - rootLoc[0] + list.width / 2f) to (l[1] - rootLoc[1] + list.height - 30 * dp)
        }

        val iv = ImageView(this).apply {
            setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundResource(R.drawable.bg_thumb)
            clipToOutline = true
            elevation = 16 * dp
            layoutParams = FrameLayout.LayoutParams(w, h)
            x = startX; y = startY
        }
        root.addView(iv)
        iv.anim()
            .x(tx - w / 2f).y(ty - h / 2f)
            .scaleX(0.12f).scaleY(0.12f).rotation(12f).alpha(0.4f)
            .setDuration(560).setInterpolator(AccelerateInterpolator(1.3f))
            .withEndAction { root.removeView(iv); onLanded() }
            .start()
    }

    /** Hidden panel fades/slides away, the other slides in from the side of the selected tab. */
    private fun switchPanel(out: View, into: View, dir: Int) {
        out.anim().cancel(); into.anim().cancel()
        out.anim().alpha(0f).translationX(-dir * 40 * dp).setDuration(150)
            .withEndAction {
                out.visibility = View.GONE
                out.translationX = 0f; out.alpha = 1f
                into.visibility = View.VISIBLE
                into.alpha = 0f
                into.translationX = dir * 40 * dp
                into.anim().alpha(1f).translationX(0f).setDuration(260)
                    .setInterpolator(DecelerateInterpolator(1.5f)).start()
            }.start()
    }

    /** Context bar swap: video controls ↔ capture actions. */
    private fun swapBars(out: View, into: View) {
        out.anim().cancel(); into.anim().cancel()
        out.anim().alpha(0f).translationY(16 * dp).setDuration(160)
            .withEndAction { out.visibility = View.GONE; out.translationY = 0f }.start()
        into.visibility = View.VISIBLE
        into.alpha = 0f
        into.translationY = -16 * dp
        into.anim().alpha(1f).translationY(0f).setStartDelay(90).setDuration(260)
            .setInterpolator(DecelerateInterpolator()).start()
        if (into === captureBar) {
            // buttons pop one by one
            val g = captureBar as ViewGroup
            for (i in 0 until g.childCount) {
                val b = g.getChildAt(i)
                b.scaleX = 0.7f; b.scaleY = 0.7f
                b.anim().scaleX(1f).scaleY(1f).setStartDelay(110L + 45L * i).setDuration(300)
                    .setInterpolator(OvershootInterpolator(2f)).start()
            }
        }
    }

    /**
     * View.animate() reuses one animator per view and REMEMBERS the last start delay and interpolator,
     * so a delayed animation would make every later one on that view delayed too. Reset them each time.
     */
    private fun View.anim(): android.view.ViewPropertyAnimator = animate().apply {
        cancel()
        startDelay = 0
        interpolator = DecelerateInterpolator()
    }

    private fun popIn(v: View, delay: Long) {
        v.anim().cancel()
        v.visibility = View.VISIBLE
        v.alpha = 0f
        v.scaleX = 0.6f; v.scaleY = 0.6f
        v.anim().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(delay).setDuration(280)
            .setInterpolator(OvershootInterpolator(2f)).start()
    }

    private fun bump(v: View) {
        v.anim().cancel()
        v.scaleX = 1.4f; v.scaleY = 1.4f
        v.anim().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(OvershootInterpolator(3f)).start()
    }

    private fun shake(v: View, dir: Int) {
        ObjectAnimator.ofFloat(v, View.TRANSLATION_X, 0f, -dir * 18 * dp, dir * 8 * dp, 0f).apply {
            duration = 300; start()
        }
    }

    /** Wiggle a view and show a hint (e.g. "pick a video first"). */
    private fun nudge(v: View, msg: String) {
        toast(msg)
        ObjectAnimator.ofFloat(v, View.ROTATION, 0f, -12f, 10f, -6f, 0f).apply { duration = 450; start() }
        bump(v)
    }

    private fun showAnimated(v: View) {
        v.visibility = View.VISIBLE
        v.alpha = 0f
        v.translationY = -8 * dp
        v.anim().alpha(1f).translationY(0f).setDuration(250).start()
    }

    // ================================================================ captures grid

    /** ListView rows with 3 thumbnails each (rows are recycled, so hundreds of frames are fine). */
    private inner class GridAdapter : BaseAdapter() {
        private val cols = 3

        override fun getCount() = (captures.size + cols - 1) / cols
        override fun getItem(position: Int): Any = position
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                val pad = (4 * dp).toInt()
                setPadding((10 * dp).toInt(), pad, (10 * dp).toInt(), pad)
                repeat(cols) {
                    val cell = FrameLayout(context).apply {
                        layoutParams = LinearLayout.LayoutParams(0, (104 * dp).toInt(), 1f)
                            .apply { setMargins(pad, 0, pad, 0) }
                        setBackgroundResource(R.drawable.bg_thumb)
                        clipToOutline = true
                        stateListAnimator = AnimatorInflater.loadStateListAnimator(context, R.animator.press_scale)
                    }
                    cell.addView(ImageView(context).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        layoutParams = FrameLayout.LayoutParams(-1, -1)
                    })
                    cell.addView(TextView(context).apply {
                        setTextColor(getColor(R.color.text))
                        setBackgroundResource(R.drawable.bg_chip)
                        textSize = 10f
                        typeface = android.graphics.Typeface.MONOSPACE
                        setPadding((6 * dp).toInt(), (1 * dp).toInt(), (6 * dp).toInt(), (1 * dp).toInt())
                        layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END)
                            .apply { setMargins(0, 0, (5 * dp).toInt(), (5 * dp).toInt()) }
                    })
                    addView(cell)
                }
            }
            for (i in 0 until cols) {
                val cell = row.getChildAt(i) as FrameLayout
                val c = captures.getOrNull(position * cols + i)
                if (c == null) {
                    cell.visibility = View.INVISIBLE
                    cell.setOnClickListener(null)
                    continue
                }
                cell.visibility = View.VISIBLE
                (cell.getChildAt(0) as ImageView).setImageBitmap(c.thumb)
                (cell.getChildAt(1) as TextView).text = TimeUtil.format(c.timeUs)
                cell.foreground = if (c === selected) getDrawable(R.drawable.fg_selected) else null
                cell.setOnClickListener { if (selected === c) closeCapture() else openCapture(c) }
                if (pendingAnim.remove(c)) {
                    cell.alpha = 0f
                    cell.scaleX = 0.5f; cell.scaleY = 0.5f
                    cell.anim().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(60L * i).setDuration(380)
                        .setInterpolator(OvershootInterpolator(1.8f)).start()
                } else {
                    cell.anim().cancel()
                    cell.alpha = 1f; cell.scaleX = 1f; cell.scaleY = 1f
                }
            }
            return row
        }
    }
}
