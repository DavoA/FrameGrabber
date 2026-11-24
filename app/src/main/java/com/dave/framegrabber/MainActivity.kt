package com.dave.framegrabber

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class Capture(val file: File, val thumb: Bitmap, val timeUs: Long, val fmt: Fmt) {
    var galleryUri: Uri? = null
}

class MainActivity : Activity() {

    companion object {
        private const val REQ_PICK = 1
        private const val ALBUM = "FrameGrabber"
        private const val FILMSTRIP_COUNT = 12
        private const val MAX_BATCH = 2000
    }

    private val ui = Handler(Looper.getMainLooper())
    /** All calls to [engine] go through this single thread (MediaMetadataRetriever is not thread-safe). */
    private val exec = Executors.newSingleThreadExecutor()
    private val previewGen = AtomicInteger()
    private val loadGen = AtomicInteger()

    @Volatile private var engine: FrameEngine? = null
    private var videoUri: Uri? = null
    private var baseName = "video"
    private var currentUs = 0L

    private val captures = mutableListOf<Capture>()
    private lateinit var capDir: File

    private var batchCancel: AtomicBoolean? = null

    // views
    private lateinit var list: ListView
    private lateinit var txtInfo: TextView
    private lateinit var imgPreview: ImageView
    private lateinit var seek: SeekBar
    private lateinit var filmstrip: LinearLayout
    private lateinit var editTime: EditText
    private lateinit var radioSize: RadioGroup
    private lateinit var previewFrame: View
    private lateinit var txtPlaceholder: TextView
    private lateinit var txtTimeBadge: TextView
    private lateinit var flash: View
    private lateinit var progressBatch: ProgressBar
    private lateinit var txtCount: TextView
    private lateinit var txtEmpty: TextView
    private lateinit var capActions: View
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
    private lateinit var btnBatch: Button
    private lateinit var txtProgress: TextView
    private val grid = GridAdapter()
    /** Captures that should play the "pop in" animation the first time they're shown. */
    private val pendingAnim = HashSet<Capture>()

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        capDir = File(cacheDir, "captures").apply { deleteRecursively(); mkdirs() }

        val header = layoutInflater.inflate(R.layout.header, null)
        list = ListView(this).apply {
            divider = null
            itemsCanFocus = true
            clipToPadding = false
            setBackgroundColor(getColor(R.color.bg))
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
            addHeaderView(header, null, false)
            adapter = grid
        }
        setContentView(list)
        applyInsets()
        bindViews(header)
        setupListeners(header)
        updateCapturesUi(animate = false)
        playEntrance(header)

        handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    override fun onDestroy() {
        batchCancel?.set(true)
        val e = engine
        exec.execute { e?.close() }
        exec.shutdown()
        super.onDestroy()
    }

    /** Android 15 draws edge-to-edge: keep content out from under status/nav bars and the keyboard. */
    private fun applyInsets() {
        if (Build.VERSION.SDK_INT < 30) return
        list.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
    }

    private fun bindViews(h: View) {
        txtInfo = h.findViewById(R.id.txtInfo)
        imgPreview = h.findViewById(R.id.imgPreview)
        seek = h.findViewById(R.id.seek)
        filmstrip = h.findViewById(R.id.filmstrip)
        editTime = h.findViewById(R.id.editTime)
        radioSize = h.findViewById(R.id.radioSize)
        previewFrame = h.findViewById(R.id.previewFrame)
        txtPlaceholder = h.findViewById(R.id.txtPlaceholder)
        txtTimeBadge = h.findViewById(R.id.txtTimeBadge)
        flash = h.findViewById(R.id.flash)
        progressBatch = h.findViewById(R.id.progressBatch)
        txtCount = h.findViewById(R.id.txtCount)
        txtEmpty = h.findViewById(R.id.txtEmpty)
        capActions = h.findViewById(R.id.capActions)
        previewFrame.clipToOutline = true
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
        btnBatch = h.findViewById(R.id.btnBatch)
        txtProgress = h.findViewById(R.id.txtProgress)
    }

    private fun setupListeners(h: View) {
        h.findViewById<Button>(R.id.btnPick).setOnClickListener { pickVideo() }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                currentUs = p * 1000L
                showTime()
                requestPreview(exact = false)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) = requestPreview(exact = true)
        })

        h.findViewById<Button>(R.id.btnPrev).setOnClickListener { stepFrame(-1) }
        h.findViewById<Button>(R.id.btnNext).setOnClickListener { stepFrame(+1) }

        editTime.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                val us = TimeUtil.parse(v.text.toString())
                if (us == null) toast("Формат времени: 12.5 или 1:23.5") else seekTo(us)
                hideKeyboard(v)
                true
            } else false
        }

        radioSize.setOnCheckedChangeListener { _, id ->
            if (id == R.id.sizeCustom) showAnimated(customSizeRow) else customSizeRow.visibility = View.GONE
        }

        radioFormat.setOnCheckedChangeListener { _, id ->
            val lossless = id == R.id.fmtPng
            seekQuality.isEnabled = !lossless
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

        radioBatch.setOnCheckedChangeListener { _, id ->
            if (id == R.id.batchInterval) { lblBatch.text = "Интервал, сек"; editBatch.setText("5") }
            else { lblBatch.text = "Сколько кадров"; editBatch.setText("10") }
            editBatch.animate().cancel()
            editBatch.alpha = 0f
            editBatch.animate().alpha(1f).setDuration(250).start()
        }

        h.findViewById<Button>(R.id.btnCapture).setOnClickListener { captureCurrent() }
        btnBatch.setOnClickListener { if (batchCancel != null) batchCancel?.set(true) else startBatch() }
        h.findViewById<Button>(R.id.btnSaveAll).setOnClickListener { saveAll() }
        h.findViewById<Button>(R.id.btnZip).setOnClickListener { makeZip() }
        h.findViewById<Button>(R.id.btnClear).setOnClickListener { confirmClear() }
    }

    // ---------------------------------------------------------------- loading video

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
        txtInfo.text = "Открываю…"
        val gen = loadGen.incrementAndGet()
        exec.execute {
            val res = runCatching { FrameEngine(this, uri) }
            ui.post {
                if (gen != loadGen.get()) { res.getOrNull()?.let { e -> exec.execute { e.close() } }; return@post }
                res.onSuccess { e ->
                    engine?.let { old -> exec.execute { old.close() } }
                    engine = e
                    videoUri = uri
                    baseName = displayName(uri)
                    currentUs = 0
                    seek.max = (e.durationUs / 1000).toInt()
                    seek.progress = 0
                    showTime()
                    if (txtPlaceholder.visibility == View.VISIBLE) {
                        txtPlaceholder.animate().alpha(0f).setDuration(250)
                            .withEndAction { txtPlaceholder.visibility = View.GONE }.start()
                    }
                    imgPreview.alpha = 0f
                    imgPreview.scaleX = 0.92f; imgPreview.scaleY = 0.92f
                    imgPreview.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(450)
                        .setInterpolator(DecelerateInterpolator()).start()
                    txtTimeBadge.visibility = View.VISIBLE
                    editFrom.setText("")
                    editTo.setText("")
                    txtInfo.text = "$baseName • ${e.width}×${e.height} • %.2f fps • %s"
                        .format(e.fps, TimeUtil.format(e.durationUs))
                    requestPreview(exact = true)
                    buildFilmstrip(e)
                }.onFailure {
                    txtInfo.text = "Не удалось открыть видео"
                    toast("Ошибка: ${it.message ?: it.javaClass.simpleName}")
                }
            }
        }
    }

    private fun displayName(uri: Uri): String {
        val name = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "video"
        return name.substringBeforeLast('.').replace(Regex("[^\\p{L}\\p{N}._-]+"), "_").take(60).ifEmpty { "video" }
    }

    private fun buildFilmstrip(e: FrameEngine) {
        filmstrip.removeAllViews()
        val dp = resources.displayMetrics.density
        val times = (0 until FILMSTRIP_COUNT).map { i -> e.lastUs * i / (FILMSTRIP_COUNT - 1) }
        val views = times.map { t ->
            ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams((84 * dp).toInt(), ViewGroup.LayoutParams.MATCH_PARENT)
                    .apply { marginEnd = (6 * dp).toInt() }
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundResource(R.drawable.bg_thumb)
                clipToOutline = true
                stateListAnimator = android.animation.AnimatorInflater.loadStateListAnimator(context, R.animator.press_scale)
                setOnClickListener { seekTo(t) }
            }.also { filmstrip.addView(it) }
        }
        times.forEachIndexed { i, t ->
            exec.execute {
                if (engine !== e) return@execute
                val b = runCatching { e.scaledFrame(t, 200, 200, exact = false) }.getOrNull()
                ui.post {
                    if (engine === e && b != null) {
                        views[i].setImageBitmap(b)
                        views[i].alpha = 0f
                        views[i].translationY = 12 * dp
                        views[i].animate().alpha(1f).translationY(0f).setDuration(300).start()
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- seeking / preview

    private fun seekTo(us: Long) {
        val e = engine ?: return
        currentUs = us.coerceIn(0L, e.lastUs)
        seek.progress = (currentUs / 1000).toInt()
        showTime()
        requestPreview(exact = true)
    }

    private fun stepFrame(dir: Int) {
        val e = engine ?: return
        seekTo(currentUs + dir * e.frameUs)
    }

    /** Only the most recent request is rendered; stale ones are skipped while scrubbing. */
    private fun requestPreview(exact: Boolean) {
        val e = engine ?: return
        val gen = previewGen.incrementAndGet()
        val us = currentUs
        exec.execute {
            if (gen != previewGen.get() || engine !== e) return@execute
            val b = runCatching { e.scaledFrame(us, 1280, 1280, exact) }.getOrNull()
            ui.post { if (gen == previewGen.get() && b != null) imgPreview.setImageBitmap(b) }
        }
    }

    // ---------------------------------------------------------------- capturing

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
        val e = engine ?: return toast("Сначала выбери видео")
        val spec = readSpec() ?: return
        val us = currentUs
        exec.execute {
            val res = runCatching { makeCapture(e, us, spec) }
            ui.post {
                res.onSuccess { shutterFlash(); addCapture(it) }
                    .onFailure { showError(it) }
            }
        }
    }

    private fun startBatch() {
        val e = engine ?: return toast("Сначала выбери видео")
        val spec = readSpec() ?: return
        hideKeyboard(editBatch)

        val from = TimeUtil.parse(editFrom.text.toString())?.coerceIn(0L, e.lastUs) ?: 0L
        val to = TimeUtil.parse(editTo.text.toString())?.coerceIn(0L, e.lastUs) ?: e.lastUs
        if (to < from) return toast("«По» должно быть больше «С»")

        val value = editBatch.text.toString().replace(',', '.').toDoubleOrNull()
        val times: List<Long> = if (radioBatch.checkedRadioButtonId == R.id.batchInterval) {
            if (value == null || value <= 0) return toast("Укажи интервал больше 0")
            val step = (value * 1_000_000).toLong().coerceAtLeast(e.frameUs)
            val count = (to - from) / step + 1
            if (count > MAX_BATCH) return toast("Получится $count кадров — максимум $MAX_BATCH. Увеличь интервал.")
            (0 until count).map { from + it * step }
        } else {
            val n = value?.toInt() ?: 0
            if (n < 1 || n > MAX_BATCH) return toast("Количество: от 1 до $MAX_BATCH")
            if (n == 1) listOf(from + (to - from) / 2)
            else (0 until n).map { from + (to - from) * it / (n - 1) }
        }

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
     * Runs on the SAME retriever/thread as single capture (a second MediaMetadataRetriever on the
     * same video returns null frames on many phones — that's why batch used to skip everything).
     * One task per frame, so "Stop" reacts immediately.
     */
    private fun runBatch(e: FrameEngine, times: List<Long>, spec: OutputSpec) {
        val cancel = AtomicBoolean(false)
        batchCancel = cancel
        btnBatch.text = "⏹  Остановить"
        txtProgress.visibility = View.VISIBLE
        txtProgress.text = "Подготовка…"
        progressBatch.max = times.size
        progressBatch.progress = 0
        showAnimated(progressBatch)

        val done = AtomicInteger()
        val failed = AtomicInteger()
        val firstError = java.util.concurrent.atomic.AtomicReference<String?>(null)

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
                btnBatch.text = "🎞  Снять пачку кадров"
                progressBatch.animate().alpha(0f).setStartDelay(600).setDuration(300)
                    .withEndAction { progressBatch.visibility = View.GONE; progressBatch.alpha = 1f }.start()
                val d = done.get()
                val f = failed.get()
                txtProgress.text = buildString {
                    append(if (cancel.get()) "Остановлено: снято $d" else "Готово: снято $d")
                    if (f > 0) append(", пропущено $f\n(${firstError.get()})")
                }
            }
        }
    }

    private fun addCapture(c: Capture, scroll: Boolean = true) {
        captures.add(c)
        pendingAnim.add(c)
        grid.notifyDataSetChanged()
        updateCapturesUi()
        if (scroll) list.post { list.smoothScrollToPosition(grid.count) }
    }

    // ---------------------------------------------------------------- saving / sharing

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

    private fun saveAll() {
        if (captures.isEmpty()) return
        val snapshot = captures.toList()
        txtProgress.visibility = View.VISIBLE
        txtProgress.text = "Сохраняю в галерею…"
        Thread {
            var ok = 0
            for (c in snapshot) if (runCatching { saveToGallery(c) }.isSuccess) ok++
            ui.post {
                txtProgress.text = "В галерее: $ok из ${snapshot.size} (Pictures/$ALBUM)"
                toast("Сохранено $ok в Pictures/$ALBUM")
            }
        }.start()
    }

    private fun makeZip() {
        if (captures.isEmpty()) return
        val snapshot = captures.toList()
        txtProgress.visibility = View.VISIBLE
        txtProgress.text = "Собираю ZIP…"
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
                    txtProgress.text = "ZIP: Загрузки/$ALBUM/$name"
                    AlertDialog.Builder(this)
                        .setTitle("ZIP готов")
                        .setMessage("Сохранён в Загрузки/$ALBUM/$name\n(${snapshot.size} кадров)")
                        .setPositiveButton("Поделиться") { _, _ -> share(uri, "application/zip") }
                        .setNegativeButton("OK", null)
                        .show()
                }.onFailure { txtProgress.text = "Ошибка ZIP: ${it.message}" }
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

    private fun showCaptureMenu(c: Capture) {
        AlertDialog.Builder(this)
            .setTitle(TimeUtil.format(c.timeUs))
            .setItems(arrayOf("Поделиться / открыть", "Сохранить в галерею", "Перейти к этому моменту", "Удалить")) { _, which ->
                when (which) {
                    0, 1 -> Thread {
                        val r = runCatching { saveToGallery(c) }
                        ui.post {
                            r.onSuccess { if (which == 0) share(it, c.fmt.mime) else toast("Сохранено в Pictures/$ALBUM") }
                                .onFailure { toast(it.message ?: "Ошибка") }
                        }
                    }.start()
                    2 -> seekTo(c.timeUs)
                    3 -> { captures.remove(c); c.file.delete(); grid.notifyDataSetChanged(); updateCapturesUi() }
                }
            }
            .show()
    }

    private fun confirmClear() {
        if (captures.isEmpty()) return
        AlertDialog.Builder(this)
            .setMessage("Удалить все ${captures.size} снятых кадров из списка?\n(Уже сохранённые в галерею останутся.)")
            .setPositiveButton("Удалить") { _, _ ->
                captures.forEach { it.file.delete() }
                captures.clear()
                pendingAnim.clear()
                grid.notifyDataSetChanged()
                updateCapturesUi()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ---------------------------------------------------------------- helpers

    private fun showTime() {
        val t = TimeUtil.format(currentUs)
        editTime.setText(t)
        txtTimeBadge.text = t
    }

    private fun updateCapturesUi(animate: Boolean = true) {
        val n = captures.size
        if (txtCount.text.toString() != n.toString()) {
            txtCount.text = n.toString()
            if (animate) {
                txtCount.animate().cancel()
                txtCount.scaleX = 1.35f; txtCount.scaleY = 1.35f
                txtCount.animate().scaleX(1f).scaleY(1f).setDuration(300)
                    .setInterpolator(OvershootInterpolator(3f)).start()
            }
        }
        if (n == 0) {
            txtEmpty.visibility = View.VISIBLE
            capActions.visibility = View.GONE
        } else {
            txtEmpty.visibility = View.GONE
            if (capActions.visibility != View.VISIBLE) {
                if (animate) showAnimated(capActions) else capActions.visibility = View.VISIBLE
            }
        }
    }

    // ---------------------------------------------------------------- animations

    /** Cards slide up one after another on start. */
    private fun playEntrance(header: View) {
        val dp = resources.displayMetrics.density
        listOf(R.id.appBar, R.id.cardVideo, R.id.cardSettings, R.id.cardBatch, R.id.cardCaptures)
            .forEachIndexed { i, id ->
                val v = header.findViewById<View>(id)
                v.alpha = 0f
                v.translationY = 40 * dp
                v.animate().alpha(1f).translationY(0f)
                    .setStartDelay(80L * i).setDuration(450)
                    .setInterpolator(DecelerateInterpolator(2f)).start()
            }
    }

    /** Camera-like white flash over the preview + small "bump". */
    private fun shutterFlash() {
        flash.animate().cancel()
        flash.alpha = 0.85f
        flash.animate().alpha(0f).setDuration(350).start()
        previewFrame.animate().cancel()
        previewFrame.scaleX = 0.97f; previewFrame.scaleY = 0.97f
        previewFrame.animate().scaleX(1f).scaleY(1f).setDuration(300)
            .setInterpolator(OvershootInterpolator(2f)).start()
    }

    private fun showAnimated(v: View) {
        v.visibility = View.VISIBLE
        v.alpha = 0f
        v.translationY = -8 * resources.displayMetrics.density
        v.animate().alpha(1f).translationY(0f).setDuration(250).start()
    }

    /** Error dialog with selectable text, so it can be copied or screenshotted. */
    private fun showError(t: Throwable) {
        val msg = t.message ?: t.javaClass.simpleName
        AlertDialog.Builder(this)
            .setTitle("Не получилось снять кадр")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .setNeutralButton("Копировать") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("error", msg))
            }
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun hideKeyboard(v: View) {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
        v.clearFocus()
    }

    /** Captures grid: ListView rows with 3 thumbnails each (rows are recycled, so hundreds of frames are fine). */
    private inner class GridAdapter : BaseAdapter() {
        private val cols = 3

        override fun getCount() = (captures.size + cols - 1) / cols
        override fun getItem(position: Int): Any = position
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val dp = resources.displayMetrics.density
            val row = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                val pad = (4 * dp).toInt()
                setPadding(pad * 2, pad, pad * 2, pad)
                repeat(cols) {
                    val cell = FrameLayout(context).apply {
                        layoutParams = LinearLayout.LayoutParams(0, (100 * dp).toInt(), 1f)
                            .apply { setMargins(pad, 0, pad, 0) }
                        setBackgroundResource(R.drawable.bg_thumb)
                        clipToOutline = true
                        stateListAnimator = android.animation.AnimatorInflater
                            .loadStateListAnimator(context, R.animator.press_scale)
                    }
                    cell.addView(ImageView(context).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        layoutParams = FrameLayout.LayoutParams(-1, -1)
                    })
                    cell.addView(TextView(context).apply {
                        setTextColor(getColor(R.color.accent))
                        setBackgroundResource(R.drawable.bg_badge)
                        textSize = 10f
                        setPadding((6 * dp).toInt(), (2 * dp).toInt(), (6 * dp).toInt(), (2 * dp).toInt())
                        layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END)
                            .apply { setMargins(0, 0, (5 * dp).toInt(), (5 * dp).toInt()) }
                    })
                    addView(cell)
                }
            }
            for (i in 0 until cols) {
                val cell = row.getChildAt(i) as FrameLayout
                val idx = position * cols + i
                val c = captures.getOrNull(idx)
                if (c == null) {
                    cell.visibility = View.INVISIBLE
                    cell.setOnClickListener(null)
                } else {
                    cell.visibility = View.VISIBLE
                    (cell.getChildAt(0) as ImageView).setImageBitmap(c.thumb)
                    (cell.getChildAt(1) as TextView).text = TimeUtil.format(c.timeUs)
                    cell.setOnClickListener { showCaptureMenu(c) }
                    if (pendingAnim.remove(c)) {
                        cell.alpha = 0f
                        cell.scaleX = 0.6f; cell.scaleY = 0.6f
                        cell.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(380)
                            .setInterpolator(OvershootInterpolator(1.6f)).start()
                    } else {
                        cell.animate().cancel()
                        cell.alpha = 1f; cell.scaleX = 1f; cell.scaleY = 1f
                    }
                }
            }
            return row
        }
    }
}
