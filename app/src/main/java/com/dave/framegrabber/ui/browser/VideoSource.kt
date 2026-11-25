package com.dave.framegrabber.ui.browser

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.File

/** One row in the file browser. */
sealed class Entry {
    class Dir(val file: File) : Entry()
    class Video(
        val name: String, val size: Long, val dateMs: Long, val durationMs: Long,
        val sub: String?, val file: File?, val uri: Uri?
    ) : Entry() {
        val key: String get() = file?.path ?: uri.toString()
    }
    /** Section title ("Папки здесь", "Видео на телефоне"). */
    class Header(val title: String) : Entry()
}

/** Where the browser's lists come from: folders on the phone and the system media index. */
object VideoSource {
    private val VIDEO_EXT = setOf(
        "mp4", "m4v", "mkv", "webm", "mov", "3gp", "3g2", "avi", "ts", "mts", "m2ts",
        "flv", "wmv", "mpg", "mpeg", "ogv", "vob"
    )

    /** Sub-folders (A→Z) and videos (newest first) in [dir]; hidden files skipped. */
    fun listFolder(dir: File): List<Entry> {
        val files = dir.listFiles().orEmpty().filter { !it.name.startsWith(".") }
        val dirs: List<Entry> = files.filter { it.isDirectory }.sortedBy { it.name.lowercase() }.map { Entry.Dir(it) }
        val vids: List<Entry> = files.filter { it.isFile && it.extension.lowercase() in VIDEO_EXT }
            .sortedByDescending { it.lastModified() }
            .map { Entry.Video(it.name, it.length(), it.lastModified(), -1, null, it, null) }
        return dirs + vids
    }

    /** Search: folders in [here] whose name matches + videos anywhere on the phone. */
    fun search(ctx: Context, here: File, q: String): List<Entry> {
        val ql = q.lowercase()
        val folders = here.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith(".") && ql in it.name.lowercase() }
            .sortedBy { it.name.lowercase() }.map { Entry.Dir(it) }
        val vids = allVideos(ctx, q)
        return buildList {
            if (folders.isNotEmpty()) { add(Entry.Header("Папки здесь")); addAll(folders) }
            if (vids.isNotEmpty()) { add(Entry.Header("Видео на телефоне")); addAll(vids) }
        }
    }

    /** Every video the system knows about (newest first), optionally filtered by name/folder. */
    fun allVideos(ctx: Context, q: String? = null): List<Entry> {
        val col = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val proj = arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_MODIFIED, MediaStore.Video.Media.DURATION, MediaStore.Video.Media.RELATIVE_PATH
        )
        val sel = q?.let { "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ? OR ${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?" }
        val args = q?.let { arrayOf("%$it%", "%$it%") }
        val out = mutableListOf<Entry>()
        ctx.contentResolver.query(col, proj, sel, args, "${MediaStore.Video.Media.DATE_MODIFIED} DESC")?.use { c ->
            while (c.moveToNext()) {
                out += Entry.Video(
                    name = c.getString(1) ?: "video",
                    size = c.getLong(2),
                    dateMs = c.getLong(3) * 1000,
                    durationMs = c.getLong(4),
                    sub = c.getString(5)?.trimEnd('/'),
                    file = null,
                    uri = ContentUris.withAppendedId(col, c.getLong(0))
                )
            }
        }
        return out
    }
}
