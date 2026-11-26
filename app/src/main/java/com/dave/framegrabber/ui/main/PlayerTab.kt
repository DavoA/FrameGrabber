package com.dave.framegrabber.ui.main

import android.app.AlertDialog
import android.graphics.Typeface
import android.net.Uri
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.dave.framegrabber.R
import com.dave.framegrabber.core.TimeUtil
import com.dave.framegrabber.player.PlayerStore
import com.dave.framegrabber.player.RandomMoment
import com.dave.framegrabber.ui.player.PlayerActivity
import com.dave.framegrabber.ui.toast

/** Page «Плеер»: 🎲 random hot moment, open a video in the player, continue watching. */
class PlayerTab(private val s: MainScreen) {

    private val btnRandom = s.h<Button>(R.id.btnRandomMoment)
    private val status = s.h<TextView>(R.id.txtRandomStatus)
    private val btnCurrent = s.h<View>(R.id.btnPlayCurrent)
    private val card = s.h<View>(R.id.cardContinue)
    private val list = s.h<LinearLayout>(R.id.continueList)
    private var busy = false

    init {
        btnRandom.setOnClickListener { random() }
        s.h<View>(R.id.btnPlayPick).setOnClickListener { s.act.openBrowserForPlayer() }
        btnCurrent.setOnClickListener { s.video.uri?.let { play(it, -1, s.video.baseName) } }
    }

    fun play(uri: Uri, startUs: Long = -1, name: String? = null) {
        s.video.pausePlayback()
        s.act.startActivity(PlayerActivity.intent(s.act, uri, startUs, name))
    }

    fun refresh() {
        btnCurrent.visibility = if (s.video.uri != null) View.VISIBLE else View.GONE
        val ctx = s.act.applicationContext
        Thread {
            val items = PlayerStore(ctx).all().filter { it.positionUs > 0 && it.durationUs > 0 }.take(12)
            s.ui.post { show(items) }
        }.start()
    }

    private fun show(items: List<PlayerStore.Video>) {
        list.removeAllViews()
        card.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        val d = s.act.resources.displayMetrics.density
        items.forEach { v ->
            val row = LinearLayout(s.act).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_row)
                setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (6 * d).toInt() }
            }
            row.addView(TextView(s.act).apply {
                text = v.name; textSize = 14f; setTextColor(s.act.getColor(R.color.text))
                maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            row.addView(TextView(s.act).apply {
                text = buildString {
                    append("${TimeUtil.format(v.positionUs)} из ${TimeUtil.format(v.durationUs)}")
                    if (v.moments.isNotEmpty()) append("  · ★ ${v.moments.size}")
                    if (v.vr.isNotEmpty() && v.vr != "flat") append("  · VR")
                }
                textSize = 12f; setTextColor(s.act.getColor(R.color.textDim))
            })
            row.addView(ProgressBar(s.act, null, 0, android.R.style.Widget_Material_ProgressBar_Horizontal).apply {
                max = 1000
                progress = (v.positionUs * 1000 / v.durationUs).toInt()
                progressTintList = android.content.res.ColorStateList.valueOf(s.act.getColor(R.color.accent))
                layoutParams = LinearLayout.LayoutParams(-1, (6 * d).toInt()).apply { topMargin = (6 * d).toInt() }
            })
            row.setOnClickListener { play(Uri.parse(v.uri), -1, v.name) }
            row.setOnLongClickListener {
                AlertDialog.Builder(s.act).setMessage("Убрать «${v.name}» из списка? (Моменты и тепловая карта тоже забудутся)")
                    .setPositiveButton("Убрать") { _, _ -> PlayerStore(s.act).delete(v.id); refresh() }
                    .setNegativeButton("Отмена", null).show()
                true
            }
            list.addView(row)
        }
    }

    private fun random() {
        if (busy) return
        busy = true
        btnRandom.isEnabled = false; btnRandom.alpha = 0.6f
        status.visibility = View.VISIBLE
        status.text = "Выбираю видео…"
        val ctx = s.act.applicationContext
        Thread {
            val pick = runCatching { RandomMoment.pick(ctx) { msg -> s.ui.post { status.text = msg }; true } }
            s.ui.post {
                busy = false
                btnRandom.isEnabled = true; btnRandom.alpha = 1f
                status.visibility = View.GONE
                pick.onSuccess { p ->
                    if (p == null) s.act.toast("На телефоне не нашлось видео длиннее минуты")
                    else play(p.uri, p.startUs, p.name)
                }.onFailure { s.act.toast("Не получилось: ${it.message}") }
            }
        }.start()
    }
}
