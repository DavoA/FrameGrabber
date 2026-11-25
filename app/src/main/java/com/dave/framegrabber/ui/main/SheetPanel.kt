package com.dave.framegrabber.ui.main

import android.graphics.Bitmap
import android.view.View
import com.dave.framegrabber.R
import com.dave.framegrabber.core.TimeUtil
import com.dave.framegrabber.data.CaptureStore
import com.dave.framegrabber.data.Naming
import com.dave.framegrabber.data.Output
import com.dave.framegrabber.export.ContactSheet
import com.dave.framegrabber.ui.toast

/** «Лист кадров» (page «Инструменты»): all captured frames in one image, with times. */
class SheetPanel(private val s: MainScreen) {

    private val job = ExportJob(s, s.h(R.id.progressExport), s.h(R.id.txtExport))

    init {
        s.h<View>(R.id.btnSheet).setOnClickListener { build() }
    }

    private fun build() {
        val captures = CaptureStore.captures
        if (captures.isEmpty()) { s.act.toast("Сначала сними кадры — лист собирается из них"); return }
        val snapshot = captures.sortedBy { it.timeUs }
        val e = s.video.engine
        val title = s.video.baseName
        val sub = buildString {
            append("${snapshot.size} кадров")
            if (e != null) append(" · ${e.width}×${e.height} · ${TimeUtil.format(e.durationUs)}")
        }
        val name = Naming.stem(s.prefs.namePattern, title, 0, 1) + "_sheet.jpg"
        job.launch("Лист кадров", "Собираю лист кадров…", "image/jpeg") {
            job.report(0.3f, "Лист")
            val bmp = ContactSheet.build(snapshot, title, sub)
            job.report(0.8f, "Лист")
            // written straight into the gallery / chosen folder: no temp file
            Output.write(s.act, name, "image/jpeg", Output.Kind.IMAGE) { t ->
                t.stream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            }.also { bmp.recycle() }
        }
    }
}
