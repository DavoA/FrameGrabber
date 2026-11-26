package com.dave.framegrabber.player

import android.content.Context
import android.net.Uri
import com.dave.framegrabber.ui.browser.Entry
import com.dave.framegrabber.ui.browser.VideoSource
import kotlin.random.Random

/**
 * 🎲 «Случайный момент»: a random video of the library (videos on the phone), and in it one of
 * the hottest places by the activity curve (worked out on the spot if the video wasn't analysed).
 */
object RandomMoment {

    class Pick(val uri: Uri, val name: String, val startUs: Long)

    /** Blocking (seconds when the video needs analysing). [progress] gets a status line. */
    fun pick(ctx: Context, exclude: Uri? = null, progress: (String) -> Boolean = { true }): Pick? {
        val store = PlayerStore(ctx)
        val library = runCatching { VideoSource.allVideos(ctx) }.getOrDefault(emptyList())
            .filterIsInstance<Entry.Video>()
            .filter { it.durationMs >= 60_000 && it.uri != null && it.uri != exclude }
        if (library.isEmpty()) return null
        // half the time a video already analysed (instant), otherwise any video
        val analysed = library.filter { v -> store.load(PlayerStore.idOf(v.uri!!))?.activity != null }
        val v = if (analysed.isNotEmpty() && Random.nextBoolean()) analysed.random() else library.random()
        val uri = v.uri!!
        val saved = store.load(uri)
        val act = saved.activity ?: run {
            if (!progress("Ищу горячий момент в «${v.name}»…")) return null
            val a = ActivityScan.compute(ctx, uri) { f -> progress("Ищу горячий момент в «${v.name}»… ${(f * 100).toInt()}%") }
            saved.activity = a
            saved.name = v.name
            saved.uri = uri.toString()
            if (saved.durationUs <= 0) saved.durationUs = v.durationMs * 1000
            runCatching { store.save(saved) }
            a
        }
        val dur = if (saved.durationUs > 0) saved.durationUs else v.durationMs * 1000
        val t = ActivityScan.hotMoments(act, dur).randomOrNull() ?: return null
        return Pick(uri, v.name, (t - 2_000_000).coerceAtLeast(0))
    }
}
