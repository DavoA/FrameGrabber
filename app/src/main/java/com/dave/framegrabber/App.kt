package com.dave.framegrabber

import android.app.Application
import com.dave.framegrabber.data.ColorScheme

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ColorScheme.install(this)
        com.dave.framegrabber.tgclient.PostQueue.resume(this)   // «Очередь постов» goes on where it stopped
        com.dave.framegrabber.data.AppLock.install(this)   // «Защита»: PIN / fingerprint on entry
        com.dave.framegrabber.site.WatchJob.schedule(this)   // the daily check of followed models
        runCatching { com.dave.framegrabber.torrent.TorrentEngine.restore(this) }   // unfinished torrent downloads go on
    }
}
