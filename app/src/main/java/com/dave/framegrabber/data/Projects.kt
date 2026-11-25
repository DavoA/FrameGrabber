package com.dave.framegrabber.data

import android.content.Context
import android.net.Uri
import com.dave.framegrabber.core.Fmt
import com.dave.framegrabber.core.FrameRender
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Project history: every opened video has a project that keeps its frames, marks, both «Отрезок»
 * ranges and the last position, so opening the video again continues where you left off.
 *
 * Stored in filesDir/projects/<id>/: project.json + the captured frame files.
 * All functions do file I/O: call them off the main thread unless noted.
 */
object Projects {

    /** A frame as saved in project.json (the file lives in the project folder). */
    class Frame(
        val file: String, val timeUs: Long, val fmt: Fmt, val width: Int, val height: Int,
        val exact: Boolean, val sharpened: Boolean, val galleryUri: String?
    )

    class Project(
        val id: String,
        val ref: String,
        val isPath: Boolean,
        var name: String,
        var positionUs: Long = 0,
        val marks: MutableList<Long> = mutableListOf(),
        var clipsFrom: String = "", var clipsTo: String = "",
        var batchFrom: String = "", var batchTo: String = "",
        val frames: MutableList<Frame> = mutableListOf(),
        var updated: Long = System.currentTimeMillis(),
    ) {
        val uri: Uri get() = if (isPath) Uri.fromFile(File(ref)) else Uri.parse(ref)
    }

    /** One line in the history list. */
    class Summary(val id: String, val ref: String, val isPath: Boolean, val name: String,
                  val frames: Int, val marks: Int, val updated: Long, val bytes: Long) {
        val uri: Uri get() = if (isPath) Uri.fromFile(File(ref)) else Uri.parse(ref)
    }

    /** "/storage/…/a.mp4" or "content://…" (same as [Recent]). */
    fun refOf(uri: Uri): Pair<String, Boolean> =
        if (uri.scheme == "file") uri.path!! to true else uri.toString() to false

    fun idOf(ref: String): String =
        MessageDigest.getInstance("SHA-1").digest(ref.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    fun root(ctx: Context) = File(ctx.filesDir, "projects")
    fun dir(ctx: Context, id: String) = File(root(ctx), id)
    private fun jsonFile(ctx: Context, id: String) = File(dir(ctx, id), "project.json")

    /** The saved project of this video, or a new empty one. */
    fun open(ctx: Context, uri: Uri, name: String): Project {
        val (ref, isPath) = refOf(uri)
        val id = idOf(ref)
        dir(ctx, id).mkdirs()
        return load(ctx, id)?.also { it.name = name } ?: Project(id, ref, isPath, name)
    }

    fun load(ctx: Context, id: String): Project? = runCatching {
        val o = JSONObject(jsonFile(ctx, id).readText())
        val p = Project(
            id, o.getString("ref"), o.optBoolean("path"), o.optString("name"),
            o.optLong("pos"),
            clipsFrom = o.optString("cf"), clipsTo = o.optString("ct"),
            batchFrom = o.optString("bf"), batchTo = o.optString("bt"),
            updated = o.optLong("t"),
        )
        o.optJSONArray("marks")?.let { a -> for (i in 0 until a.length()) p.marks += a.getLong(i) }
        val folder = dir(ctx, id)
        o.optJSONArray("frames")?.let { a ->
            for (i in 0 until a.length()) {
                val f = a.getJSONObject(i)
                val name = f.getString("f")
                if (!File(folder, name).exists()) continue   // deleted outside the app
                p.frames += Frame(
                    name, f.optLong("us"), runCatching { Fmt.valueOf(f.optString("fmt")) }.getOrDefault(Fmt.JPG),
                    f.optInt("w"), f.optInt("h"), f.optBoolean("x", true), f.optBoolean("s"),
                    f.optString("g").ifEmpty { null }
                )
            }
        }
        p
    }.getOrNull()

    fun save(ctx: Context, p: Project) = write(ctx, p.id, json(p))

    /** The project as text (cheap; done on the main thread, written later on the disk thread). */
    fun json(p: Project): String {
        val o = JSONObject()
            .put("ref", p.ref).put("path", p.isPath).put("name", p.name).put("pos", p.positionUs)
            .put("cf", p.clipsFrom).put("ct", p.clipsTo).put("bf", p.batchFrom).put("bt", p.batchTo)
            .put("t", p.updated)
            .put("marks", JSONArray().apply { p.marks.forEach { put(it) } })
            .put("frames", JSONArray().apply {
                p.frames.forEach { f ->
                    put(JSONObject().put("f", f.file).put("us", f.timeUs).put("fmt", f.fmt.name)
                        .put("w", f.width).put("h", f.height).put("x", f.exact).put("s", f.sharpened)
                        .put("g", f.galleryUri ?: ""))
                }
            })
        return o.toString()
    }

    fun write(ctx: Context, id: String, json: String) {
        val d = dir(ctx, id).apply { mkdirs() }
        // write-then-rename: a crash mid-write never leaves a broken project
        val tmp = File(d, "project.json.tmp")
        tmp.writeText(json)
        tmp.renameTo(jsonFile(ctx, id))
    }

    /** Adds a frame to a project that isn't open right now (background batch of another video). */
    @Synchronized
    fun appendFrame(ctx: Context, id: String, ref: String, isPath: Boolean, name: String, f: Frame) {
        val p = load(ctx, id) ?: Project(id, ref, isPath, name)
        p.frames += f
        p.updated = System.currentTimeMillis()
        save(ctx, p)
    }

    /** Cover picture of a project (written when the video is opened). */
    fun cover(ctx: Context, id: String) = File(dir(ctx, id), "cover.jpg")

    fun list(ctx: Context): List<Summary> =
        (root(ctx).listFiles() ?: emptyArray()).mapNotNull { d ->
            val p = load(ctx, d.name) ?: return@mapNotNull null
            val bytes = d.listFiles()?.sumOf { it.length() } ?: 0L
            if (p.frames.isEmpty() && p.marks.isEmpty() && !cover(ctx, p.id).exists()) return@mapNotNull null
            Summary(p.id, p.ref, p.isPath, p.name, p.frames.size, p.marks.size, p.updated, bytes)
        }.sortedByDescending { it.updated }

    fun delete(ctx: Context, id: String) { dir(ctx, id).deleteRecursively() }

    fun frameToCapture(ctx: Context, id: String, f: Frame): Capture? {
        val file = File(dir(ctx, id), f.file)
        val thumb = FrameRender.decodeFile(file, 320) ?: return null
        return Capture(file, thumb, f.timeUs, f.fmt, f.width, f.height, f.exact, f.sharpened)
            .apply { galleryUri = f.galleryUri?.let(Uri::parse) }
    }

    fun captureToFrame(c: Capture) =
        Frame(c.file.name, c.timeUs, c.fmt, c.width, c.height, c.exact, c.sharpened, c.galleryUri?.toString())
}
