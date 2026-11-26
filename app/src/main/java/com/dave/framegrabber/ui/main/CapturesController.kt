package com.dave.framegrabber.ui.main

import com.dave.framegrabber.ui.tc

import android.animation.AnimatorInflater
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.dave.framegrabber.R
import com.dave.framegrabber.batch.BatchState
import com.dave.framegrabber.core.TimeUtil
import com.dave.framegrabber.data.Capture
import com.dave.framegrabber.data.CaptureStore
import com.dave.framegrabber.data.Output
import com.dave.framegrabber.ui.anim
import com.dave.framegrabber.ui.bump
import com.dave.framegrabber.ui.dp
import com.dave.framegrabber.ui.floatLoop
import com.dave.framegrabber.ui.shareUri
import com.dave.framegrabber.ui.showAnimated
import com.dave.framegrabber.ui.showError
import com.dave.framegrabber.ui.toast
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Page «Кадры»: the grid of captured frames, the counter, and saving / sharing / ZIP / clearing.
 */
class CapturesController(private val s: MainScreen) {

    private val captures get() = CaptureStore.captures
    /** Frames that should play the "pop in" animation the first time they're shown. */
    private val pendingAnim = HashSet<Capture>()

    val list = s.pinned<ListView>(R.id.list)
    /** Title, counter, action buttons and the empty state above the grid. */
    private val header = s.act.layoutInflater.inflate(R.layout.frames_header, list, false)
    private val adapter = GridAdapter()
    private val txtCount = header.findViewById<TextView>(R.id.txtCount)
    private val capActions = header.findViewById<View>(R.id.capActions)
    private val emptyBox = header.findViewById<View>(R.id.emptyBox)
    private val btnTgPost = header.findViewById<View>(R.id.btnTgPost)

    /** New frames from single capture and from the background batch arrive here. */
    private val storeListener = object : CaptureStore.Listener {
        override fun onAdded(c: Capture) = added(c, scroll = !BatchState.running)
        override fun onReplaced() { pendingAnim.clear(); refresh(); updateUi(animate = false); s.viewer.updateChrome() }
    }

    init {
        list.addHeaderView(header, null, false)
        list.adapter = adapter
        list.itemsCanFocus = true
        list.selector = ColorDrawable(Color.TRANSPARENT)
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(v: AbsListView, state: Int) {
                // freeze the animated background while scrolling (smoother)
                s.act.bgView.scrolling = state != AbsListView.OnScrollListener.SCROLL_STATE_IDLE
            }
            override fun onScroll(v: AbsListView, first: Int, visible: Int, total: Int) {}
        })
        header.findViewById<View>(R.id.btnSaveAll).setOnClickListener { saveAll() }
        header.findViewById<View>(R.id.btnZip).setOnClickListener { makeZip() }
        header.findViewById<View>(R.id.btnClear).setOnClickListener { confirmClear() }
        btnTgPost.setOnClickListener { s.tgPick.start() }
        header.findViewById<View>(R.id.emptyIcon).floatLoop(6f, 1700)
        updateUi(animate = false)
    }

    fun onResume() {
        CaptureStore.listener = storeListener
        refresh(); updateUi(animate = false)
    }

    /** While away, the batch service shows a notification instead of updating this screen. */
    fun onPause() { CaptureStore.listener = null }

    fun refresh() = adapter.notifyDataSetChanged()

    /** UI side of a new frame (the frame is already in [CaptureStore]). */
    private fun added(c: Capture, scroll: Boolean) {
        pendingAnim.add(c)
        refresh()
        updateUi()
        s.viewer.updateChrome()
        s.project.dirty()
        if (scroll && list.visibility == View.VISIBLE) list.post { list.smoothScrollToPosition(adapter.count) }
    }

    fun remove(c: Capture) {
        captures.remove(c)
        s.tgPick.forget(c)
        pendingAnim.remove(c)
        c.file.delete()
        refresh()
        updateUi()
        s.project.dirty()
    }

    /** Counter, empty state, action buttons. */
    private fun updateUi(animate: Boolean = true) {
        val n = captures.size
        s.nav.setCount(n, animate)
        if (txtCount.text.toString() != n.toString()) {
            txtCount.text = n.toString()
            if (animate) txtCount.bump()
        }
        if (n == 0) {
            if (emptyBox.visibility != View.VISIBLE) { if (animate) emptyBox.showAnimated() else emptyBox.visibility = View.VISIBLE }
            capActions.visibility = View.GONE
            btnTgPost.visibility = View.GONE
        } else {
            btnTgPost.visibility = View.VISIBLE
            emptyBox.visibility = View.GONE
            if (capActions.visibility != View.VISIBLE) {
                if (animate) capActions.showAnimated() else capActions.visibility = View.VISIBLE
            }
        }
    }

    /** After the save folder changed, frames must be saved again into the new one. */
    fun forgetSavedCopies() = captures.forEach { it.galleryUri = null }

    // ================================================================ saving / sharing

    /** Saves to the chosen folder (General settings) or Pictures/FrameGrabber; runs off the UI thread. */
    private fun saveToGallery(c: Capture): Uri {
        c.galleryUri?.let { return it }
        val uri = Output.save(s.act, c.file, c.file.name, c.fmt.mime, Output.Kind.IMAGE)
        c.galleryUri = uri
        s.project.dirty()   // remember it's saved
        return uri
    }

    fun saveOne(c: Capture) = Thread {
        val r = runCatching { saveToGallery(c) }
        s.ui.post {
            r.onSuccess { s.act.toast("Сохранено: ${Output.describe(s.act)}"); s.effects.shutterFlash(soft = true) }
                .onFailure { s.act.showError("Не удалось сохранить", it) }
        }
    }.start()

    fun share(c: Capture) = Thread {
        val r = runCatching { saveToGallery(c) }
        s.ui.post { r.onSuccess { s.act.shareUri(it, c.fmt.mime) }.onFailure { s.act.showError("Не удалось поделиться", it) } }
    }.start()

    private fun saveAll() {
        if (captures.isEmpty()) return
        val snapshot = captures.toList()
        s.act.toast("Сохраняю ${snapshot.size} в галерею…")
        Thread {
            var ok = 0
            for (c in snapshot) if (runCatching { saveToGallery(c) }.isSuccess) ok++
            s.ui.post { s.act.toast("Сохранено $ok из ${snapshot.size}: ${Output.describe(s.act)}") }
        }.start()
    }

    /** All frames in one ZIP, written straight into Download/FrameGrabber. */
    private fun makeZip() {
        if (captures.isEmpty()) return
        val snapshot = captures.toList()
        val name = "${s.video.baseName}_frames_${System.currentTimeMillis() / 1000}.zip"
        s.act.toast("Собираю ZIP…")
        Thread {
            val res = runCatching {
                Output.write(s.act, name, "application/zip", Output.Kind.DOWNLOAD) { t ->
                    ZipOutputStream(t.stream().buffered()).use { zip ->
                        zip.setLevel(1) // images are already compressed
                        for (c in snapshot) {
                            zip.putNextEntry(ZipEntry(c.file.name))
                            c.file.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                }
            }
            s.ui.post {
                res.onSuccess { uri ->
                    AlertDialog.Builder(s.act)
                        .setTitle("ZIP готов")
                        .setMessage("Сохранён в Загрузки/FrameGrabber/$name\n(${snapshot.size} кадров)")
                        .setPositiveButton("Поделиться") { _, _ -> s.act.shareUri(uri, "application/zip") }
                        .setNegativeButton("OK", null)
                        .show()
                }.onFailure { s.act.showError("Ошибка ZIP", it) }
            }
        }.start()
    }

    private fun confirmClear() {
        if (captures.isEmpty()) return
        AlertDialog.Builder(s.act)
            .setMessage("Удалить все ${captures.size} снятых кадров из списка?\n(Уже сохранённые в галерею останутся.)")
            .setPositiveButton("Удалить") { _, _ ->
                s.viewer.close()
                captures.forEach { it.file.delete() }
                captures.clear()
                s.tgPick.cancel()
                s.project.dirty()
                pendingAnim.clear()
                refresh()
                updateUi()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ================================================================ grid

    /** ListView rows with 3 thumbnails each (rows are recycled, so hundreds of frames are fine). */
    private inner class GridAdapter : BaseAdapter() {
        private val cols = 3

        override fun getCount() = (captures.size + cols - 1) / cols
        override fun getItem(position: Int): Any = position
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = (convertView as? LinearLayout) ?: makeRow(parent)
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
                val badge = cell.getChildAt(2) as TextView
                if (s.tgPick.active) {
                    // picking frames for a Telegram post: numbered in tap order
                    val n = s.tgPick.number(c)
                    cell.foreground = if (n > 0) s.act.getDrawable(R.drawable.fg_selected) else null
                    badge.visibility = if (n > 0) View.VISIBLE else View.GONE
                    badge.text = n.toString()
                    (cell.getChildAt(0) as ImageView).alpha = if (n > 0) 1f else 0.6f
                    cell.setOnClickListener { s.tgPick.toggle(c) }
                } else {
                    badge.visibility = View.GONE
                    (cell.getChildAt(0) as ImageView).alpha = 1f
                    cell.foreground = if (c === s.viewer.selected) s.act.getDrawable(R.drawable.fg_selected) else null
                    cell.setOnClickListener { if (s.viewer.selected === c) s.viewer.close() else s.viewer.open(c) }
                }
                if (pendingAnim.remove(c)) {
                    cell.alpha = 0f
                    cell.scaleX = 0.5f; cell.scaleY = 0.5f
                    cell.anim().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(60L * i).setDuration(380)
                        .setInterpolator(OvershootInterpolator(1.8f)).start()
                } else {
                    cell.anim()
                    cell.alpha = 1f; cell.scaleX = 1f; cell.scaleY = 1f
                }
            }
            return row
        }

        private fun makeRow(parent: ViewGroup): LinearLayout {
            val ctx = parent.context
            val d = parent.dp
            val pad = (4 * d).toInt()
            return LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding((10 * d).toInt(), pad, (10 * d).toInt(), pad)
                repeat(cols) {
                    val cell = FrameLayout(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams(0, (104 * d).toInt(), 1f).apply { setMargins(pad, 0, pad, 0) }
                        setBackgroundResource(R.drawable.bg_thumb)
                        clipToOutline = true
                        stateListAnimator = AnimatorInflater.loadStateListAnimator(ctx, R.animator.press_scale)
                    }
                    cell.addView(ImageView(ctx).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        layoutParams = FrameLayout.LayoutParams(-1, -1)
                    })
                    cell.addView(TextView(ctx).apply {
                        setTextColor(ctx.tc(R.color.text))
                        setBackgroundResource(R.drawable.bg_chip)
                        textSize = 10f
                        typeface = Typeface.MONOSPACE
                        setPadding((6 * d).toInt(), (1 * d).toInt(), (6 * d).toInt(), (1 * d).toInt())
                        layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END)
                            .apply { setMargins(0, 0, (5 * d).toInt(), (5 * d).toInt()) }
                    })
                    // number in a Telegram post (1 = cover)
                    cell.addView(TextView(ctx).apply {
                        setTextColor(ctx.tc(R.color.text))
                        setBackgroundResource(R.drawable.bg_count)
                        textSize = 13f
                        typeface = Typeface.DEFAULT_BOLD
                        gravity = Gravity.CENTER
                        layoutParams = FrameLayout.LayoutParams((26 * d).toInt(), (26 * d).toInt(), Gravity.TOP or Gravity.START)
                            .apply { setMargins((5 * d).toInt(), (5 * d).toInt(), 0, 0) }
                        visibility = View.GONE
                    })
                    addView(cell)
                }
            }
        }
    }
}
