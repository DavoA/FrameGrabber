package com.dave.framegrabber.ui.telegram

import com.dave.framegrabber.ui.tc

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.dave.framegrabber.R
import com.dave.framegrabber.core.FrameRender
import com.dave.framegrabber.core.TimeUtil
import com.dave.framegrabber.data.AppTheme
import com.dave.framegrabber.telegram.PhotoPrep
import com.dave.framegrabber.telegram.PostCaption
import com.dave.framegrabber.telegram.PostLog
import com.dave.framegrabber.telegram.TelegramApi
import com.dave.framegrabber.telegram.TgException
import com.dave.framegrabber.telegram.TgStore
import com.dave.framegrabber.ui.bump
import com.dave.framegrabber.ui.wiggle
import com.dave.framegrabber.ui.telegram.Chips.setOn
import com.dave.framegrabber.ui.toast
import com.dave.framegrabber.ui.widget.FlowLayout
import com.dave.framegrabber.ui.widget.SynthwaveView
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * The post editor: 6 photos (swap by tapping two), studio, models, hashtags from the library,
 * a live preview of how the post will look in the channel, and «Опубликовать».
 *
 * The caption is plain text in the fixed shape from [PostCaption]; it's sent without any parse
 * mode, so the channel shows exactly the characters in the preview.
 */
class PostActivity : Activity() {

    companion object {
        const val EXTRA_OPEN_FEED = "openFeed"
        private const val EXTRA_FILES = "files"
        private const val EXTRA_TIMES = "times"
        private const val EXTRA_VIDEO = "video"
        private const val EXTRA_VIDEO_KEY = "videoKey"
        private const val EXTRA_VIDEO_URI = "videoUri"

        fun intent(ctx: Context, files: List<String>, times: List<Long>, video: String, videoKey: String, videoUri: Uri? = null) =
            Intent(ctx, PostActivity::class.java)
                .putExtra(EXTRA_VIDEO_URI, videoUri?.toString())
                .putStringArrayListExtra(EXTRA_FILES, ArrayList(files))
                .putExtra(EXTRA_TIMES, times.toLongArray())
                .putExtra(EXTRA_VIDEO, video)
                .putExtra(EXTRA_VIDEO_KEY, videoKey)

        /** Hashtag colour in Telegram's dark theme (preview). */
        private const val TG_LINK = 0xFF6AB3F3.toInt()
    }

    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var store: TgStore
    private val d get() = resources.displayMetrics.density

    /** Photos in album order. */
    private val files = mutableListOf<File>()
    private val times = mutableListOf<Long>()
    private val thumbs = HashMap<String, Bitmap>()
    /** First tile tapped when swapping (index), or -1. */
    private var swapFrom = -1

    private val models = mutableListOf<String>()
    /** Posts published before (for the "already posted?" checks). */
    private lateinit var log: PostLog
    private var videoKey = ""
    /** Picture hash of each photo (by file path), computed in the background. */
    private val hashes = HashMap<String, Long>()
    private lateinit var cardDup: View
    private lateinit var txtDup: TextView
    private lateinit var txtNamesHint: TextView
    /** Hashtag choice (public for tests). */
    lateinit var tags: TagPicker
    private var posting = false

    private lateinit var photoGrid: GridLayout
    private lateinit var previewGrid: GridLayout
    private lateinit var editStudio: AutoCompleteTextView
    private lateinit var editModel: AutoCompleteTextView
    private lateinit var modelChips: FlowLayout
    private lateinit var editTagSearch: EditText
    private lateinit var txtTagCount: TextView
    private lateinit var txtPreview: TextView
    private lateinit var txtCounter: TextView
    private lateinit var txtChannel: TextView
    private lateinit var cardSetup: View
    private lateinit var btnPublish: Button
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        setContentView(R.layout.activity_post)
        val bg = findViewById<SynthwaveView>(R.id.bgView)
        AppTheme.apply(this, bg, AppTheme.get(this), animate = false)
        store = TgStore(this)
        log = PostLog(this)
        videoKey = intent.getStringExtra(EXTRA_VIDEO_KEY) ?: ""

        intent.getStringArrayListExtra(EXTRA_FILES)?.forEach { files += File(it) }
        intent.getLongArrayExtra(EXTRA_TIMES)?.forEach { times += it }
        while (times.size < files.size) times += 0L

        photoGrid = findViewById(R.id.photoGrid)
        previewGrid = findViewById(R.id.previewGrid)
        editStudio = findViewById(R.id.editStudio)
        editModel = findViewById(R.id.editModel)
        modelChips = findViewById(R.id.modelChips)
        editTagSearch = findViewById(R.id.editTagSearch)
        txtTagCount = findViewById(R.id.txtTagCount)
        txtPreview = findViewById(R.id.txtPreview)
        txtCounter = findViewById(R.id.txtCounter)
        txtChannel = findViewById(R.id.txtChannel)
        cardSetup = findViewById(R.id.cardSetup)
        btnPublish = findViewById(R.id.btnPublish)
        progress = findViewById(R.id.progressPost)
        status = findViewById(R.id.txtPostStatus)
        cardDup = findViewById(R.id.cardDup)
        txtDup = findViewById(R.id.txtDup)
        txtNamesHint = findViewById(R.id.txtNamesHint)

        findViewById<View>(R.id.btnBack).setOnClickListener { onBackPressed() }
        findViewById<View>(R.id.btnSetup).setOnClickListener { setup() }
        findViewById<View>(R.id.btnSetupNow).setOnClickListener { setup() }
        tags = TagPicker(this, findViewById(R.id.tagArea), editTagSearch) { updatePreview() }
        findViewById<View>(R.id.btnEditTags).setOnClickListener { startActivity(Intent(this, TagsActivity::class.java)) }
        btnPublish.setOnClickListener { publish() }

        // the draft from last time (if it wasn't published)
        editStudio.setText(store.draftStudio)
        models += store.draftModels
        val lib = com.dave.framegrabber.telegram.TagLibrary(this)
        tags.selected += store.draftTags.filter { lib.contains(it) }

        editStudio.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, store.studios))
        editModel.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, store.models))
        editStudio.addTextChangedListener(watcher { updatePreview(); updateNamesHint() })
        editModel.setOnEditorActionListener { _, id, ev ->
            if (id == EditorInfo.IME_ACTION_DONE || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { addModel(); true } else false
        }
        editModel.setOnItemClickListener { _, _, _, _ -> addModel() }
        findViewById<View>(R.id.btnAddModel).setOnClickListener { addModel() }

        applyInsets()
        loadThumbs()
        buildModelChips()
        updateNamesHint()
        tags.render()
        updateSetup()
        updatePreview()
        buildVideoCard()
    }

    // ================================================================ video after the photos · when

    private var videoUri: Uri? = null
    private var sendAt = 0                       // unix time, 0 = now
    private lateinit var swVideo: android.widget.Switch
    private lateinit var editVideoCaption: EditText
    private lateinit var txtWhen: TextView
    private lateinit var txtAccount: TextView
    private val tgPrefs get() = getSharedPreferences("telegram", MODE_PRIVATE)

    /**
     * «Видео и время»: the video right after the 6 photos (cut into Part 1, 2… when too big) and
     * publishing now or at a chosen time. Both go through your Telegram login (tab «Канал»).
     */
    private fun buildVideoCard() {
        com.dave.framegrabber.tgclient.Tg.start(this)
        videoUri = intent.getStringExtra(EXTRA_VIDEO_URI)?.let(Uri::parse)
        val d = resources.displayMetrics.density
        val card = android.widget.LinearLayout(this, null, 0, R.style.Card)
        card.addView(TextView(this, null, 0, R.style.CardTitle).apply { text = "Видео и время" })
        // the platform's own switch look (thumb + track), in the scheme's accent color
        swVideo = android.widget.Switch(this).apply {
            text = "Выложить видео после фото"
            textSize = 15f; setTextColor(tc(R.color.text))
            minHeight = (44 * resources.displayMetrics.density).toInt()
            isEnabled = videoUri != null
            isChecked = videoUri != null && tgPrefs.getBoolean("postVideo", true)
            setOnCheckedChangeListener { _, on -> tgPrefs.edit().putBoolean("postVideo", on).apply(); updateVideoInfo() }
        }
        card.addView(swVideo)
        val info = TextView(this).apply { textSize = 12f; setTextColor(tc(R.color.textDim)); setPadding(0, (2 * d).toInt(), 0, (6 * d).toInt()) }
        info.tag = "videoInfo"
        card.addView(info)
        editVideoCaption = EditText(this, null, 0, R.style.Input).apply {
            hint = "Подпись под видео (необязательно)"
            gravity = Gravity.CENTER_VERTICAL
            setText(tgPrefs.getString("videoCaption", ""))
            layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2)
            addTextChangedListener(watcher { tgPrefs.edit().putString("videoCaption", text.toString()).apply() })
        }
        card.addView(editVideoCaption)
        val row = android.widget.LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (12 * d).toInt(), 0, 0)
        }
        txtWhen = TextView(this).apply { textSize = 14f; setTextColor(tc(R.color.text)); layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f) }
        row.addView(txtWhen)
        row.addView(Button(this, null, 0, R.style.BtnSecondary).apply {
            text = "Изменить"; gravity = Gravity.CENTER; maxLines = 1
            setOnClickListener { pickWhen() }
        })
        card.addView(row)
        txtAccount = TextView(this).apply { textSize = 12f; setTextColor(tc(R.color.warn)); setPadding(0, (8 * d).toInt(), 0, 0) }
        card.addView(txtAccount)
        val parent = btnPublish.parent as android.view.ViewGroup
        parent.addView(card, parent.indexOfChild(btnPublish))
        (card.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.topMargin = (12 * d).toInt()
        updateVideoInfo()
        updateWhen()
    }

    private fun updateVideoInfo() {
        val info = swVideo.rootView.findViewWithTag<TextView>("videoInfo") ?: return
        val uri = videoUri
        info.text = when {
            uri == null -> "Видео этих кадров не известно (открой видео и выбери кадры заново)"
            !swVideo.isChecked -> "Только 6 фото"
            else -> {
                val size = runCatching { contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1L
                val limit = com.dave.framegrabber.split.SplitPlan.TELEGRAM_LIMIT
                when {
                    size <= 0 -> "Видео: ${intent.getStringExtra(EXTRA_VIDEO) ?: ""}"
                    size <= limit -> "Видео ${mb(size)} — одним файлом"
                    else -> "Видео ${mb(size)} — больше 2 ГБ, будет разрезано на ~${(size + limit - 1) / limit} части (Part 1, Part 2…; с Premium — до 4 ГБ на часть)"
                }
            }
        }
        val needsAccount = swVideo.isChecked || sendAt > 0
        val ready = com.dave.framegrabber.tgclient.Tg.state == com.dave.framegrabber.tgclient.Tg.State.READY
        txtAccount.visibility = if (needsAccount && !ready) View.VISIBLE else View.GONE
        txtAccount.text = "Видео и расписание отправляются через твой вход в Telegram — войди во вкладке «Канал»."
    }

    private fun mb(b: Long) = when {
        b >= 1L shl 30 -> String.format(java.util.Locale.US, "%.2f ГБ", b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> String.format(java.util.Locale.US, "%.0f МБ", b / (1L shl 20).toDouble())
        else -> String.format(java.util.Locale.US, "%.0f КБ", b / 1024.0)
    }

    private fun updateWhen() {
        txtWhen.text = if (sendAt == 0) "Публикация: сразу"
        else "Публикация: " + java.text.SimpleDateFormat("d MMMM, HH:mm", java.util.Locale("ru")).format(java.util.Date(sendAt * 1000L))
        updateVideoInfo()
    }

    /** Now, or a date + time (Telegram publishes it by itself then). */
    private fun pickWhen() {
        AlertDialog.Builder(this).setItems(arrayOf("Сразу", "Запланировать на дату и время…")) { _, i ->
            if (i == 0) { sendAt = 0; updateWhen(); return@setItems }
            val c = java.util.Calendar.getInstance().apply { if (sendAt > 0) timeInMillis = sendAt * 1000L else add(java.util.Calendar.HOUR_OF_DAY, 1) }
            android.app.DatePickerDialog(this, { _, y, m, day ->
                c.set(y, m, day)
                android.app.TimePickerDialog(this, { _, h, min ->
                    c.set(java.util.Calendar.HOUR_OF_DAY, h); c.set(java.util.Calendar.MINUTE, min); c.set(java.util.Calendar.SECOND, 0)
                    if (c.timeInMillis < System.currentTimeMillis() + 60_000) { toast("Это время уже прошло"); return@TimePickerDialog }
                    sendAt = (c.timeInMillis / 1000).toInt(); updateWhen()
                }, c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE), true).show()
            }, c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH), c.get(java.util.Calendar.DAY_OF_MONTH))
                .apply { datePicker.minDate = System.currentTimeMillis() - 1000 }.show()
        }.show()
    }

    /** Photos + video and/or a schedule: sent through the account, in the background. */
    private fun sendViaAccount(text: String, studio: String) {
        if (com.dave.framegrabber.tgclient.Tg.state != com.dave.framegrabber.tgclient.Tg.State.READY) {
            AlertDialog.Builder(this).setTitle("Нужен вход в Telegram")
                .setMessage("Видео после фото и отложенные посты отправляются через твой аккаунт. Войди во вкладке «Канал» (один раз), потом опубликуй снова.\n\nИли выключи видео и выбери «Сразу» — тогда фото уйдут через бота, как раньше.")
                .setPositiveButton("OK", null).show()
            return
        }
        val channel = store.chatUsername.removePrefix("@").ifBlank { com.dave.framegrabber.tgclient.TgKeys(this).feedChannel }
        if (channel.isBlank()) { toast("Не знаю @имя канала — укажи канал во вкладке «Канал»"); return }
        if (com.dave.framegrabber.tgclient.TgPublish.running) { toast("Предыдущий пост ещё отправляется"); return }
        com.dave.framegrabber.tgclient.TgPublish.start(this, com.dave.framegrabber.tgclient.TgPublish.Request(
            channel, files.toList(), text, if (swVideo.isChecked) videoUri else null,
            intent.getStringExtra(EXTRA_VIDEO) ?: "video", editVideoCaption.text.toString(), sendAt,
        ))
        recordPost(studio, 0)
        AlertDialog.Builder(this)
            .setTitle(if (sendAt > 0) "Пост запланирован" else "Публикуется ✓")
            .setMessage((if (sendAt > 0) "Фото" + (if (swVideo.isChecked) " и видео" else "") + " загружаются сейчас, а в канале появятся в ${txtWhen.text.toString().substringAfter(": ")}. "
                else "Фото" + (if (swVideo.isChecked) " и видео" else "") + " отправляются в фоне. ") +
                "Прогресс — в уведомлении, приложение можно закрыть.")
            .setPositiveButton("Готово") { _, _ ->
                setResult(RESULT_OK, Intent().putExtra(EXTRA_OPEN_FEED, true)); finish()
            }
            .setOnCancelListener { finish() }
            .show()
    }

    override fun onPause() {
        super.onPause()
        if (!posted) saveDraft()
    }

    override fun onDestroy() { io.shutdownNow(); super.onDestroy() }

    override fun onBackPressed() {
        if (posting) { toast("Подожди, идёт публикация…"); return }
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun saveDraft() {
        store.draftStudio = editStudio.text.toString()
        store.draftModels = models.toList()
        store.draftTags = tags.selected.toList()
    }

    private fun applyInsets() {
        if (Build.VERSION.SDK_INT < 30) return
        findViewById<View>(R.id.content).setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, max(bars.bottom, ime.bottom))
            insets
        }
    }

    private fun watcher(f: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) = f()
    }

    // ================================================================ setup

    private fun setup() = TgSetupDialog(this) { updateSetup() }.show()

    private fun updateSetup() {
        val ok = store.configured
        cardSetup.visibility = if (ok) View.GONE else View.VISIBLE
        txtChannel.text = if (ok) "в «${store.chatTitle.ifEmpty { store.chat }}»" else "канал не настроен"
    }

    // ================================================================ photos

    private fun loadThumbs() {
        buildPhotoGrids()
        io.execute {
            files.forEach { f ->
                val b = FrameRender.decodeFile(f, 480) ?: return@forEach
                ui.post { thumbs[f.path] = b; buildPhotoGrids() }
            }
            // then: was this video or any of these pictures already posted?
            val h = files.associate { it.path to PostLog.hashOf(it) }
            ui.post {
                h.forEach { (k, v) -> if (v != null) hashes[k] = v }
                updateDupWarning()
                buildPhotoGrids()
            }
        }
    }

    private fun buildPhotoGrids() {
        fill(photoGrid, editable = true)
        fill(previewGrid, editable = false)
    }

    private fun fill(grid: GridLayout, editable: Boolean) {
        grid.removeAllViews()
        val gap = (if (editable) 6 else 2) * d
        val total = grid.width.takeIf { it > 0 } ?: (resources.displayMetrics.widthPixels - (60 * d).toInt())
        val cell = ((total - gap * 3) / 3).toInt()
        if (grid.width == 0) grid.post { if (grid.width > 0) fill(grid, editable) }
        files.forEachIndexed { i, f ->
            val tile = FrameLayout(this).apply {
                layoutParams = GridLayout.LayoutParams().apply {
                    width = cell; height = if (editable) cell else (cell * 0.75f).toInt()
                    setMargins((gap / 2).toInt(), (gap / 2).toInt(), (gap / 2).toInt(), (gap / 2).toInt())
                }
                if (editable) {
                    setBackgroundResource(R.drawable.bg_thumb)
                    clipToOutline = true
                    if (i == swapFrom) foreground = getDrawable(R.drawable.fg_selected)
                }
            }
            tile.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = FrameLayout.LayoutParams(-1, -1)
                thumbs[f.path]?.let { setImageBitmap(it) } ?: setBackgroundColor(Color.DKGRAY)
            })
            if (editable) {
                tile.addView(TextView(this).apply {
                    text = if (i == 0) "1 · обложка" else (i + 1).toString()
                    setTextColor(tc(R.color.text)); textSize = 11f
                    setBackgroundResource(R.drawable.bg_chip)
                    setPadding((7 * d).toInt(), (2 * d).toInt(), (7 * d).toInt(), (2 * d).toInt())
                    layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START)
                        .apply { setMargins((5 * d).toInt(), (5 * d).toInt(), 0, 0) }
                })
                tile.addView(TextView(this).apply {
                    text = TimeUtil.format(times[i])
                    setTextColor(tc(R.color.textDim)); textSize = 9f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setBackgroundResource(R.drawable.bg_chip)
                    setPadding((5 * d).toInt(), 0, (5 * d).toInt(), 0)
                    layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END)
                        .apply { setMargins(0, 0, (5 * d).toInt(), (5 * d).toInt()) }
                })
                // this picture was in an earlier post
                if (f.path in dupPaths) tile.addView(TextView(this).apply {
                    text = "уже было"
                    setTextColor(0xFF2A220C.toInt()); textSize = 10f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setBackgroundColor(tc(R.color.warn))
                    setPadding((6 * d).toInt(), (1 * d).toInt(), (6 * d).toInt(), (1 * d).toInt())
                    layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START)
                        .apply { setMargins((5 * d).toInt(), 0, 0, (5 * d).toInt()) }
                })
                tile.setOnClickListener { tapPhoto(i) }
            }
            grid.addView(tile)
        }
    }

    /** First tap marks a photo, second tap swaps the two. */
    private fun tapPhoto(i: Int) {
        if (posting) return
        when (swapFrom) {
            -1 -> swapFrom = i
            i -> swapFrom = -1
            else -> {
                java.util.Collections.swap(files, swapFrom, i)
                java.util.Collections.swap(times, swapFrom, i)
                swapFrom = -1
            }
        }
        buildPhotoGrids()
    }

    // ================================================================ studio & models

    private fun addModel() {
        val m = editModel.text.toString().trim().replace(Regex("\\s+"), " ")
        if (m.isEmpty()) { editModel.wiggle(); return }
        if (models.any { it.equals(m, ignoreCase = true) }) { toast("$m уже добавлена"); editModel.setText(""); return }
        models += m
        editModel.setText("")
        buildModelChips()
        updatePreview()
    }

    private fun buildModelChips() {
        modelChips.removeAllViews()
        modelChips.visibility = if (models.isEmpty()) View.GONE else View.VISIBLE
        updateNamesHint()
        models.forEach { m ->
            modelChips.addView(Chips.make(this, "$m  ✕", on = true).apply {
                contentDescription = "Убрать $m"
                setOnClickListener { models.remove(m); buildModelChips(); updatePreview() }
            })
        }
    }

    // ================================================================ hashtags

    /** Back from the tag manager: the library may have changed. */
    override fun onRestart() {
        super.onRestart()
        tags.reload()
        updatePreview()
    }

    // ================================================================ preview

    private fun caption() = PostCaption.build(editStudio.text.toString(), models, tags.ordered())

    private fun updatePreview() {
        val text = caption()
        val sp = SpannableString(text)
        Regex("#[\\p{L}\\p{N}_]+").findAll(text).forEach {
            sp.setSpan(ForegroundColorSpan(TG_LINK), it.range.first, it.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        txtPreview.text = sp
        val n = PostCaption.length(text)
        txtCounter.text = "$n / ${PostCaption.MAX}"
        txtCounter.setTextColor(tc(if (n > PostCaption.MAX) R.color.danger else R.color.textDim))
        txtTagCount.text = if (tags.selected.isEmpty()) "" else "выбрано ${tags.selected.size}"
    }

    // ================================================================ publishing

    private var posted = false

    private fun publish() {
        if (posting) return
        if (!store.configured) { setup(); return }
        val studio = editStudio.text.toString().trim()
        if (editModel.text.isNotBlank()) addModel()   // typed but not added yet
        val problems = buildList {
            if (files.size != PostCaption.PHOTOS) add("нужно ${PostCaption.PHOTOS} фото")
            if (studio.isEmpty()) add("не указана студия")
            if (models.isEmpty()) add("не указана модель")
            if (PostCaption.length(caption()) > PostCaption.MAX) add("подпись длиннее ${PostCaption.MAX} символов")
        }
        if (problems.isNotEmpty()) {
            toast("Не хватает: " + problems.joinToString(", "))
            btnPublish.wiggle()
            if (studio.isEmpty()) editStudio.requestFocus() else if (models.isEmpty()) editModel.requestFocus()
            return
        }
        val text = caption()
        val withoutTags = tags.selected.isEmpty()
        val dup = duplicates()
        val b = AlertDialog.Builder(this)
        if (dup != null && dup.any) {
            // already posted: say what exactly, and ask
            b.setTitle("⚠ Это уже было в канале")
                .setMessage(describe(dup) + "\n\nВсё равно опубликовать?")
                .setPositiveButton("Опубликовать") { _, _ -> route(text, studio) }
        } else {
            b.setTitle("Опубликовать в «${store.chatTitle.ifEmpty { store.chat }}»?")
                .setMessage(text + (if (withoutTags) "\n\n(без хэштегов)" else "") +
                    (if (swVideo.isChecked) "\n\n+ видео после фото" else "") +
                    (if (sendAt > 0) "\n\n" + txtWhen.text else ""))
                .setPositiveButton(if (sendAt > 0) "Запланировать" else "Опубликовать") { _, _ -> route(text, studio) }
        }
        b.setNegativeButton("Отмена", null).show()
    }

    private fun route(text: String, studio: String) =
        if (swVideo.isChecked || sendAt > 0) sendViaAccount(text, studio) else send(text, studio)

    private fun send(text: String, studio: String) {
        posting = true
        setBusy(true, "Готовлю фото…", 0)
        val token = store.token; val chat = store.chat
        val list = files.toList()
        io.execute {
            val r = runCatching {
                val photos = list.mapIndexed { i, f ->
                    ui.post { setBusy(true, "Готовлю фото ${i + 1} из ${list.size}…", (i + 1) * 10 / list.size) }
                    PhotoPrep.jpeg(f)
                }
                val api = TelegramApi(token)
                fun attempt() = api.sendAlbum(chat, photos, text) { p ->
                    ui.post { setBusy(true, if (p < 100) "Отправляю… $p%" else "Telegram обрабатывает…", 10 + p * 90 / 100) }
                }
                try { attempt() } catch (e: TgException) {
                    // "too many requests": wait as asked and try once more
                    if (e.retryAfter in 1..60) { Thread.sleep(e.retryAfter * 1000L + 500); attempt() } else throw e
                }
            }
            ui.post {
                posting = false
                r.onSuccess { msgId -> onPosted(msgId, studio) }
                    .onFailure {
                        setBusy(false, null, 0)
                        val msg = when (it) {
                            is java.net.UnknownHostException, is java.net.SocketTimeoutException, is java.net.ConnectException ->
                                "Нет связи с Telegram. Проверь интернет и попробуй снова."
                            else -> it.message ?: it.javaClass.simpleName
                        }
                        AlertDialog.Builder(this).setTitle("Не опубликовано").setMessage(msg)
                            .setPositiveButton("OK", null).show()
                    }
            }
        }
    }

    /** Remembers the post (duplicates check, names, tag use) and clears the draft. */
    private fun recordPost(studio: String, msgId: Long) {
        posted = true
        store.rememberUsed(studio, models)
        runCatching {
            log.add(PostLog.Post(
                System.currentTimeMillis(), store.chat, msgId, if (msgId > 0) postLink(msgId) else null, studio, models.toList(), tags.ordered(),
                intent.getStringExtra(EXTRA_VIDEO) ?: "", videoKey, files.mapNotNull { hashes[it.path] },
            ))
        }
        com.dave.framegrabber.telegram.TagLibrary(this).countUse(tags.selected)
        store.clearDraft()
        setResult(RESULT_OK, Intent())
    }

    private fun onPosted(msgId: Long, studio: String) {
        setBusy(false, null, 0)
        recordPost(studio, msgId)
        val link = postLink(msgId)
        val b = AlertDialog.Builder(this)
            .setTitle("Опубликовано ✓")
            .setMessage("Пост в «${store.chatTitle.ifEmpty { store.chat }}» готов.")
            .setPositiveButton("Готово") { _, _ ->
                // back in the app: straight to the channel feed, where the new post shows up
                setResult(RESULT_OK, Intent().putExtra(EXTRA_OPEN_FEED, true))
                finish()
            }
            .setOnCancelListener { finish() }
        if (link != null) b.setNeutralButton("Открыть в Telegram") { _, _ ->
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
            finish()
        }
        b.show()
    }

    private fun postLink(msgId: Long): String? = when {
        store.chatUsername.isNotEmpty() -> "https://t.me/${store.chatUsername}/$msgId"
        store.chat.startsWith("-100") -> "https://t.me/c/${store.chat.removePrefix("-100")}/$msgId"
        else -> null
    }

    // ================================================================ already posted?

    /** Video / pictures / names compared with earlier posts (null until the photos are hashed). */
    private fun duplicates(): PostLog.Duplicates? {
        if (hashes.isEmpty()) return null
        return log.check(videoKey, files.mapNotNull { hashes[it.path] }, editStudio.text.toString(), models)
    }

    /** Photos (file paths) that were in earlier posts. */
    private var dupPaths: Set<String> = emptySet()

    private fun updateDupWarning() {
        val dup = duplicates() ?: return
        dupPaths = dup.sameFrames.keys.map { files[it - 1].path }.toSet()
        if (dup.sameVideo.isEmpty() && dup.sameFrames.isEmpty()) { cardDup.visibility = View.GONE; return }
        txtDup.text = describe(dup, withNames = false)
        cardDup.visibility = View.VISIBLE
    }

    /** "this studio + these models" posted before: a line under the fields. */
    private fun updateNamesHint() {
        if (!::log.isInitialized) return
        val same = log.check("", emptyList(), editStudio.text.toString(), models).sameNames
        if (same.isEmpty()) { txtNamesHint.visibility = View.GONE; return }
        txtNamesHint.visibility = View.VISIBLE
        txtNamesHint.text = "Эта студия с этими моделями уже была: постов ${same.size}, последний ${date(same.last().time)}"
    }

    private fun describe(dup: PostLog.Duplicates, withNames: Boolean = true): String = buildString {
        fun post(p: PostLog.Post) = "${date(p.time)} — ${p.studio} - ${p.models.joinToString(" & ")}"
        if (dup.sameVideo.isNotEmpty()) {
            append("• Это видео уже выкладывалось: ")
            append(dup.sameVideo.takeLast(3).joinToString("; ") { post(it) })
            append('\n')
        }
        if (dup.sameFrames.isNotEmpty()) {
            // grouped by post: "фото 1, 3, 4 — в посте от 5 нояб."
            append("• Такие же кадры уже были: ")
            append(dup.sameFrames.entries.groupBy({ it.value }, { it.key }).entries.joinToString("; ") { (p, nums) ->
                "фото " + nums.sorted().joinToString(", ") + " — в посте от ${date(p.time)}"
            })
            append('\n')
        }
        if (withNames && dup.sameNames.isNotEmpty() && dup.sameVideo.isEmpty()) {
            append("• Эта студия с этими моделями уже была: ${post(dup.sameNames.last())}\n")
        }
    }.trim()

    private fun date(t: Long): String = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale("ru")).format(java.util.Date(t))

    private fun setBusy(busy: Boolean, text: String?, p: Int) {
        btnPublish.isEnabled = !busy
        btnPublish.alpha = if (busy) 0.5f else 1f
        btnPublish.text = if (busy) "Публикую…" else "Опубликовать"
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        progress.max = 100
        progress.setProgress(p, true)
        status.visibility = if (text != null) View.VISIBLE else View.GONE
        status.text = text ?: ""
    }
}
