package com.dave.framegrabber.ui.main

import com.dave.framegrabber.ui.tc

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import com.dave.framegrabber.R
import com.dave.framegrabber.core.TimeUtil
import com.dave.framegrabber.data.Made
import com.dave.framegrabber.data.Naming
import com.dave.framegrabber.data.Output
import com.dave.framegrabber.export.Exporters
import com.dave.framegrabber.export.GifEncoder
import com.dave.framegrabber.export.GifOptions
import com.dave.framegrabber.ui.anim
import com.dave.framegrabber.ui.bump
import com.dave.framegrabber.ui.dp
import com.dave.framegrabber.ui.shareUri
import com.dave.framegrabber.ui.toast
import java.io.File

/**
 * Page «Клипы»: GIF and MP4 clip from this page's «Отрезок», and the list of the ones made so far
 * (tap = open, share, delete). Files are written straight into the destination (no temp files).
 */
class ClipsPanel(private val s: MainScreen) {

    private val radioFps = s.h<RadioGroup>(R.id.radioGifFps)
    private val radioWidth = s.h<RadioGroup>(R.id.radioGifW)
    private val radioQuality = s.h<RadioGroup>(R.id.radioGifQ)
    private val radioSpeed = s.h<RadioGroup>(R.id.radioGifSpeed)
    private val swBoomerang = s.h<Switch>(R.id.swBoomerang)
    private val txtQualityHint = s.h<TextView>(R.id.txtGifQHint)
    private val txtGifInfo = s.h<TextView>(R.id.txtGifInfo)
    private val gifJob = ExportJob(s, s.h(R.id.progressGif), s.h(R.id.txtGif))
    private val clipJob = ExportJob(s, s.h(R.id.progressClips), s.h(R.id.txtClips))
    private val madeList = s.h<LinearLayout>(R.id.madeList)
    private val madeEmpty = s.h<View>(R.id.txtMadeEmpty)
    private val madeCount = s.h<TextView>(R.id.txtMadeCount)

    private val fpsIds = mapOf(R.id.fps10 to 10, R.id.fps15 to 15, R.id.fps20 to 20, R.id.fps25 to 25, R.id.fps30 to 30)
    private val widthIds = mapOf(R.id.gw320 to 320, R.id.gw480 to 480, R.id.gw720 to 720, R.id.gw1080 to 1080, R.id.gwOrig to 0)
    private val qualityIds = mapOf(
        R.id.gqNormal to GifEncoder.Quality.NORMAL, R.id.gqHigh to GifEncoder.Quality.HIGH, R.id.gqMax to GifEncoder.Quality.MAX
    )
    private val speedIds = mapOf(R.id.gs05 to 0.5f, R.id.gs1 to 1f, R.id.gs2 to 2f)

    init {
        // saved values first, then listeners
        val p = s.prefs
        radioFps.check(fpsIds.entries.firstOrNull { it.value == p.gifFps }?.key ?: R.id.fps15)
        radioWidth.check(widthIds.entries.firstOrNull { it.value == p.gifWidth }?.key ?: R.id.gw480)
        radioQuality.check(qualityIds.entries.firstOrNull { it.value.name == p.gifQuality }?.key ?: R.id.gqHigh)
        radioSpeed.check(speedIds.entries.firstOrNull { it.value == p.gifSpeed }?.key ?: R.id.gs1)
        swBoomerang.isChecked = p.gifBoomerang
        radioFps.setOnCheckedChangeListener { _, id -> p.gifFps = fpsIds[id] ?: 15; updateGifInfo() }
        radioWidth.setOnCheckedChangeListener { _, id -> p.gifWidth = widthIds[id] ?: 480; updateGifInfo() }
        radioQuality.setOnCheckedChangeListener { _, id -> p.gifQuality = (qualityIds[id] ?: GifEncoder.Quality.HIGH).name; updateGifInfo() }
        radioSpeed.setOnCheckedChangeListener { _, id -> p.gifSpeed = speedIds[id] ?: 1f; updateGifInfo() }
        swBoomerang.setOnCheckedChangeListener { _, on -> p.gifBoomerang = on; updateGifInfo() }
        updateGifInfo()
        s.h<View>(R.id.btnGif).setOnClickListener { gif() }
        s.h<View>(R.id.btnClip).setOnClickListener { clip() }
        buildMade()
    }

    // ================================================================ GIF options

    private fun options() = GifOptions(
        fps = s.prefs.gifFps,
        width = s.prefs.gifWidth,
        quality = runCatching { GifEncoder.Quality.valueOf(s.prefs.gifQuality) }.getOrDefault(GifEncoder.Quality.HIGH),
        speed = s.prefs.gifSpeed,
        boomerang = s.prefs.gifBoomerang,
    )

    /** "≈ 90 кадров · 720×405 · 6.0 с" under the options (also called when the range or video changes). */
    fun updateGifInfo() {
        val o = options()
        txtQualityHint.text = when (o.quality) {
            GifEncoder.Quality.NORMAL -> "Быстро и маленький файл, на плавных переходах цвета возможны полосы"
            GifEncoder.Quality.HIGH -> "Плавные переходы цвета без полос (сглаживание)"
            GifEncoder.Quality.MAX -> "Самые точные цвета; файл больше, делается дольше"
        }
        val e = s.video.engine ?: run { txtGifInfo.text = "Сначала выбери видео"; return }
        val (from, to) = s.rangeClips.bounds(e)
        if (to <= from) { txtGifInfo.text = "Проверь отрезок"; return }
        val frames = o.outputFrames(from, to)
        val (w, h) = o.size(e)
        val seconds = frames / o.fps.toFloat()
        txtGifInfo.text = buildString {
            append("≈ $frames кадров · ${w}×${h} · %.1f с".format(seconds))
            if (frames > GifOptions.MAX_FRAMES) append("\nСлишком много кадров (макс. ${GifOptions.MAX_FRAMES}) — укороти отрезок или снизь плавность")
        }
        txtGifInfo.setTextColor(s.act.tc(if (frames > GifOptions.MAX_FRAMES) R.color.danger else R.color.textDim))
    }

    // ================================================================ making

    private fun gif() {
        val e = s.video.requireEngine() ?: return
        val uri = s.video.uri ?: return
        val (from, to) = s.rangeClips.require(e) ?: return
        val o = options()
        val frames = o.outputFrames(from, to)
        if (frames > GifOptions.MAX_FRAMES) {
            s.act.toast("Получится $frames кадров — максимум ${GifOptions.MAX_FRAMES}. Укороти отрезок или снизь плавность.")
            s.rangeClips.card.bump(); txtGifInfo.bump(); return
        }
        val (w, h) = o.size(e)
        val name = Naming.stem(s.prefs.namePattern, s.video.baseName, from, 1) + ".gif"
        val lengthUs = (frames * 1_000_000L / o.fps)
        val go = {
            gifJob.launch("GIF", "Делаю GIF…", "image/gif", onDone = { made(it, "image/gif", name, lengthUs) }) {
                Output.write(s.act, name, "image/gif", Output.Kind.IMAGE) { t ->
                    t.stream().buffered().use { os ->
                        Exporters.gif(s.act, uri, from, to, o, os) { gifJob.report(it, "GIF"); true }
                    }
                }
            }
        }
        // big + smooth GIFs take a while and make large files: say so first
        if (frames.toLong() * w * h > 250_000_000L) {
            AlertDialog.Builder(s.act)
                .setTitle("Большой GIF")
                .setMessage("$frames кадров в ${w}×${h}: это займёт пару минут, а файл может получиться десятки мегабайт. Продолжить?")
                .setPositiveButton("Сделать") { _, _ -> go() }
                .setNegativeButton("Отмена", null)
                .show()
        } else go()
    }

    private fun clip() {
        val e = s.video.requireEngine() ?: return
        val uri = s.video.uri ?: return
        val (from, to) = s.rangeClips.require(e) ?: return
        // MP4, or WebM for VP8/VP9 video; formats Android can't read get a clear message here
        val fmt = runCatching { Exporters.clipFormat(s.act, uri) }.getOrElse {
            AlertDialog.Builder(s.act).setTitle("Клип не получится").setMessage(it.message)
                .setPositiveButton("Сделать GIF") { _, _ -> gif() }
                .setNegativeButton("OK", null).show()
            return
        }
        val name = Naming.stem(s.prefs.namePattern, s.video.baseName, from, 1) + "_clip." + fmt.ext
        var result: Exporters.ClipResult? = null
        clipJob.launch("Клип", "Вырезаю клип…", fmt.mime, onDone = {
            made(it, fmt.mime, name, to - from)
            result?.droppedAudio?.takeIf { d -> d.isNotEmpty() }?.let { d ->
                s.act.toast("Клип без звука: звук ${d.joinToString()} нельзя сохранить в ${fmt.ext.uppercase()} без перекодирования")
            }
        }) {
            Output.write(s.act, name, fmt.mime, Output.Kind.VIDEO) { t ->
                val pfd = t.fd()
                if (pfd != null) {
                    // MediaMuxer writes straight into the destination file
                    pfd.use { fd ->
                        result = Exporters.clip(s.act, uri, from, to,
                            { MediaMuxer(fd.fileDescriptor, fmt.muxer) }
                        ) { clipJob.report(it, "Клип"); true }
                    }
                } else {
                    // folder without read/write access: build in the app's permanent storage, then copy
                    val tmp = File(File(s.act.filesDir, "export").apply { mkdirs() }, "clip." + fmt.ext)
                    try {
                        result = Exporters.clip(s.act, uri, from, to, tmp) { clipJob.report(it, "Клип"); true }
                        t.stream().use { o -> tmp.inputStream().use { it.copyTo(o) } }
                    } finally { tmp.delete() }
                }
            }
        }
    }

    private fun made(uri: android.net.Uri, mime: String, name: String, lengthUs: Long) {
        Made.add(s.act, uri, mime, name, lengthUs)
        buildMade(animateFirst = true)
    }

    // ================================================================ «Готовые»

    /**
     * The page was opened: GIFs and clips already in Pictures/FrameGrabber and Movies/FrameGrabber
     * join the list (needs the «photos» permission for files made before a reinstall — asked once).
     */
    fun onShown() {
        val act = s.act
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            act.checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            !act.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).getBoolean("askedImages", false)) {
            act.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).edit().putBoolean("askedImages", true).apply()
            act.requestPermissions(arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO), REQ_MEDIA)
            return
        }
        s.io.execute { if (runCatching { Made.syncWithGallery(act) }.getOrDefault(false)) s.ui.post { buildMade() } }
    }

    companion object { const val REQ_MEDIA = 78 }

    private fun buildMade(animateFirst: Boolean = false) {
        val items = Made.list(s.act)
        madeList.removeAllViews()
        madeEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        madeCount.text = items.size.toString()
        if (animateFirst) madeCount.bump()
        items.forEachIndexed { i, item ->
            val row = makeRow(item)
            madeList.addView(row)
            if (animateFirst && i == 0) {
                row.alpha = 0f; row.translationX = -40 * row.dp
                row.anim().alpha(1f).translationX(0f).setDuration(350).start()
            }
        }
    }

    private fun makeRow(item: Made.Item): View {
        val ctx = s.act
        val d = madeList.dp
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_row)
            val p = (8 * d).toInt()
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (6 * d).toInt() }
            stateListAnimator = android.animation.AnimatorInflater.loadStateListAnimator(ctx, R.animator.press_scale)
        }
        val box = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams((76 * d).toInt(), (54 * d).toInt())
            setBackgroundResource(R.drawable.bg_thumb)
            clipToOutline = true
        }
        val thumb = ImageView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        val tag = TextView(ctx).apply {
            text = if (item.isGif) "GIF" else "MP4"
            setBackgroundResource(R.drawable.bg_chip)
            setTextColor(ctx.tc(R.color.accent))
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            setPadding((5 * d).toInt(), 0, (5 * d).toInt(), 0)
            layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
                setMargins(0, 0, (3 * d).toInt(), (3 * d).toInt())
            }
        }
        box.addView(thumb); box.addView(tag)
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = (12 * d).toInt() }
        }
        texts.addView(TextView(ctx).apply {
            text = item.name; setTextColor(ctx.tc(R.color.text)); textSize = 14f
            maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
        })
        texts.addView(TextView(ctx).apply {
            val ago = DateUtils.getRelativeTimeSpanString(item.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            text = "${TimeUtil.format(item.lengthUs).substringBefore('.')} · $ago"
            setTextColor(ctx.tc(R.color.textDim)); textSize = 12f; maxLines = 1
        })
        fun iconButton(icon: Int, tint: Int, desc: String, onClick: () -> Unit) = ImageButton(ctx).apply {
            setImageResource(icon)
            setBackgroundResource(R.drawable.btn_icon_glass)
            imageTintList = android.content.res.ColorStateList.valueOf(ctx.tc(tint))
            contentDescription = desc
            layoutParams = LinearLayout.LayoutParams((38 * d).toInt(), (38 * d).toInt()).apply { marginStart = (6 * d).toInt() }
            stateListAnimator = android.animation.AnimatorInflater.loadStateListAnimator(ctx, R.animator.press_scale)
            setOnClickListener { onClick() }
        }
        row.addView(box); row.addView(texts)
        row.addView(iconButton(R.drawable.ic_share, R.color.accent, "Отправить") { ctx.shareUri(item.uri, item.mime) })
        row.addView(iconButton(R.drawable.ic_delete, R.color.danger, "Удалить") { confirmDelete(item) })
        row.setOnClickListener { open(item) }

        loadThumb(item, thumb)
        return row
    }

    /** First frame of the GIF / a frame of the clip, made off the UI thread. */
    private fun loadThumb(item: Made.Item, into: ImageView) {
        s.io.execute {
            val b: Bitmap? = runCatching {
                if (item.isGif) {
                    s.act.contentResolver.openInputStream(item.uri)?.use { BitmapFactory.decodeStream(it) }
                } else {
                    val r = MediaMetadataRetriever()
                    try { r.setDataSource(s.act, item.uri); r.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 240, 240) }
                    finally { r.release() }
                }
            }.getOrNull()
            s.ui.post {
                if (b != null) { into.setImageBitmap(b); into.alpha = 0f; into.anim().alpha(1f).setDuration(200).start() }
            }
        }
    }

    /** Open in the phone's gallery / player. */
    private fun open(item: Made.Item) {
        if (!Made.exists(s.act, item)) {
            s.act.toast("Файл больше не найден — убираю из списка")
            Made.remove(s.act, item, deleteFile = false); buildMade(); return
        }
        runCatching {
            s.act.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(item.uri, item.mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }.onFailure { s.act.toast("Нет приложения, чтобы открыть этот файл") }
    }

    private fun confirmDelete(item: Made.Item) {
        AlertDialog.Builder(s.act)
            .setMessage("Удалить «${item.name}»?")
            .setPositiveButton("Удалить файл") { _, _ -> Made.remove(s.act, item, deleteFile = true); buildMade() }
            .setNeutralButton("Только из списка") { _, _ -> Made.remove(s.act, item, deleteFile = false); buildMade() }
            .setNegativeButton("Отмена", null)
            .show()
    }
}
