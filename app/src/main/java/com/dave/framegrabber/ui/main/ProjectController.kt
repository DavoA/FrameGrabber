package com.dave.framegrabber.ui.main

import android.animation.AnimatorInflater
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.TextUtils
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.dave.framegrabber.R
import com.dave.framegrabber.core.FrameEngine
import com.dave.framegrabber.data.CaptureStore
import com.dave.framegrabber.data.Projects
import com.dave.framegrabber.ui.dp
import com.dave.framegrabber.ui.toast
import java.io.File

/**
 * Project history: each video keeps its frames, marks, ranges and position.
 * Opening a video restores its project; everything is saved as you work (and when leaving the app).
 * The 🕘 button in the top bar lists all projects.
 */
class ProjectController(private val s: MainScreen) {

    /** This screen has shown the open project (so the UI holds its state and may save it). */
    private var shownHere = false
    /** Frames of the project still being read from disk (kept in saves until they're in the grid). */
    private var pendingFrames: List<Projects.Frame>? = null
    private val saveLater = Runnable { saveNow() }
    private var dialog: AlertDialog? = null

    init {
        s.h<View>(R.id.btnHistory).setOnClickListener { showHistory() }
    }

    val current: Projects.Project? get() = CaptureStore.project

    // ================================================================ open / save

    /**
     * A video was opened (the previous project was saved by [saveNow] in
     * [VideoController] before the screen switched): bring this one back.
     */
    fun onVideoLoaded(uri: Uri, e: FrameEngine) {
        val id = Projects.idOf(Projects.refOf(uri).first)
        val cur = CaptureStore.project
        if (cur != null && cur.id == id && !shownHere) {
            // the screen was recreated while the app kept running: frames are still in memory
            shownHere = true
            restoreUi(cur)
            return
        }
        if (cur != null && cur.id == id) { restoreUi(cur); return }   // same video opened again
        shownHere = true

        val p = Projects.open(s.act, uri, s.video.baseName)   // one small JSON file
        p.updated = System.currentTimeMillis()
        val frames = p.frames.toList()
        pendingFrames = frames
        CaptureStore.replace(p, emptyList())
        restoreUi(p)
        writeCover(p, e)
        if (frames.isNotEmpty() || p.marks.isNotEmpty()) {
            s.act.toast("Проект продолжен: кадров ${frames.size}, меток ${p.marks.size}")
        }
        // thumbnails of saved frames are decoded in the background
        if (frames.isNotEmpty()) s.io.execute {
            val caps = frames.mapNotNull { Projects.frameToCapture(s.act, p.id, it) }
            s.ui.post {
                if (CaptureStore.project !== p) return@post
                CaptureStore.captures.addAll(0, caps)
                pendingFrames = null
                CaptureStore.listener?.onReplaced() ?: s.grid.refresh()
                dirty()
            }
        } else pendingFrames = null
        saveNow()
    }

    private fun restoreUi(p: Projects.Project) {
        s.marks.set(p.marks)
        s.rangeClips.restore(p.clipsFrom, p.clipsTo)
        s.rangeBatch.restore(p.batchFrom, p.batchTo)
        if (p.positionUs > 0) s.video.seekTo(p.positionUs)
        s.grid.refresh()
    }

    private fun writeCover(p: Projects.Project, e: FrameEngine) {
        val f = Projects.cover(s.act, p.id)
        s.exec.execute {
            if (s.video.engine !== e) return@execute
            val b = runCatching { e.scaledFrame(e.lastUs / 3, 320, 320, exact = false) }.getOrNull() ?: return@execute
            CaptureStore.disk.execute {
                runCatching { f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 85, it) } }
            }
        }
    }

    /** Something changed: save in a moment (several quick changes = one save). Any thread. */
    fun dirty() {
        s.ui.post {
            s.ui.removeCallbacks(saveLater)
            s.ui.postDelayed(saveLater, 1200)
        }
    }

    /** Main thread. */
    fun saveNow() {
        s.ui.removeCallbacks(saveLater)
        val p = CaptureStore.project ?: return
        if (!shownHere) return
        p.marks.clear(); p.marks += s.marks.marks
        p.clipsFrom = s.rangeClips.fromText; p.clipsTo = s.rangeClips.toText
        p.batchFrom = s.rangeBatch.fromText; p.batchTo = s.rangeBatch.toText
        if (s.video.engine != null) p.positionUs = s.video.currentUs
        p.frames.clear()
        pendingFrames?.let { p.frames += it }
        CaptureStore.captures.forEach { p.frames += Projects.captureToFrame(it) }
        p.updated = System.currentTimeMillis()
        val json = Projects.json(p)
        val ctx = s.act.applicationContext
        CaptureStore.disk.execute { runCatching { Projects.write(ctx, p.id, json) } }
    }

    // ================================================================ history list

    private fun showHistory() {
        saveNow()
        val ctx = s.act
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = (12 * dp).toInt()
            setPadding(p, p / 2, p, p)
        }
        val loading = TextView(ctx).apply {
            text = "Загружаю…"; setTextColor(ctx.getColor(R.color.textDim)); gravity = Gravity.CENTER
            setPadding(0, (24 * dp).toInt(), 0, (24 * dp).toInt())
        }
        box.addView(loading)
        val title = TextView(ctx).apply {
            text = "История проектов"; textSize = 20f; setTextColor(ctx.getColor(R.color.text))
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setPadding((24 * dp).toInt(), (20 * dp).toInt(), (24 * dp).toInt(), 0)
        }
        val sub = TextView(ctx).apply {
            textSize = 12f; setTextColor(ctx.getColor(R.color.textDim))
            setPadding((24 * dp).toInt(), (2 * dp).toInt(), (24 * dp).toInt(), (6 * dp).toInt())
        }
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; addView(title); addView(sub) }
        dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(head)
            .setView(ScrollView(ctx).apply { addView(box) })
            .setNegativeButton("Закрыть", null)
            .show()
        fill(box, sub)
    }

    private fun fill(box: LinearLayout, sub: TextView) {
        val ctx = s.act
        CaptureStore.disk.execute {   // after pending saves
            // only videos that are still on the phone (a deleted video can't be opened anymore)
            val items = Projects.list(ctx).filter { com.dave.framegrabber.data.VideoFile.exists(ctx, it.ref, it.isPath) }
            val covers = items.associate { it.id to loadCover(it) }
            s.ui.post {
                box.removeAllViews()
                val total = items.sumOf { it.bytes }
                sub.text = "Видео: ${items.size} · занято ${Formatter.formatShortFileSize(ctx, total)}"
                if (items.isEmpty()) {
                    box.addView(TextView(ctx).apply {
                        text = "Пока пусто. Открой видео — его кадры, метки и отрезки будут сохраняться здесь."
                        setTextColor(ctx.getColor(R.color.textDim)); gravity = Gravity.CENTER
                        setPadding((12 * dp).toInt(), (24 * dp).toInt(), (12 * dp).toInt(), (24 * dp).toInt())
                    })
                }
                items.forEach { box.addView(row(it, covers[it.id], box, sub)) }
            }
        }
    }

    private fun loadCover(it: Projects.Summary): Bitmap? {
        val f = Projects.cover(s.act, it.id).takeIf { f -> f.exists() }
            ?: Projects.load(s.act, it.id)?.frames?.firstOrNull()?.let { fr -> File(Projects.dir(s.act, it.id), fr.file) }
            ?: return null
        return runCatching {
            val o = BitmapFactory.Options().apply { inSampleSize = if (f.name == "cover.jpg") 1 else 4 }
            BitmapFactory.decodeFile(f.path, o)
        }.getOrNull()
    }

    private val dp get() = s.act.resources.displayMetrics.density

    private fun row(it: Projects.Summary, cover: Bitmap?, box: LinearLayout, sub: TextView): View {
        val ctx = s.act
        val d = dp
        val isOpen = it.id == CaptureStore.project?.id
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_row)
            if (isOpen) foreground = ctx.getDrawable(R.drawable.fg_selected)
            val p = (8 * d).toInt()
            setPadding(p, p, p / 2, p)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (8 * d).toInt() }
            stateListAnimator = AnimatorInflater.loadStateListAnimator(ctx, R.animator.press_scale)
        }
        row.addView(ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams((84 * d).toInt(), (52 * d).toInt())
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundResource(R.drawable.bg_thumb)
            clipToOutline = true
            if (cover != null) setImageBitmap(cover)
            else { setImageResource(R.drawable.ic_movie); scaleType = ImageView.ScaleType.CENTER }
        })
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = (12 * d).toInt() }
        }
        texts.addView(TextView(ctx).apply {
            text = it.name; textSize = 14f; setTextColor(ctx.getColor(R.color.text))
            maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
        })
        texts.addView(TextView(ctx).apply {
            text = buildString {
                append("Кадров: ${it.frames}")
                if (it.marks > 0) append(" · меток: ${it.marks}")
            }
            textSize = 12f; setTextColor(ctx.getColor(R.color.accent)); maxLines = 1
        })
        texts.addView(TextView(ctx).apply {
            val time = if (isOpen) "открыт сейчас"
                       else DateUtils.getRelativeTimeSpanString(it.updated, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            text = "$time · ${Formatter.formatShortFileSize(ctx, it.bytes)}"
            textSize = 11f; setTextColor(ctx.getColor(R.color.textDim)); maxLines = 1
        })
        row.addView(texts)
        if (!isOpen) {
            row.addView(ImageButton(ctx).apply {
                setImageResource(R.drawable.ic_delete)
                imageTintList = android.content.res.ColorStateList.valueOf(ctx.getColor(R.color.textDim))
                background = null
                layoutParams = LinearLayout.LayoutParams((44 * d).toInt(), (44 * d).toInt())
                contentDescription = "Удалить проект"
                setOnClickListener { _ -> confirmDelete(it, box, sub) }
            })
            row.setOnClickListener { _ -> openProject(it) }
        } else row.setOnClickListener { dialog?.dismiss() }
        return row
    }

    private fun openProject(it: Projects.Summary) {
        if (it.isPath && !File(it.ref).exists()) {
            AlertDialog.Builder(s.act)
                .setMessage("Видео «${it.name}» больше не найдено (удалено или перемещено).\n\nКадров в проекте: ${it.frames}. Удалить проект?")
                .setPositiveButton("Удалить") { _, _ -> delete(it) }
                .setNegativeButton("Оставить", null)
                .show()
            return
        }
        dialog?.dismiss()
        s.video.load(it.uri)
        s.nav.show(NavController.Page.CAPTURE)
    }

    private fun confirmDelete(it: Projects.Summary, box: LinearLayout, sub: TextView) {
        AlertDialog.Builder(s.act)
            .setMessage("Удалить проект «${it.name}»?\nКадров: ${it.frames}, меток: ${it.marks}.\n(То, что уже сохранено в галерею, останется.)")
            .setPositiveButton("Удалить") { _, _ -> delete(it); fill(box, sub) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun delete(it: Projects.Summary) {
        val ctx = s.act.applicationContext
        CaptureStore.disk.execute { Projects.delete(ctx, it.id) }
    }
}
