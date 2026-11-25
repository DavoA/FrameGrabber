package com.dave.framegrabber.data

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import com.dave.framegrabber.core.*
import com.dave.framegrabber.export.*
import com.dave.framegrabber.batch.*
import com.dave.framegrabber.ocr.*
import com.dave.framegrabber.ui.*
import com.dave.framegrabber.ui.widget.*
import com.dave.framegrabber.R

/**
 * Where results go: the folder the user picked (SAF tree) or the default
 * Pictures/FrameGrabber, Movies/FrameGrabber, Download/FrameGrabber via MediaStore.
 */
object Output {
    enum class Kind { IMAGE, VIDEO, DOWNLOAD }

    /** A file being created in the destination; write to it, then [finish] (or [abort] on error). */
    class Target(private val ctx: Context, val uri: Uri, private val mediaStore: Boolean) {
        fun stream(): java.io.OutputStream =
            ctx.contentResolver.openOutputStream(uri, "w") ?: error("Не удалось открыть файл для записи")
        /** Read/write descriptor (needed by MediaMuxer); null if the folder doesn't allow it. */
        fun fd(): android.os.ParcelFileDescriptor? = runCatching { ctx.contentResolver.openFileDescriptor(uri, "rw") }.getOrNull()
        fun finish() {
            if (!mediaStore) return
            ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }
        fun abort() {
            runCatching {
                if (mediaStore) ctx.contentResolver.delete(uri, null, null)
                else DocumentsContract.deleteDocument(ctx.contentResolver, uri)
            }
        }
    }

    /**
     * Creates the output file directly in the destination (picked folder or MediaStore), so nothing
     * has to go through the app's cache folder (which some phones wipe while the app is running).
     */
    fun create(ctx: Context, name: String, mime: String, kind: Kind): Target {
        val prefs = Prefs(ctx)
        val tree = prefs.folderTree
        val cr = ctx.contentResolver
        if (tree != null && kind != Kind.DOWNLOAD) {
            val made = runCatching {
                val treeUri = Uri.parse(tree)
                val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
                DocumentsContract.createDocument(cr, parent, mime, name)
            }.getOrNull()
            if (made != null) return Target(ctx, made, mediaStore = false)
            // folder no longer accessible -> fall back to the default location
        }
        val (col, dir) = when (kind) {
            Kind.IMAGE -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_PICTURES
            Kind.VIDEO -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_MOVIES
            Kind.DOWNLOAD -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_DOWNLOADS
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/${Prefs.ALBUM}")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = cr.insert(col, values) ?: error("Не удалось создать файл в $dir/${Prefs.ALBUM}")
        return Target(ctx, uri, mediaStore = true)
    }

    /** Writes into a new destination file; cleans up if [write] fails. */
    inline fun write(ctx: Context, name: String, mime: String, kind: Kind, write: (Target) -> Unit): Uri {
        val t = create(ctx, name, mime, kind)
        try { write(t); t.finish() } catch (e: Throwable) { t.abort(); throw e }
        return t.uri
    }

    /** Copies [src] to the destination, returns its Uri. */
    fun save(ctx: Context, src: File, name: String, mime: String, kind: Kind): Uri =
        write(ctx, name, mime, kind) { t -> t.stream().use { o -> src.inputStream().use { it.copyTo(o) } } }

    /** Human description of where images go. */
    fun describe(ctx: Context): String {
        val p = Prefs(ctx)
        return if (p.folderTree != null) (p.folderTreeName ?: "выбранная папка") else "Pictures/${Prefs.ALBUM}"
    }
}
