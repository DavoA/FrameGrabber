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
    fun create(ctx: Context, name: String, mime: String, kind: Kind, sub: String? = null): Target {
        val prefs = Prefs(ctx)
        val tree = prefs.folderTree
        val cr = ctx.contentResolver
        if (tree != null && kind != Kind.DOWNLOAD) {
            val made = runCatching {
                val treeUri = Uri.parse(tree)
                val top = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
                val parent = if (sub == null) top else subFolder(ctx, treeUri, top, sub)
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
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/${Prefs.ALBUM}" + (sub?.let { "/$it" } ?: ""))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = cr.insert(col, values) ?: error("Не удалось создать файл в $dir/${Prefs.ALBUM}")
        return Target(ctx, uri, mediaStore = true)
    }

    /** Writes into a new destination file; cleans up if [write] fails. */
    inline fun write(ctx: Context, name: String, mime: String, kind: Kind, sub: String? = null, write: (Target) -> Unit): Uri {
        val t = create(ctx, name, mime, kind, sub)
        try { write(t); t.finish() } catch (e: Throwable) { t.abort(); throw e }
        return t.uri
    }

    /** Copies [src] to the destination, returns its Uri. */
    fun save(ctx: Context, src: File, name: String, mime: String, kind: Kind, sub: String? = null): Uri =
        write(ctx, name, mime, kind, sub) { t -> t.stream().use { o -> src.inputStream().use { it.copyTo(o) } } }

    /** Picked folder + "/sub" (found or created once, then remembered). */
    private val subCache = HashMap<String, Uri>()

    @Synchronized
    private fun subFolder(ctx: Context, treeUri: Uri, top: Uri, sub: String): Uri {
        val key = "$treeUri|$sub"
        subCache[key]?.let { return it }
        val cr = ctx.contentResolver
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getDocumentId(top))
        val found = cr.query(children, arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
            var r: Uri? = null
            while (c.moveToNext()) {
                if (c.getString(1) == sub && c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    r = DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0)); break
                }
            }
            r
        }
        val dir = found ?: DocumentsContract.createDocument(cr, top, DocumentsContract.Document.MIME_TYPE_DIR, sub)
            ?: error("Не удалось создать папку $sub")
        subCache[key] = dir
        return dir
    }

    /** Human description of where images go. */
    fun describe(ctx: Context, kind: Kind = Kind.IMAGE): String {
        val p = Prefs(ctx)
        if (p.folderTree != null) return p.folderTreeName ?: "выбранная папка"
        return when (kind) {
            Kind.VIDEO -> "Movies/${Prefs.ALBUM}"
            Kind.IMAGE -> "Pictures/${Prefs.ALBUM}"
            else -> "Download/${Prefs.ALBUM}"
        }
    }
}
