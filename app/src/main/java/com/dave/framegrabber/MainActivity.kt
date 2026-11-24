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

class Capture(val file: File, val thumb: Bitmap, val timeUs: Long, val fmt: Fmt) {
    var galleryUri: Uri? = null
}

class MainActivity : Activity() {

    companion object {
        private const val REQ_PICK = 1
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

    private val captures = mutableListOf<Capture>()
    private lateinit var capDir: File
    /** Capture currently opened in the big preview; null = showing the video. */
    private var selected: Capture? = null
    /** Captures that should play the "pop in" animation the first time they're shown. */
    private val pendingAnim = HashSet<Capture>()

    private var batchCancel: AtomicBoolean? = null
    private var batchPulse: ObjectAnimator? = null

    private val dp get() = resources.displayMetrics.density

    // pinned views
    private lateinit var root: FrameLayout
    private lateinit var content: View
    private lateinit var logo: ImageView
    private lateinit var titleBox: View
    private lateinit var btnPick: ImageButton
    private lateinit var txtInfo: TextView
    private lateinit var previewCard: FrameLayout
    private lateinit var imgPreview: ImageView
    private lateinit var imgCapture: ImageView
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
        capDir = File(cacheDir, "captures").apply { deleteRecursively(); mkdirs() }
        setContentView(R.layout.activity_main)
        bindViews()
        header = layoutInflater.inflate(R.layout.header, list, false)
        bindHeader(header)
        list.addHeaderView(header, null, false)
        list.adapter = grid
        list.itemsCanFocus = true
        list.selector = ColorDrawable(Color.TRANSPARENT)

        applyInsets()
        setupListeners()
        updateCapturesUi(animate = false)
        updateBatchHint()
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

    override fun onDestroy() {
        batchCancel?.set(true)
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
        previewCard.clipToOutline = true
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
    }

    private fun setupListeners() {
        btnPick.setOnClickListener { pickVideo() }
        findViewById<View>(R.id.btnPickBig).setOnClickListener { pickVideo() }

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
        setupSwipe()

        // tabs
        radioTabs.setOnCheckedChangeListener { _, id ->
            if (id == R.id.tabBatch) switchPanel(cardSettings, cardBatch, -1)
            else switchPanel(cardBatch, cardSettings, +1)
        }

        // settings
        radioSize.setOnCheckedChangeListener { _, id ->
            if (id == R.id.sizeCustom) showAnimated(customSizeRow) else customSizeRow.visibility = View.GONE
        }
        radioFormat.setOnCheckedChangeListener { _, id ->
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
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        // batch
        radioBatch.setOnCheckedChangeListener { _, id ->
            if (id == R.id.batchInterval) { lblBatch.text = "Интервал, сек"; editBatch.setText("5") }
            else { lblBatch.text = "Сколько кадров"; editBatch.setText("10") }
            editBatch.anim().cancel()
            editBatch.alpha = 0f
            editBatch.anim().alpha(1f).setDuration(250).start()
            updateBatchHint()
        }
        val hintWatcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = updateBatchHint()
        }
        editBatch.addTextChangedListener(hintWatcher)
        editFrom.addTextChangedListener(hintWatcher)
        editTo.addTextChangedListener(hintWatcher)

        btnBatch.setOnClickListener { if (batchCancel != null) batchCancel?.set(true) else startBatch() }
        header.findViewById<View>(R.id.btnSaveAll).setOnClickListener { saveAll() }
        header.findViewById<View>(R.id.btnZip).setOnClickListener { makeZip() }
        header.findViewById<View>(R.id.btnClear).setOnClickListener { confirmClear() }
    }

    // ================================================================ loading video

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
        if (requestCode == REQ_PICK && resultCode == RESULT_OK) data?.data?.let { loadVideo(it) }
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
        batchCancel?.set(true)
        if (selected != null) closeCapture()
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
        baseName = displayName(uri)
        currentUs = 0
        seek.max = (e.durationUs / 1000).toInt()
        seek.progress = 0
        editFrom.setText("")
        editTo.setText("")
        txtInfo.text = "$baseName · ${e.width}×${e.height} · %.0f fps".format(e.fps)
        txtDuration.text = TimeUtil.format(e.durationUs)
        showTime()
        updateBatchHint()

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
    private fun makeCapture(e: FrameEngine, us: Long, spec: OutputSpec): Capture {
        val shot = FrameCapture.capture(e, us, spec, capDir, baseName)
        return Capture(shot.file, shot.thumb, us, spec.format)
    }

    private fun captureCurrent() {
        val e = engine ?: return nudge(btnPick, "Сначала выбери видео")
        val spec = readSpec() ?: return
        val us = currentUs
        exec.execute {
            val res = runCatching { makeCapture(e, us, spec) }
            ui.post {
                res.onSuccess { c ->
                    shutterFlash()
                    flyToCaptures(c.thumb) { addCapture(c) }
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
        val spec = readSpec() ?: return
        hideKeyboard(editBatch)
        val times = batchTimes(e, quiet = false) ?: return
        val go = { runBatch(e, times, spec) }
        if (times.size > 300) {
            AlertDialog.Builder(this)
                .setMessage("Будет снято ${times.size} кадров. Это может занять время. Продолжить?")
                .setPositiveButton("Да") { _, _ -> go() }
                .setNegativeButton("Отмена", null)
                .show()
        } else go()
    }

    /**
     * Runs on the same retriever/thread as single capture, so both paths behave identically.
     * One task per frame, so "Stop" reacts immediately.
     */
    private fun runBatch(e: FrameEngine, times: List<Long>, spec: OutputSpec) {
        val cancel = AtomicBoolean(false)
        batchCancel = cancel
        btnBatch.text = "Остановить"
        btnBatch.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_stop, 0, 0, 0)
        batchPulse = ObjectAnimator.ofFloat(btnBatch, View.ALPHA, 1f, 0.72f).apply {
            duration = 650; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE; start()
        }
        txtProgress.visibility = View.VISIBLE
        txtProgress.text = "Подготовка…"
        progressBatch.max = times.size
        progressBatch.progress = 0
        showAnimated(progressBatch)

        val done = AtomicInteger()
        val failed = AtomicInteger()
        val firstError = AtomicReference<String?>(null)

        times.forEachIndexed { i, t ->
            exec.execute {
                if (cancel.get() || engine !== e) return@execute
                val r = runCatching { makeCapture(e, t, spec) }
                r.onSuccess { done.incrementAndGet() }
                    .onFailure { failed.incrementAndGet(); firstError.compareAndSet(null, it.message ?: it.javaClass.simpleName) }
                ui.post {
                    r.getOrNull()?.let { addCapture(it, scroll = false) }
                    txtProgress.text = "Кадр ${i + 1} из ${times.size}"
                    progressBatch.setProgress(i + 1, true)
                }
            }
        }
        exec.execute {
            ui.post {
                if (batchCancel === cancel) batchCancel = null
                batchPulse?.cancel(); batchPulse = null
                btnBatch.alpha = 1f
                btnBatch.text = "Снять пачку кадров"
                btnBatch.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_burst, 0, 0, 0)
                progressBatch.anim().alpha(0f).setStartDelay(600).setDuration(300)
                    .withEndAction { progressBatch.visibility = View.GONE; progressBatch.alpha = 1f }.start()
                val d = done.get()
                val f = failed.get()
                txtProgress.text = buildString {
                    append(if (cancel.get()) "Остановлено: снято $d" else "Готово: снято $d")
                    if (f > 0) append(", пропущено $f\n(${firstError.get()})")
                }
                if (d > 0) list.post { list.smoothScrollToPositionFromTop(1, 0) }
            }
        }
    }

    private fun addCapture(c: Capture, scroll: Boolean = true) {
        captures.add(c)
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
        txtTimeBadge.text = TimeUtil.format(c.timeUs)
        btnCapPrev.anim().alpha(if (i > 0) 1f else 0.3f).setDuration(150).start()
        btnCapNext.anim().alpha(if (i < captures.size - 1) 1f else 0.3f).setDuration(150).start()
    }

    private fun enterViewerChrome() {
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
    }

    private fun decodeSampled(file: File, maxSide: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        var sample = 1
        while (max(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Swipe left/right on the big preview to flip through captures. */
    private fun setupSwipe() {
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = selected != null
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (selected == null || e1 == null) return false
                val dx = e2.x - e1.x
                if (abs(dx) > 60 * dp && abs(dx) > abs(e2.y - e1.y)) {
                    stepCapture(if (dx < 0) +1 else -1)
                    return true
                }
                return false
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (selected == null) return false
                closeCapture(); return true
            }
        })
        imgCapture.setOnTouchListener { _, ev -> gd.onTouchEvent(ev) }
    }

    // ================================================================ saving / sharing

    private fun saveToGallery(c: Capture): Uri {
        c.galleryUri?.let { return it }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, c.file.name)
            put(MediaStore.Images.Media.MIME_TYPE, c.fmt.mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val col = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = contentResolver.insert(col, values) ?: error("Не удалось создать файл в галерее")
        contentResolver.openOutputStream(uri)!!.use { o -> c.file.inputStream().use { it.copyTo(o) } }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)
        c.galleryUri = uri
        return uri
    }

    private fun saveOne(c: Capture) = Thread {
        val r = runCatching { saveToGallery(c) }
        ui.post {
            r.onSuccess { toast("Сохранено в Pictures/$ALBUM"); shutterFlash(soft = true) }
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
            ui.post { toast("Сохранено $ok из ${snapshot.size} в Pictures/$ALBUM") }
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
                txtTapHint.visibility = View.VISIBLE
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
