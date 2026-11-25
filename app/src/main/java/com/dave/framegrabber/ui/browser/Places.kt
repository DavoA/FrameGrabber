package com.dave.framegrabber.ui.browser

import android.os.Environment
import java.io.File

/** A quick-access chip in the browser ("Камера", "Загрузки", …). [all] = "Все видео". */
class Place(val title: String, val dir: File?, val all: Boolean = false)

object Places {
    /** The chips that make sense on this phone (folders that don't exist are left out). */
    fun available(root: File): List<Place> = listOf(
        Place("Все видео", null, all = true),
        Place("Память", root),
        Place("Камера", File(root, "DCIM/Camera")),
        Place("Загрузки", File(root, Environment.DIRECTORY_DOWNLOADS)),
        Place("Telegram", listOf(
            File(root, "Android/media/org.telegram.messenger/Telegram/Telegram Video"),
            File(root, "Telegram/Telegram Video"),
            File(root, "Download/Telegram")
        ).firstOrNull { it.isDirectory }),
        Place("WhatsApp", File(root, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video")),
        Place("Фильмы", File(root, Environment.DIRECTORY_MOVIES)),
        Place("Записи экрана", listOf(
            File(root, "Movies/Screen recordings"), File(root, "DCIM/Screen recordings"),
            File(root, "DCIM/ScreenRecorder"), File(root, "Pictures/Screenshots")
        ).firstOrNull { it.isDirectory }),
    ).filter { it.all || (it.dir != null && it.dir.isDirectory) }
}
