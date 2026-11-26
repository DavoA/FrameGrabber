package com.dave.framegrabber.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import com.dave.framegrabber.R

/** Dialogs, toasts, keyboard, sharing — shared by all screens. */

fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

fun Context.copyToClipboard(label: String, text: String) =
    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text))

/** Error dialog with the full text and a "Копировать" button, so it can be sent to the developer. */
fun Activity.showError(title: String, t: Throwable) {
    val msg = t.message ?: t.javaClass.simpleName
    AlertDialog.Builder(this)
        .setTitle(title)
        .setMessage(msg)
        .setPositiveButton("OK", null)
        .setNeutralButton("Копировать") { _, _ -> copyToClipboard("error", msg) }
        .show()
}

/** Non-cancellable "please wait" dialog. */
fun Activity.progressDialog(msg: String): AlertDialog =
    AlertDialog.Builder(this).setMessage(msg).setCancelable(false).show()

fun Activity.shareUri(uri: Uri, mime: String) {
    val i = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    startActivity(Intent.createChooser(i, "Поделиться"))
}

fun Activity.shareText(text: String) =
    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Поделиться"))

fun View.hideKeyboard() {
    (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(windowToken, 0)
    clearFocus()
}

/** TextWatcher without the two empty methods. */
fun TextView.onTextChanged(block: (String) -> Unit) = addTextChangedListener(object : TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    override fun afterTextChanged(s: Editable?) = block(s?.toString().orEmpty())
})

private val schemeAttrs by lazy {
    mapOf(
        R.color.bg to R.attr.cBg, R.color.bgClassic to R.attr.cBgClassic, R.color.surface to R.attr.cSurface,
        R.color.surface2 to R.attr.cSurface2, R.color.stroke to R.attr.cStroke, R.color.primary to R.attr.cPrimary,
        R.color.primaryBright to R.attr.cPrimaryBright, R.color.accent to R.attr.cAccent,
        R.color.text to R.attr.cText, R.color.textDim to R.attr.cTextDim,
    )
}

/** A palette color in the current color scheme (green / pink / orange / …); other colors as they are. */
fun Context.tc(id: Int): Int {
    val attr = schemeAttrs[id] ?: return getColor(id)
    val tv = android.util.TypedValue()
    return if (theme.resolveAttribute(attr, tv, true) &&
        tv.type >= android.util.TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= android.util.TypedValue.TYPE_LAST_COLOR_INT) tv.data
    else getColor(id)
}
