package com.dave.framegrabber.ui.telegram

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.dave.framegrabber.R
import com.dave.framegrabber.core.FrameRender
import com.dave.framegrabber.data.AppTheme
import com.dave.framegrabber.data.Projects
import com.dave.framegrabber.split.SplitPlan
import com.dave.framegrabber.telegram.PostCaption
import com.dave.framegrabber.telegram.PostLog
import com.dave.framegrabber.tgclient.PostQueue
import com.dave.framegrabber.tgclient.Tg
import com.dave.framegrabber.tgclient.TgPublish
import com.dave.framegrabber.ui.tc
import com.dave.framegrabber.ui.toast
import com.dave.framegrabber.ui.widget.SynthwaveView
import java.io.File
import java.util.concurrent.Executors

/**
 * «Очередь постов»: pick videos that have frames (in the order they should go out), compose a post
 * for each one ([PostActivity] in queue mode, one after another), then they are uploaded to the
 * channel by themselves, one after another ([PostQueue]). Also shows how the queue is doing.
 */
class QueueActivity : Activity() {

    private class Video(val s: Projects.Summary, val size: Long, val reachable: Boolean, var cover: Bitmap? = null)

    private val d get() = resources.displayMetrics.density
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var status: LinearLayout
    private lateinit var listBox: LinearLayout
    private lateinit var btnNext: Button
    private lateinit var bottom: View

    private val videos = mutableListOf<Video>()
    /** Picked videos in the order of tapping = order of upload. */
    private val picked = mutableListOf<Video>()
    /** Composing: which picked video is on the post page now, and what was composed so far. */
    private var step = -1
    private val composed = mutableListOf<PostQueue.Item>()

    private val onQueue: () -> Unit = { ui.removeCallbacks(redrawStatus); ui.postDelayed(redrawStatus, 300) }
    private val redrawStatus = Runnable { if (!isDestroyed) buildStatus() }

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
            text = "Очередь постов"; textSize = 19f; setTextColor(tc(R.color.text)); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding((12 * d).toInt(), 0, 0, 0)
        })
        content.addView(bar)

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((14 * d).toInt(), 0, (14 * d).toInt(), (20 * d).toInt()) }
        status = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(status)
        body.addView(TextView(this).apply {
            text = "Выбери видео в том порядке, в котором их заливать. Потом для каждого откроется страница поста: кадры, студия, модели, теги. Посты уйдут в канал сразу, один за другим; большие видео разрежутся на части."
            textSize = 13f; setTextColor(tc(R.color.textDim)); setLineSpacing(0f, 1.1f)
            setPadding((4 * d).toInt(), (10 * d).toInt(), (4 * d).toInt(), (8 * d).toInt())
        })
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(listBox)
        content.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))

        val bottomBar = LinearLayout(this).apply { setPadding((14 * d).toInt(), (8 * d).toInt(), (14 * d).toInt(), (12 * d).toInt()) }
        btnNext = Button(this, null, 0, R.style.BtnPrimary).apply {
            gravity = Gravity.CENTER
            setOnClickListener { startComposing() }
        }
        bottomBar.addView(btnNext, LinearLayout.LayoutParams(-1, -2))
        bottom = bottomBar
        content.addView(bottomBar)

        Tg.start(this)
        PostQueue.listeners += onQueue
        TgPublish.listeners += onQueue
        buildStatus()
        updateNext()
        load()
    }

    override fun onDestroy() {
        PostQueue.listeners -= onQueue
        TgPublish.listeners -= onQueue
        io.shutdownNow()
        super.onDestroy()
    }

    // ================================================================ the queue now

    private fun buildStatus() {
        status.removeAllViews()
        val items = PostQueue.items(this)
        if (items.isEmpty()) return
        val card = LinearLayout(this, null, 0, R.style.Card)
        val active = PostQueue.active(this)
        val failed = items.count { it.state == PostQueue.State.FAILED }
        val done = items.count { it.state == PostQueue.State.DONE }
        card.addView(TextView(this, null, 0, R.style.CardTitle).apply {
            text = when {
                active -> "Заливается: ${done + failed} из ${items.size} готово"
                failed > 0 -> "Готово: ушло $done из ${items.size}"
                else -> "Готово: все ${items.size} в канале ✓"
            }
        })
        items.forEachIndexed { i, it ->
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt()) }
            val (icon, color) = when (it.state) {
                PostQueue.State.WAITING -> "⏳" to R.color.textDim
                PostQueue.State.SENDING -> "⬆" to R.color.accent
                PostQueue.State.DONE -> "✓" to R.color.accent
                PostQueue.State.FAILED -> "✕" to R.color.danger
            }
            row.addView(TextView(this).apply { text = icon; textSize = 16f; setTextColor(tc(color)); gravity = Gravity.CENTER },
                LinearLayout.LayoutParams((30 * d).toInt(), -2))
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(this).apply {
                text = "${i + 1}. " + it.title.ifBlank { it.videoName }; textSize = 14f; setTextColor(tc(R.color.text)); maxLines = 2
            })
            val sub = when (it.state) {
                PostQueue.State.WAITING -> "ждёт очереди"
                PostQueue.State.SENDING -> if (TgPublish.running) TgPublish.stage else "начинаю…"
                PostQueue.State.DONE -> it.result ?: "в канале"
                PostQueue.State.FAILED -> "не ушёл: " + (it.error ?: "ошибка")
            }
            col.addView(TextView(this).apply { text = sub; textSize = 12f; setTextColor(tc(if (it.state == PostQueue.State.FAILED) R.color.danger else R.color.textDim)) })
            if (it.state == PostQueue.State.SENDING && TgPublish.running) col.addView(
                ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 1000; progress = (TgPublish.fraction * 1000).toInt()
                    progressTintList = android.content.res.ColorStateList.valueOf(tc(R.color.accent))
                }, LinearLayout.LayoutParams(-1, (6 * d).toInt()).apply { topMargin = (4 * d).toInt() })
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            if (it.state == PostQueue.State.WAITING || it.state == PostQueue.State.FAILED) row.addView(TextView(this).apply {
                text = "✕"; textSize = 15f; setTextColor(tc(R.color.textDim)); gravity = Gravity.CENTER
                contentDescription = "Убрать из очереди"
                setOnClickListener { _ -> PostQueue.remove(this@QueueActivity, it.id); buildStatus() }
            }, LinearLayout.LayoutParams((36 * d).toInt(), (36 * d).toInt()))
            card.addView(row)
        }
        val btns = LinearLayout(this).apply { setPadding(0, (8 * d).toInt(), 0, 0) }
        fun btn(t: String, f: () -> Unit) = Button(this, null, 0, R.style.BtnSecondary).apply {
            text = t; gravity = Gravity.CENTER; maxLines = 1; setOnClickListener { f() }
        }
        if (active) btns.addView(btn("Остановить очередь") {
            AlertDialog.Builder(this, R.style.DialogTheme)
                .setMessage("Остановить? Пост, который сейчас заливается, отменится (то, что из него уже ушло, удалится), остальные уберутся из очереди.")
                .setPositiveButton("Остановить") { _, _ -> PostQueue.stopAll(this); buildStatus() }
                .setNegativeButton("Нет", null).show()
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (failed > 0) btns.addView(btn("Залить заново ($failed)") { PostQueue.retryFailed(this); buildStatus() },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = (6 * d).toInt() })
        if (!active) btns.addView(btn("Очистить") { PostQueue.clearFinished(this); buildStatus() },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = (6 * d).toInt() })
        card.addView(btns)
        status.addView(card)
    }

    // ================================================================ videos with frames

    private fun load() {
        listBox.removeAllViews()
        listBox.addView(ProgressBar(this), LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = (30 * d).toInt() })
        io.execute {
            val list = runCatching { Projects.list(this).filter { it.frames >= PostCaption.PHOTOS } }.getOrDefault(emptyList())
                .map { s -> val sz = sizeOf(s.uri); Video(s, sz, sz > 0) }
            runOnUiThread { if (!isDestroyed) { videos.clear(); videos += list; buildList() } }
            list.forEach { v ->
                v.cover = FrameRender.decodeFile(Projects.cover(this, v.s.id), 200)
                    ?: Projects.load(this, v.s.id)?.frames?.firstOrNull()?.let { FrameRender.decodeFile(File(Projects.dir(this, v.s.id), it.file), 200) }
                runOnUiThread { if (!isDestroyed) buildList() }
            }
        }
    }

    private fun sizeOf(u: Uri): Long = runCatching {
        if (u.scheme == "file") File(u.path!!).let { if (it.exists()) it.length() else -1L }
        else contentResolver.openAssetFileDescriptor(u, "r")?.use { it.length } ?: -1L
    }.getOrDefault(-1L)

    private fun buildList() {
        listBox.removeAllViews()
        if (videos.isEmpty()) {
            listBox.addView(TextView(this).apply {
                text = "Нет видео с ${PostCaption.PHOTOS}+ снятыми кадрами. Открой видео, сними кадры — и оно появится здесь."
                textSize = 14f; setTextColor(tc(R.color.textDim)); gravity = Gravity.CENTER
                setPadding((20 * d).toInt(), (30 * d).toInt(), (20 * d).toInt(), 0)
            })
            return
        }
        val limit = SplitPlan.TELEGRAM_LIMIT
        videos.forEach { v ->
            val n = picked.indexOf(v) + 1
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_card)
                setPadding((10 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
                if (n > 0) foreground = getDrawable(R.drawable.fg_selected)
                setOnClickListener { toggle(v) }
            }
            row.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundResource(R.drawable.bg_thumb); clipToOutline = true
                v.cover?.let { setImageBitmap(it) }
            }, LinearLayout.LayoutParams((84 * d).toInt(), (56 * d).toInt()))
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((12 * d).toInt(), 0, (8 * d).toInt(), 0) }
            col.addView(TextView(this).apply { text = v.s.name; textSize = 14f; setTextColor(tc(R.color.text)); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
            col.addView(TextView(this).apply {
                textSize = 12f
                text = "${v.s.frames} кадров · " + when {
                    !v.reachable -> "видео не найдено — только фото"
                    v.size > limit -> "${gb(v.size)} — разрежется на части"
                    else -> gb(v.size)
                }
                setTextColor(tc(if (!v.reachable) R.color.warn else R.color.textDim))
            })
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(TextView(this).apply {
                gravity = Gravity.CENTER; textSize = 14f; typeface = Typeface.DEFAULT_BOLD
                if (n > 0) {
                    text = n.toString(); setTextColor(android.graphics.Color.WHITE)
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(tc(R.color.accent)) }
                } else background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke((1.5f * d).toInt(), tc(R.color.textDim)) }
            }, LinearLayout.LayoutParams((28 * d).toInt(), (28 * d).toInt()))
            listBox.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * d).toInt() })
        }
    }

    private fun gb(b: Long) = if (b >= 1L shl 30) String.format(java.util.Locale.US, "%.2f ГБ", b / (1L shl 30).toDouble())
        else String.format(java.util.Locale.US, "%.0f МБ", b / (1L shl 20).toDouble())

    private fun toggle(v: Video) {
        if (!picked.remove(v)) picked += v
        buildList(); updateNext()
    }

    private fun updateNext() {
        btnNext.isEnabled = picked.isNotEmpty()
        btnNext.alpha = if (picked.isEmpty()) 0.45f else 1f
        btnNext.text = if (picked.isEmpty()) "Выбери видео" else "Далее · постов: ${picked.size}"
    }

    // ================================================================ composing, one post after another

    private fun startComposing() {
        if (picked.isEmpty()) return
        if (Tg.state != Tg.State.READY) {
            AlertDialog.Builder(this, R.style.DialogTheme).setTitle("Нужен вход в Telegram")
                .setMessage("Очередь заливает через твой аккаунт. Войди во вкладке «Канал» (один раз), потом вернись сюда.")
                .setPositiveButton("OK", null).show()
            return
        }
        composed.clear()
        step = 0
        openStep()
    }

    private fun openStep() {
        val v = picked[step]
        btnNext.isEnabled = false; btnNext.text = "Открываю пост ${step + 1}…"
        io.execute {
            val p = Projects.load(this, v.s.id)
            val frames = p?.frames.orEmpty()
            val dir = Projects.dir(this, v.s.id)
            val dur = if (v.reachable) runCatching {
                MediaMetadataRetriever().run {
                    try { setDataSource(this@QueueActivity, v.s.uri); extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L }
                    finally { release() }
                }
            }.getOrDefault(0L) * 1000 else 0L
            val key = PostLog.videoKey(maxOf(v.size, 0), dur)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                updateNext()
                if (frames.size < PostCaption.PHOTOS) { toast("У «${v.s.name}» меньше ${PostCaption.PHOTOS} кадров — пропускаю"); afterStep(); return@runOnUiThread }
                @Suppress("DEPRECATION")
                startActivityForResult(PostActivity.queueIntent(this,
                    frames.map { File(dir, it.file).path }, frames.map { it.timeUs },
                    v.s.name, key, if (v.reachable) v.s.uri else null, step + 1, picked.size), REQ_POST)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_POST || step < 0) return
        val v = picked.getOrNull(step) ?: return
        if (resultCode == RESULT_OK && data != null) {
            composed += PostQueue.Item(
                id = System.currentTimeMillis().toString(36) + "_" + step,
                title = data.getStringExtra(PostActivity.RES_TITLE) ?: v.s.name,
                videoName = v.s.name,
                photos = data.getStringArrayListExtra(PostActivity.RES_FILES).orEmpty(),
                caption = data.getStringExtra(PostActivity.RES_TEXT) ?: "",
                video = if (data.getBooleanExtra(PostActivity.RES_VIDEO, false)) v.s.uri.toString() else null,
            )
            afterStep()
        } else {
            // back from a post page: what now?
            AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle("Пост ${step + 1} из ${picked.size}")
                .setItems(arrayOf("Вернуться к этому посту", "Пропустить это видео", "Отменить всю очередь")) { _, i ->
                    when (i) {
                        0 -> openStep()
                        1 -> afterStep()
                        else -> { step = -1; composed.clear(); toast("Очередь не создана") }
                    }
                }
                .setOnCancelListener { openStep() }
                .show()
        }
    }

    private fun afterStep() {
        step++
        if (step < picked.size) { openStep(); return }
        step = -1
        if (composed.isEmpty()) { toast("Ни одного поста не составлено"); return }
        val n = composed.size
        PostQueue.add(this, composed.toList())
        composed.clear(); picked.clear()
        buildList(); updateNext(); buildStatus()
        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle("Очередь запущена ✓")
            .setMessage("Постов: $n. Они уйдут в канал по очереди, сразу один за другим. Прогресс — здесь, внизу ленты и в уведомлении; приложение можно закрыть.")
            .setPositiveButton("OK", null).show()
    }

    companion object { private const val REQ_POST = 41 }
}
