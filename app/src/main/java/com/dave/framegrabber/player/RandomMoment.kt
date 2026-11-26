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

    /**
     * From the player's library (folders from the settings) if it has videos, otherwise from all
     * videos on the phone. Blocking (seconds when the video needs analysing).
     */
    fun pick(ctx: Context, exclude: Uri? = null, progress: (String) -> Boolean = { true }): Pick? {
        val lib = Library(ctx)
        val mine = runCatching { lib.scan() }.getOrDefault(emptyList())
            .filter { (lib.cachedDuration(it.file).takeIf { d -> d > 1 } ?: Long.MAX_VALUE) >= 60_000 }
            .map { Uri.fromFile(it.file) to it.name }
            .filter { it.first != exclude }
        if (mine.isNotEmpty()) return pickFrom(ctx, mine, progress)
        val phone = runCatching { VideoSource.allVideos(ctx) }.getOrDefault(emptyList())
            .filterIsInstance<Entry.Video>()
            .filter { it.durationMs >= 60_000 && it.uri != null && it.uri != exclude }
            .map { it.uri!! to it.name }
        return pickFrom(ctx, phone, progress)
    }

    /** A hot moment in one of [videos] (uri to name). */
    fun pickFrom(ctx: Context, videos: List<Pair<Uri, String>>, progress: (String) -> Boolean = { true }): Pick? {
        if (videos.isEmpty()) return null
        val store = PlayerStore(ctx)
        // half the time a video already analysed (instant), otherwise any video
        val analysed = videos.filter { (u, _) -> store.load(PlayerStore.idOf(u))?.activity != null }
        val (uri, name) = if (analysed.isNotEmpty() && Random.nextBoolean()) analysed.random() else videos.random()
        val saved = store.load(uri)
        val act = saved.activity ?: run {
            if (!progress("🎲 Ищу горячий момент в «$name»…")) return null
            val a = ActivityScan.compute(ctx, uri) { f -> progress("🎲 Ищу горячий момент в «$name»… ${(f * 100).toInt()}%") }
            saved.activity = a
            saved.name = name
            saved.uri = uri.toString()
            runCatching { store.save(saved) }
            a
        }
        var dur = saved.durationUs
        if (dur <= 0) dur = runCatching {
            android.media.MediaMetadataRetriever().let { r ->
                try { r.setDataSource(ctx, uri); (r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0) * 1000 }
                finally { r.release() }
            }
        }.getOrDefault(0L)
        if (dur <= 0) return null
        val t = ActivityScan.hotMoments(act, dur).randomOrNull() ?: return null
        return Pick(uri, name, (t - 2_000_000).coerceAtLeast(0))
    }
}
