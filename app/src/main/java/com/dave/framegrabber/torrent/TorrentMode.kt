package com.dave.framegrabber.torrent

import android.content.Context

/** How «⬇ Торрент» works (Общие настройки → Торренты). */
enum class TorrentMode(val key: String, val title: String, val hint: String) {
    APP("app", "В приложении", "Видео качается прямо в приложение и появляется в «Плеере»"),
    CLIENT("client", "Торрент-приложение", "Торрент открывается в твоём торрент-приложении; папка Download видна в «Плеере»"),
    FILE("file", "Только .torrent", "Файл .torrent сохраняется в Download/FrameGrabber");

    companion object {
        fun get(ctx: Context): TorrentMode {
            val v = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("torrentMode", APP.key)
            return entries.firstOrNull { it.key == v } ?: APP
        }
        fun set(ctx: Context, m: TorrentMode) =
            ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("torrentMode", m.key).apply()
    }
}
