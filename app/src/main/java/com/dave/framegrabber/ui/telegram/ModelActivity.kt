package com.dave.framegrabber.ui.telegram

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.dave.framegrabber.R
import com.dave.framegrabber.data.AppTheme
import com.dave.framegrabber.data.CaptureStore
import com.dave.framegrabber.tgclient.ChannelStats
import com.dave.framegrabber.tgclient.Tg
import com.dave.framegrabber.ui.tc
import com.dave.framegrabber.ui.toast
import com.dave.framegrabber.ui.widget.SynthwaveView
import org.drinkless.tdlib.TdApi
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * A model's page: her photo (add / change it), her name, and all the channel's posts with her
 * (3 per row, newest first). Tap a post → the feed opens right at it.
 */
class ModelActivity : Activity() {

    companion object {
        private const val EXTRA_CHAT = "chat"
        private const val EXTRA_NAME = "name"
        /** Result: open the feed at this message. */
        const val EXTRA_JUMP = "jump"
        private const val REQ_IMAGE = 1

        fun intent(ctx: Context, chatId: Long, name: String) =
            Intent(ctx, ModelActivity::class.java).putExtra(EXTRA_CHAT, chatId).putExtra(EXTRA_NAME, name)

        /** Where a model's photo is kept (by name, any letter case). */
        fun photoFile(ctx: Context, name: String) =
            File(File(ctx.filesDir, "models").apply { mkdirs() }, "m_" + name.trim().lowercase().hashCode().toUInt().toString(16) + ".jpg")
    }

    private val d get() = resources.displayMetrics.density
    private val io = Executors.newFixedThreadPool(3)
    private val dateFmt = SimpleDateFormat("d MMM yyyy", Locale("ru"))
    private var chatId = 0L
    private lateinit var name: String
    private lateinit var photo: ImageView
    private lateinit var addHint: TextView
    private lateinit var sub: TextView
    private lateinit var grid: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        chatId = intent.getLongExtra(EXTRA_CHAT, 0)
        name = intent.getStringExtra(EXTRA_NAME) ?: ""
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

        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding((10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt(), 0)
        }
        bar.addView(ImageButton(this, null, 0, R.style.IconBtn).apply {
            setImageResource(R.drawable.ic_arrow_back); contentDescription = "Назад"
            layoutParams = LinearLayout.LayoutParams((46 * d).toInt(), (46 * d).toInt())
            setOnClickListener { finish() }
        })
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(ImageButton(this, null, 0, R.style.IconBtn).apply {
            setImageResource(R.drawable.ic_search); contentDescription = "Искать в ленте"
            layoutParams = LinearLayout.LayoutParams((46 * d).toInt(), (46 * d).toInt())
            setOnClickListener { setResult(RESULT_OK, Intent().putExtra(StatsActivity.EXTRA_SEARCH, name)); finish() }
        })
        content.addView(bar)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding((14 * d).toInt(), 0, (14 * d).toInt(), (24 * d).toInt())
        }
        content.addView(ScrollView(this).apply { addView(body); clipToPadding = false }, LinearLayout.LayoutParams(-1, 0, 1f))

        // her photo (tap = add / change)
        val box = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.bg_thumb)
            clipToOutline = true
            foreground = getDrawable(R.drawable.fg_selected)
            setOnClickListener { choosePhoto() }
        }
        photo = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        box.addView(photo, FrameLayout.LayoutParams(-1, -1))
        addHint = TextView(this).apply {
            text = "＋\nДобавить фото"; gravity = Gravity.CENTER; textSize = 14f; setTextColor(tc(R.color.accent))
        }
        box.addView(addHint, FrameLayout.LayoutParams(-1, -1))
        val side = minOf((220 * d).toInt(), resources.displayMetrics.widthPixels - (80 * d).toInt())
        body.addView(box, LinearLayout.LayoutParams(side, side).apply { topMargin = (6 * d).toInt() })

        body.addView(TextView(this).apply {
            text = name; textSize = 26f; setTextColor(tc(R.color.text)); gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, (14 * d).toInt(), 0, 0)
        })
        sub = TextView(this).apply {
            textSize = 13f; setTextColor(tc(R.color.textDim)); gravity = Gravity.CENTER
            setPadding(0, (2 * d).toInt(), 0, (14 * d).toInt())
            text = "Загружаю посты…"
        }
        body.addView(sub)
        grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(grid, LinearLayout.LayoutParams(-1, -2))

        showPhoto()
        val cached = ChannelStats.load(this, chatId)
        if (cached.isNotEmpty()) show(cached)
        io.execute {
            val fresh = runCatching { ChannelStats.update(applicationContext, chatId) {} }.getOrNull()
            runOnUiThread { if (!isDestroyed && fresh != null) show(fresh) else if (cached.isEmpty()) sub.text = "Не удалось прочитать канал" }
        }
    }

    override fun onDestroy() { io.shutdownNow(); super.onDestroy() }

    // ================================================================ photo

    private fun showPhoto() {
        val f = photoFile(this, name)
        val b = if (f.exists()) BitmapFactory.decodeFile(f.path) else null
        photo.setImageBitmap(b)
        addHint.visibility = if (b == null) View.VISIBLE else View.GONE
    }

    private fun choosePhoto() {
        val frames = CaptureStore.captures.toList()
        val items = buildList {
            add("Из галереи")
            if (frames.isNotEmpty()) add("Из кадров открытого видео (${frames.size})")
            if (photoFile(this@ModelActivity, name).exists()) add("Убрать фото")
        }
        AlertDialog.Builder(this).setTitle("Фото: $name").setItems(items.toTypedArray()) { _, i ->
            when (items[i]) {
                "Из галереи" -> runCatching {
                    startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), REQ_IMAGE)
                }.onFailure { toast("Нет приложения для выбора фото") }
                "Убрать фото" -> { photoFile(this, name).delete(); showPhoto() }
                else -> pickFrame(frames)
            }
        }.show()
    }

    /** The frames of the open video, 3 per row: tap one. */
    private fun pickFrame(frames: List<com.dave.framegrabber.data.Capture>) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), 0) }
        lateinit var dlg: AlertDialog
        frames.chunked(3).forEach { row ->
            val line = LinearLayout(this).apply { weightSum = 3f }
            row.forEach { c ->
                line.addView(ImageView(this).apply {
                    setImageBitmap(c.thumb); scaleType = ImageView.ScaleType.CENTER_CROP
                    setPadding((2 * d).toInt(), (2 * d).toInt(), (2 * d).toInt(), (2 * d).toInt())
                    setOnClickListener { dlg.dismiss(); savePhoto { BitmapFactory.decodeFile(c.file.path) } }
                }, LinearLayout.LayoutParams(0, (100 * d).toInt(), 1f))
            }
            box.addView(line)
        }
        dlg = AlertDialog.Builder(this).setTitle("Выбери кадр").setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton("Отмена", null).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (requestCode == REQ_IMAGE && resultCode == RESULT_OK)
            savePhoto { contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } }
    }

    /** Center square, 720 px, JPEG. */
    private fun savePhoto(load: () -> Bitmap?) {
        io.execute {
            val r = runCatching {
                val src = load() ?: throw IllegalStateException("Не удалось открыть картинку")
                val s = minOf(src.width, src.height)
                val sq = Bitmap.createBitmap(src, (src.width - s) / 2, (src.height - s) / 3, s, s)
                val out = Bitmap.createScaledBitmap(sq, minOf(720, s), minOf(720, s), true)
                photoFile(this, name).outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            }
            runOnUiThread { r.onSuccess { showPhoto() }.onFailure { toast(it.message ?: "Ошибка") } }
        }
    }

    // ================================================================ posts

    private fun show(all: List<ChannelStats.Post>) {
        val key = name.trim().lowercase()
        val posts = all.filter { p -> p.models.any { it.trim().lowercase() == key } }.sortedByDescending { it.id }
        val videos = posts.count { it.video }
        sub.text = if (posts.isEmpty()) "Постов пока нет"
            else "${posts.size} пост. · с видео $videos · последний ${dateFmt.format(Date(posts.first().time))}"
        grid.removeAllViews()
        val gap = (6 * d).toInt()
        posts.chunked(3).forEachIndexed { ri, row ->
            val line = LinearLayout(this).apply {
                weightSum = 3f
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { if (ri > 0) topMargin = gap }
            }
            for (i in 0 until 3) {
                val p = row.getOrNull(i)
                val cell = Square(this).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = gap } }
                if (p != null) {
                    cell.setBackgroundResource(R.drawable.bg_thumb); cell.clipToOutline = true
                    val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                    cell.addView(iv, FrameLayout.LayoutParams(-1, -1))
                    cell.addView(TextView(this).apply {
                        text = dateFmt.format(Date(p.time)) + if (p.video) " · 🎬" else ""
                        textSize = 10f; setTextColor(0xFFFFFFFF.toInt())
                        setBackgroundResource(R.drawable.bg_badge)
                        setPadding((5 * d).toInt(), (1 * d).toInt(), (5 * d).toInt(), (1 * d).toInt())
                    }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins((4 * d).toInt(), 0, 0, (4 * d).toInt()) })
                    cell.setOnClickListener { setResult(RESULT_OK, Intent().putExtra(EXTRA_JUMP, p.id)); finish() }
                    loadThumb(p.id, iv)
                }
                line.addView(cell)
            }
            grid.addView(line)
        }
    }

    /** The post's first photo, through Telegram. */
    private fun loadThumb(msgId: Long, into: ImageView) {
        io.execute {
            val m = Tg.call(TdApi.GetMessage(chatId, msgId)) as? TdApi.Message
            val file = when (val c = m?.content) {
                is TdApi.MessagePhoto -> c.photo.sizes.sortedBy { it.width }.let { s -> s.firstOrNull { it.width >= 320 } ?: s.lastOrNull() }?.photo
                is TdApi.MessageVideo -> c.video.thumbnail?.file
                else -> null
            } ?: return@execute
            val path = Tg.fetch(file, 8) ?: return@execute
            val b = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 1 })
            runOnUiThread { if (!isDestroyed) { into.setImageBitmap(b); into.alpha = 0f; into.animate().alpha(1f).setDuration(200).start() } }
        }
    }

    private class Square(c: Context) : FrameLayout(c) {
        override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, w)
    }
}
