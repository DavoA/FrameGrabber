package com.dave.framegrabber.telegram

import android.content.Context
import android.graphics.BitmapFactory
import com.dave.framegrabber.core.ImageMetrics
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Every post published from the app: for the channel statistics and for the "already posted?"
 * check. Stored in filesDir/tg_posts.json (newest last).
 *
 * The video is recognised by [videoKey] = size + duration, so a copy or a moved file still counts
 * as the same video. Frames are compared by a 64-bit picture hash ([ImageMetrics.dHash]).
 */
class PostLog(ctx: Context) {

    class Post(
        val time: Long, val chat: String, val messageId: Long, val link: String?,
        val studio: String, val models: List<String>, val tags: List<String>,
        val videoName: String, val videoKey: String, val frameHashes: List<Long>,
        /** "app" = published from the app; "channel" = read from the channel's public page. */
        val source: String = "app",
    )

    /** What the check found; empty = nothing posted before. */
    class Duplicates(
        /** Posts of this same video. */
        val sameVideo: List<Post>,
        /** Photo number (1…6) -> the earlier post with a (nearly) identical picture. */
        val sameFrames: Map<Int, Post>,
        /** Posts with the same studio and the same set of models. */
        val sameNames: List<Post>,
    ) {
        val any get() = sameVideo.isNotEmpty() || sameFrames.isNotEmpty() || sameNames.isNotEmpty()
    }

    private val file = File(ctx.filesDir, "tg_posts.json")

    /** All posts, oldest first. */
    val posts: List<Post> get() = load().sortedBy { it.time }

    fun add(p: Post) = write(load() + p)

    /**
     * Posts read from the channel: skips ones already known (same message id, or the same text
     * published by the app within minutes). Returns how many were added.
     */
    @Synchronized
    fun addFromChannel(list: List<Post>): Int {
        val old = load()
        val ids = old.map { it.messageId }.toHashSet()
        val fresh = list.filter { p ->
            p.messageId !in ids && old.none { o ->
                o.source == "app" && kotlin.math.abs(o.time - p.time) < 10 * 60_000 &&
                    o.studio.equals(p.studio, true) && o.models.map { it.lowercase() } == p.models.map { it.lowercase() }
            }
        }
        if (fresh.isNotEmpty()) write(old + fresh)
        return fresh.size
    }

    /** Highest message id seen in the channel (for "update": read only newer posts). */
    fun lastChannelId(): Long = load().maxOfOrNull { it.messageId } ?: 0

    private fun write(list: List<Post>) {
        val a = JSONArray()
        list.forEach { a.put(toJson(it)) }
        val tmp = File(file.path + ".tmp")
        tmp.writeText(a.toString())
        tmp.renameTo(file)
    }

    fun check(videoKey: String, frameHashes: List<Long>, studio: String, models: List<String>): Duplicates {
        val all = load()
        val sameVideo = if (videoKey.isEmpty()) emptyList() else all.filter { it.videoKey == videoKey }
        val sameFrames = LinkedHashMap<Int, Post>()
        frameHashes.forEachIndexed { i, h ->
            all.lastOrNull { p -> p.frameHashes.any { ImageMetrics.hamming(it, h) <= FRAME_DISTANCE } }
                ?.let { sameFrames[i + 1] = it }
        }
        val names = models.map { it.lowercase().trim() }.toSet()
        val sameNames = if (studio.isBlank() || names.isEmpty()) emptyList() else all.filter {
            it.studio.equals(studio.trim(), ignoreCase = true) && it.models.map { m -> m.lowercase().trim() }.toSet() == names
        }
        return Duplicates(sameVideo, sameFrames, sameNames)
    }

    private fun load(): List<Post> = runCatching {
        if (!file.exists()) return emptyList()
        val a = JSONArray(file.readText())
        (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun toJson(p: Post) = JSONObject()
        .put("t", p.time).put("chat", p.chat).put("id", p.messageId).put("link", p.link ?: "")
        .put("studio", p.studio).put("models", JSONArray(p.models)).put("tags", JSONArray(p.tags))
        .put("video", p.videoName).put("vkey", p.videoKey)
        .put("hashes", JSONArray().apply { p.frameHashes.forEach { put(java.lang.Long.toHexString(it)) } })
        .put("src", p.source)

    private fun fromJson(o: JSONObject): Post {
        fun strs(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
        return Post(
            o.optLong("t"), o.optString("chat"), o.optLong("id"), o.optString("link").ifEmpty { null },
            o.optString("studio"), strs("models"), strs("tags"), o.optString("video"), o.optString("vkey"),
            strs("hashes").mapNotNull { java.lang.Long.parseUnsignedLong(it, 16) },
            o.optString("src", "app"),
        )
    }

    companion object {
        /** Up to this many different bits of 64 = the same picture (re-encoded, slightly shifted…). */
        const val FRAME_DISTANCE = 6

        /** Picture hash of a frame file (decoded small). */
        fun hashOf(f: File): Long? = runCatching {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            var sample = 1
            while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= 256) sample *= 2
            val b = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            ImageMetrics.dHash(b).also { b.recycle() }
        }.getOrNull()

        /** "size:duration" — the same video even if copied or renamed. */
        fun videoKey(sizeBytes: Long, durationUs: Long) =
            if (sizeBytes <= 0) "" else "$sizeBytes:${durationUs / 1000}"
    }
}
