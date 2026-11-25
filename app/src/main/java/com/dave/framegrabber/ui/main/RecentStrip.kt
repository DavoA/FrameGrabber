package com.dave.framegrabber.ui.main

import android.animation.AnimatorInflater
import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.TextUtils
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.dave.framegrabber.R
import com.dave.framegrabber.core.FrameEngine
import com.dave.framegrabber.core.TimeUtil
import com.dave.framegrabber.data.Recent
import com.dave.framegrabber.ui.anim
import com.dave.framegrabber.ui.dp
import com.dave.framegrabber.ui.toast
import java.io.File

/** «Недавние видео» on the start screen (shown while no video is open). */
class RecentStrip(private val s: MainScreen) {

    private val card = s.h<View>(R.id.cardRecent)
    private val row = s.h<LinearLayout>(R.id.recentRow)

    /** Save the opened video to the list (with a thumbnail). */
    fun remember(uri: Uri, e: FrameEngine) {
        val isPath = uri.scheme == "file"
        val ref = if (isPath) uri.path!! else uri.toString()
        if (!isPath) runCatching { s.act.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val info = "${e.width}×${e.height} · ${TimeUtil.format(e.durationUs).substringBefore('.')}"
        val name = s.video.baseName
        s.exec.execute {
            val thumb = runCatching { e.scaledFrame(e.lastUs / 3, 320, 320, exact = false) }.getOrNull()
            Recent.add(s.act, ref, isPath, name, info, thumb)
        }
    }

    fun hide() {
        if (card.visibility != View.VISIBLE) return
        card.anim().alpha(0f).translationY(-20 * card.dp).setDuration(220)
            .withEndAction { card.visibility = View.GONE; card.alpha = 1f; card.translationY = 0f }.start()
    }

    fun build() {
        val items = Recent.list(s.act)
        row.removeAllViews()
        if (items.isEmpty() || s.video.engine != null) { card.visibility = View.GONE; return }
        card.visibility = View.VISIBLE
        items.forEachIndexed { i, item ->
            val v = makeCard(item)
            v.alpha = 0f; v.translationX = 40 * v.dp
            v.anim().alpha(1f).translationX(0f).setStartDelay(250L + 60L * i).setDuration(320).start()
            row.addView(v)
        }
    }

    private fun makeCard(item: Recent.Item): View {
        val ctx = s.act
        val d = row.dp
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams((132 * d).toInt(), -2).apply { marginEnd = (10 * d).toInt() }
            stateListAnimator = AnimatorInflater.loadStateListAnimator(ctx, R.animator.press_scale)
        }
        card.addView(ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(-1, (76 * d).toInt())
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundResource(R.drawable.bg_thumb)
            clipToOutline = true
            Recent.thumbFile(ctx, item)?.takeIf { it.exists() }?.let { setImageBitmap(BitmapFactory.decodeFile(it.path)) }
        })
        card.addView(TextView(ctx).apply {
            text = item.name; setTextColor(ctx.getColor(R.color.text)); textSize = 12f
            maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
            setPadding(2, (6 * d).toInt(), 2, 0)
        })
        card.addView(TextView(ctx).apply {
            text = item.info; setTextColor(ctx.getColor(R.color.textDim)); textSize = 10f; maxLines = 1
            setPadding(2, 0, 2, 0)
        })
        card.setOnClickListener {
            if (item.isPath && !File(item.ref).exists()) {
                ctx.toast("Файл больше не найден"); Recent.remove(ctx, item.ref); build()
            } else s.video.load(if (item.isPath) Uri.fromFile(File(item.ref)) else Uri.parse(item.ref))
        }
        card.setOnLongClickListener {
            AlertDialog.Builder(ctx).setMessage("Убрать «${item.name}» из недавних?")
                .setPositiveButton("Убрать") { _, _ -> Recent.remove(ctx, item.ref); build() }
                .setNegativeButton("Отмена", null).show()
            true
        }
        return card
    }
}
