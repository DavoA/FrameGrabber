package com.dave.framegrabber.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileNotFoundException

/** Is a remembered video still on the phone? (deleted videos are hidden and their frames cleaned up) */
object VideoFile {
    enum class State { HERE, GONE, UNKNOWN }

    fun exists(ctx: Context, ref: String, isPath: Boolean): Boolean = state(ctx, ref, isPath) == State.HERE

    /** GONE only when the phone says the file is really not there (not for a lost permission). */
    fun state(ctx: Context, ref: String, isPath: Boolean): State =
        if (isPath) { if (File(ref).exists()) State.HERE else State.GONE }
        else try {
            ctx.contentResolver.openFileDescriptor(Uri.parse(ref), "r")?.use { State.HERE } ?: State.GONE
        } catch (e: FileNotFoundException) { State.GONE }
        catch (e: IllegalArgumentException) { State.GONE }
        catch (e: Exception) { State.UNKNOWN }

    /**
     * Projects whose video was deleted: their frames are removed too — the copies inside the app
     * and the ones the app saved to the gallery (if they are still there). The open project is kept.
     */
    fun cleanUp(ctx: Context, keepId: String?) {
        Projects.list(ctx).forEach { p ->
            if (p.id == keepId || state(ctx, p.ref, p.isPath) != State.GONE) return@forEach
            Projects.load(ctx, p.id)?.frames?.forEach { f ->
                val g = f.galleryUri?.takeIf { it.isNotBlank() } ?: return@forEach
                runCatching { ctx.contentResolver.delete(Uri.parse(g), null, null) }
            }
            Projects.delete(ctx, p.id)
            Recent.remove(ctx, p.ref)
        }
    }
}
