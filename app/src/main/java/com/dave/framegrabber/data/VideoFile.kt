package com.dave.framegrabber.data

import android.content.Context
import android.net.Uri
import java.io.File

/** Is a remembered video still on the phone? (deleted videos are hidden from «Недавние» and the history) */
object VideoFile {
    fun exists(ctx: Context, ref: String, isPath: Boolean): Boolean =
        if (isPath) File(ref).exists()
        else runCatching { ctx.contentResolver.openFileDescriptor(Uri.parse(ref), "r")?.use { true } ?: false }.getOrDefault(false)
}
