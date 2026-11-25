package com.dave.framegrabber.ui.main

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
import android.widget.TextView
import com.dave.framegrabber.R
import com.dave.framegrabber.core.TimeUtil
import com.dave.framegrabber.data.Made
import com.dave.framegrabber.data.Naming
import com.dave.framegrabber.data.Output
import com.dave.framegrabber.export.Exporters
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

    companion object {
        private const val GIF_MAX_US = 20_000_000L
    }

    private val radioFps = s.h<RadioGroup>(R.id.radioGifFps)
    private val radioWidth = s.h<RadioGroup>(R.id.radioGifW)
    private val job = ExportJob(s, s.h(R.id.progressClips), s.h(R.id.txtClips))
    private val madeList = s.h<LinearLayout>(R.id.madeList)
    private val madeEmpty = s.h<View>(R.id.txtMadeEmpty)
    private val madeCount = s.h<TextView>(R.id.txtMadeCount)

    init {
        val p = s.prefs
        radioFps.check(when (p.gifFps) { 5 -> R.id.fps5; 15 -> R.id.fps15; else -> R.id.fps10 })
        radioWidth.check(when (p.gifWidth) { 320 -> R.id.gw320; 720 -> R.id.gw720; else -> R.id.gw480 })
        radioFps.setOnCheckedChangeListener { _, id -> p.gifFps = when (id) { R.id.fps5 -> 5; R.id.fps15 -> 15; else -> 10 } }
        radioWidth.setOnCheckedChangeListener { _, id -> p.gifWidth = when (id) { R.id.gw320 -> 320; R.id.gw720 -> 720; else -> 480 } }
        s.h<View>(R.id.btnGif).setOnClickListener { gif() }
        s.h<View>(R.id.btnClip).setOnClickListener { clip() }
        buildMade()
    }

    // ================================================================ making

    private fun gif() {
        val e = s.video.requireEngine() ?: return
        val uri = s.video.uri ?: return
        val (from, to) = s.rangeClips.require(e) ?: return
        if (to - from > GIF_MAX_US) {
            s.act.toast("GIF до 20 секунд — укажи отрезок короче (сейчас ${TimeUtil.format(to - from)})")
            s.rangeClips.card.bump(); return
        }
        val fps = s.prefs.gifFps
        val width = s.prefs.gifWidth
        val name = Naming.stem(s.prefs.namePattern, s.video.baseName, from, 1) + ".gif"
        job.launch("GIF", "Делаю GIF…", "image/gif", onDone = { made(it, "image/gif", name, to - from) }) {
            Output.write(s.act, name, "image/gif", Output.Kind.IMAGE) { t ->
                t.stream().buffered().use { os ->
                    Exporters.gif(s.act, uri, from, to, fps, width, os) { job.report(it, "GIF"); true }
                }
            }
        }
    }

    private fun clip() {
        val e = s.video.requireEngine() ?: return
        val uri = s.video.uri ?: return
        val (from, to) = s.rangeClips.require(e) ?: return
        val name = Naming.stem(s.prefs.namePattern, s.video.baseName, from, 1) + "_clip.mp4"
        job.launch("Клип", "Вырезаю клип…", "video/mp4", onDone = { made(it, "video/mp4", name, to - from) }) {
            Output.write(s.act, name, "video/mp4", Output.Kind.VIDEO) { t ->
                val pfd = t.fd()
                if (pfd != null) {
                    // MediaMuxer writes straight into the destination file
                    pfd.use { fd ->
                        Exporters.clip(s.act, uri, from, to,
                            { MediaMuxer(fd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4) }
                        ) { job.report(it, "Клип"); true }
                    }
                } else {
                    // folder without read/write access: build in the app's permanent storage, then copy
                    val tmp = File(File(s.act.filesDir, "export").apply { mkdirs() }, "clip.mp4")
                    try {
                        Exporters.clip(s.act, uri, from, to, tmp) { job.report(it, "Клип"); true }
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
            setTextColor(ctx.getColor(R.color.accent))
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
            text = item.name; setTextColor(ctx.getColor(R.color.text)); textSize = 14f
            maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
        })
        texts.addView(TextView(ctx).apply {
            val ago = DateUtils.getRelativeTimeSpanString(item.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            text = "${TimeUtil.format(item.lengthUs).substringBefore('.')} · $ago"
            setTextColor(ctx.getColor(R.color.textDim)); textSize = 12f; maxLines = 1
        })
        fun iconButton(icon: Int, tint: Int, desc: String, onClick: () -> Unit) = ImageButton(ctx).apply {
            setImageResource(icon)
            setBackgroundResource(R.drawable.btn_icon_glass)
            imageTintList = android.content.res.ColorStateList.valueOf(ctx.getColor(tint))
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
