package com.dave.framegrabber

import android.app.Application
import com.dave.framegrabber.data.ColorScheme

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ColorScheme.install(this)
    }
}
