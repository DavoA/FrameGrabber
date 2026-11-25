package com.dave.framegrabber.ui.main

import android.view.View
import android.widget.Button
import android.widget.TextView
import com.dave.framegrabber.R
import com.dave.framegrabber.data.Capture
import com.dave.framegrabber.data.CaptureStore
import com.dave.framegrabber.telegram.PostCaption
import com.dave.framegrabber.ui.bump
import com.dave.framegrabber.ui.hideAnimated
import com.dave.framegrabber.ui.showAnimated
import com.dave.framegrabber.ui.telegram.PostActivity
import com.dave.framegrabber.ui.toast

/**
 * «Пост в Telegram» on the «Кадры» page: tap 6 frames (numbered in tap order = order in the album),
 * then «Далее» opens the post editor ([PostActivity]).
 */
class TelegramPick(private val s: MainScreen) {

    var active = false
        private set
    private val picked = mutableListOf<Capture>()

    private val bar = s.h<View>(R.id.tgPickBar)
    private val txt = s.h<TextView>(R.id.txtTgPick)
    private val btnNext = s.h<Button>(R.id.btnTgPickNext)

    init {
        s.h<View>(R.id.btnTgPickCancel).setOnClickListener { cancel() }
        btnNext.setOnClickListener { next() }
    }

    fun start() {
        val n = CaptureStore.captures.size
        if (n < PostCaption.PHOTOS) {
            s.act.toast("Для поста нужно ${PostCaption.PHOTOS} кадров, снято $n")
            return
        }
        s.viewer.close()
        active = true
        picked.clear()
        bar.showAnimated()
        update()
        s.grid.refresh()
    }

    fun cancel() {
        if (!active) return
        active = false
        picked.clear()
        bar.hideAnimated(duration = 200)
        s.grid.refresh()
    }

    /** 1…6 if picked, else 0. */
    fun number(c: Capture) = picked.indexOf(c) + 1

    fun toggle(c: Capture) {
        if (!picked.remove(c)) {
            if (picked.size >= PostCaption.PHOTOS) { s.act.toast("Уже выбрано ${PostCaption.PHOTOS}. Сними выбор с какого-нибудь кадра."); txt.bump(); return }
            picked += c
        }
        update()
        s.grid.refresh()
    }

    /** A picked frame was deleted. */
    fun forget(c: Capture) { if (picked.remove(c)) update() }

    private fun update() {
        val k = picked.size
        txt.text = if (k < PostCaption.PHOTOS) "Выбрано $k из ${PostCaption.PHOTOS}\n1-й — обложка"
                   else "Выбрано ${PostCaption.PHOTOS} ✓"
        btnNext.isEnabled = k == PostCaption.PHOTOS
        btnNext.alpha = if (k == PostCaption.PHOTOS) 1f else 0.45f
        if (k == PostCaption.PHOTOS) btnNext.bump()
    }

    private fun next() {
        if (picked.size != PostCaption.PHOTOS) return
        val uri = s.video.uri
        val size = uri?.let { u ->
            if (u.scheme == "file") java.io.File(u.path!!).length()
            else runCatching {
                s.act.contentResolver.query(u, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
            }.getOrNull() ?: 0L
        } ?: 0L
        val key = com.dave.framegrabber.telegram.PostLog.videoKey(size, s.video.engine?.durationUs ?: 0)
        s.act.openPostEditor(PostActivity.intent(s.act, picked.map { it.file.path }, picked.map { it.timeUs }, s.video.baseName, key))
    }

    /** Posted: leave picking. */
    fun onPosted() = cancel()
}
