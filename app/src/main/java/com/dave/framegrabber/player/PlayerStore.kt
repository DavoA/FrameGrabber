package com.dave.framegrabber.player

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * What the player remembers about each video (filesDir/player/<id>.json):
 * where you stopped, your «лучшие моменты», the watch heat map, the activity curve, the VR mode.
 * Plus "skip the intro" per folder (videos of one studio usually sit in one folder).
 */
class PlayerStore(private val ctx: Context) {

    companion object {
        /** Heat map / activity resolution: buckets across the whole video. */
        const val BUCKETS = 200

        fun idOf(uri: Uri): String {
            val ref = if (uri.scheme == "file") uri.path ?: uri.toString() else uri.toString()
            return MessageDigest.getInstance("SHA-1").digest(ref.toByteArray())
                .joinToString("") { "%02x".format(it) }.take(16)
        }
    }

    class Video(
        val id: String,
        var uri: String = "",
        var name: String = "",
        var durationUs: Long = 0,
        var positionUs: Long = 0,
        var lastWatched: Long = 0,
        val moments: MutableList<Long> = mutableListOf(),
        /** Seconds watched in each of [BUCKETS] parts of the video. */
        val heat: FloatArray = FloatArray(BUCKETS),
        /** 0…1 "how much is going on" per bucket; null = not analysed yet. */
        var activity: FloatArray? = null,
        /** "flat", "vr180", "vr360", "vr360tb", "vr180mono" */
        var vr: String = "",
    )

    private val dir = File(ctx.filesDir, "player").apply { mkdirs() }
    private val sp = ctx.getSharedPreferences("player", Context.MODE_PRIVATE)

    fun load(uri: Uri): Video = load(idOf(uri)) ?: Video(idOf(uri), uri = uri.toString())

    fun load(id: String): Video? = runCatching {
        val o = JSONObject(File(dir, "$id.json").readText())
        Video(
            id, o.optString("uri"), o.optString("name"), o.optLong("dur"), o.optLong("pos"), o.optLong("last"),
            o.optJSONArray("moments")?.let { a -> (0 until a.length()).map { a.getLong(it) }.toMutableList() } ?: mutableListOf(),
            floats(o.optJSONArray("heat")) ?: FloatArray(BUCKETS),
            floats(o.optJSONArray("act")),
            o.optString("vr"),
        )
    }.getOrNull()

    fun save(v: Video) {
        val o = JSONObject()
            .put("uri", v.uri).put("name", v.name).put("dur", v.durationUs).put("pos", v.positionUs)
            .put("last", v.lastWatched)
            .put("moments", JSONArray(v.moments))
            .put("heat", JSONArray().apply { v.heat.forEach { put(round(it)) } })
            .put("vr", v.vr)
        v.activity?.let { a -> o.put("act", JSONArray().apply { a.forEach { put(round(it)) } }) }
        val tmp = File(dir, "${v.id}.json.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(File(dir, "${v.id}.json"))
    }

    /** Everything watched, most recent first. */
    fun all(): List<Video> = (dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
        .mapNotNull { load(it.name.removeSuffix(".json")) }
        .sortedByDescending { it.lastWatched }

    fun delete(id: String) { File(dir, "$id.json").delete() }

    // ================================================================ skip the intro (per folder)

    fun introSkipUs(folder: String?): Long = folder?.let { sp.getLong("intro:$it", 0) } ?: 0
    fun setIntroSkip(folder: String?, us: Long) {
        folder ?: return
        sp.edit().apply { if (us <= 0) remove("intro:$folder") else putLong("intro:$folder", us) }.apply()
    }

    private fun round(f: Float) = Math.round(f * 1000) / 1000.0
    private fun floats(a: JSONArray?): FloatArray? = a?.let { FloatArray(it.length()) { i -> it.getDouble(i).toFloat() } }
}
