package com.dave.framegrabber.ui.browser

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Typeface
import android.media.ThumbnailUtils
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.text.TextUtils
import android.text.format.Formatter
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.dave.framegrabber.R
import com.dave.framegrabber.ui.dp
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

/**
 * Rows of the file browser: folders, videos (with thumbnail, duration, size, date) and section titles.
 * Thumbnails are made on low-priority threads, cached, and not made during a fling.
 */
class BrowserAdapter(
    private val act: Activity, private val entries: List<Entry>,
    /** Multi-select: keys ([Entry.Video.key]) of the chosen videos; null = single pick. */
    private val selected: Set<String>? = null,
) : BaseAdapter() {

    /** True while the list is flinging: thumbnails wait until it stops. */
    var flinging = false

    private val ui = Handler(Looper.getMainLooper())
    private val thumbs = Executors.newFixedThreadPool(2) { r ->
        Thread { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run() }
    }
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val failed = HashSet<String>()
    private val dateFmt: DateFormat = DateFormat.getDateInstance(DateFormat.SHORT)

    private class Holder(
        val row: View, val thumb: ImageView, val icon: ImageView, val badge: TextView,
        val name: TextView, val sub: TextView, val chevron: ImageView
    ) { var key: String? = null }

    fun shutdown() = thumbs.shutdownNow()

    override fun getCount() = entries.size
    override fun getItem(position: Int): Any = entries[position]
    override fun getItemId(position: Int) = position.toLong()
    override fun getViewTypeCount() = 2
    override fun getItemViewType(position: Int) = if (entries[position] is Entry.Header) 1 else 0
    override fun isEnabled(position: Int) = entries[position] !is Entry.Header

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val e = entries[position]
        if (e is Entry.Header) {
            val d = parent.dp
            val tv = (convertView as? TextView) ?: TextView(act).apply {
                setTextColor(act.getColor(R.color.accent))
                textSize = 12f
                letterSpacing = 0.08f
                setPadding((8 * d).toInt(), (14 * d).toInt(), 0, (6 * d).toInt())
            }
            tv.text = e.title.uppercase()
            return tv
        }
        val v = convertView ?: makeRow(parent)
        val h = v.tag as Holder
        when (e) {
            is Entry.Dir -> {
                h.key = null
                h.thumb.setImageDrawable(null)
                h.icon.setImageResource(R.drawable.ic_folder)
                h.chevron.setImageResource(R.drawable.ic_chev_right)
                h.chevron.imageTintList = ColorStateList.valueOf(act.getColor(R.color.textDim))
                h.row.foreground = null
                h.badge.visibility = View.GONE
                h.chevron.visibility = View.VISIBLE
                h.name.text = e.file.name
                h.sub.text = "Папка"
            }
            is Entry.Video -> {
                h.icon.setImageResource(R.drawable.ic_movie)
                val on = selected?.contains(e.key) == true
                h.chevron.setImageResource(R.drawable.ic_check)
                h.chevron.imageTintList = ColorStateList.valueOf(act.getColor(R.color.accent))
                h.chevron.visibility = if (on) View.VISIBLE else View.GONE
                h.row.foreground = if (on) act.getDrawable(R.drawable.fg_selected) else null
                h.name.text = e.name
                h.sub.text = listOfNotNull(Formatter.formatShortFileSize(act, e.size), dateFmt.format(Date(e.dateMs)), e.sub)
                    .joinToString(" · ")
                if (e.durationMs > 0) { h.badge.visibility = View.VISIBLE; h.badge.text = fmtDuration(e.durationMs) }
                else h.badge.visibility = View.GONE
                bindThumb(h, e)
            }
            is Entry.Header -> {}
        }
        return v
    }

    private fun fmtDuration(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    private fun bindThumb(h: Holder, e: Entry.Video) {
        val key = e.key
        h.key = key
        val cached = cache.get(key)
        if (cached != null) { h.thumb.setImageBitmap(cached); h.thumb.alpha = 1f; return }
        h.thumb.setImageDrawable(null)
        if (key in failed || flinging) return // during a fling: load once it stops
        thumbs.execute {
            if (h.key != key) return@execute // row was reused for another file meanwhile
            val b = runCatching {
                val size = Size(256, 192)
                if (e.file != null) ThumbnailUtils.createVideoThumbnail(e.file, size, null)
                else act.contentResolver.loadThumbnail(e.uri!!, size, null)
            }.getOrNull()
            ui.post {
                if (b == null) { failed += key; return@post }
                cache.put(key, b)
                if (h.key == key) {
                    h.thumb.setImageBitmap(b)
                    h.thumb.alpha = 0f
                    h.thumb.animate().alpha(1f).setDuration(220).start()
                }
            }
        }
    }

    private fun makeRow(parent: ViewGroup): View {
        val ctx = act
        val d = parent.dp
        val outer = FrameLayout(ctx).apply { setPadding(0, (3 * d).toInt(), 0, (3 * d).toInt()) }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_row)
            val p = (8 * d).toInt()
            setPadding(p, p, p * 2, p)
        }
        val box = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams((76 * d).toInt(), (54 * d).toInt())
            setBackgroundResource(R.drawable.bg_thumb)
            clipToOutline = true
        }
        val icon = ImageView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams((28 * d).toInt(), (28 * d).toInt(), Gravity.CENTER)
            imageTintList = ColorStateList.valueOf(ctx.getColor(R.color.accent))
        }
        val thumb = ImageView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        val badge = TextView(ctx).apply {
            setBackgroundResource(R.drawable.bg_chip)
            setTextColor(ctx.getColor(R.color.text))
            textSize = 9f
            typeface = Typeface.MONOSPACE
            setPadding((4 * d).toInt(), 0, (4 * d).toInt(), 0)
            layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
                setMargins(0, 0, (3 * d).toInt(), (3 * d).toInt())
            }
        }
        box.addView(icon); box.addView(thumb); box.addView(badge)
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = (12 * d).toInt() }
        }
        val name = TextView(ctx).apply {
            setTextColor(ctx.getColor(R.color.text)); textSize = 15f
            maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        val sub = TextView(ctx).apply {
            setTextColor(ctx.getColor(R.color.textDim)); textSize = 12f
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        texts.addView(name); texts.addView(sub)
        val chevron = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_chev_right)
            imageTintList = ColorStateList.valueOf(ctx.getColor(R.color.textDim))
        }
        row.addView(box); row.addView(texts); row.addView(chevron)
        outer.addView(row)
        outer.tag = Holder(row, thumb, icon, badge, name, sub, chevron)
        return outer
    }
}
