package com.dave.framegrabber

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Text recognition with Tesseract (works offline once the language files are downloaded).
 * Google ML Kit can't read Cyrillic or Armenian, Tesseract can.
 */
object Ocr {
    val LANGS = linkedMapOf("eng" to "English", "rus" to "Русский", "hye" to "Հայերեն")

    private fun dataDir(ctx: Context) = File(ctx.filesDir, "ocr")
    private fun file(ctx: Context, lang: String) = File(File(dataDir(ctx), "tessdata"), "$lang.traineddata")

    fun missing(ctx: Context, langs: List<String>) = langs.filter { !file(ctx, it).exists() }

    /** Downloads the "fast" models (a few MB each) from the official tesseract-ocr repository. */
    fun download(ctx: Context, langs: List<String>, progress: (String, Float) -> Unit) {
        for (lang in langs) {
            val dest = file(ctx, lang)
            if (dest.exists()) continue
            dest.parentFile!!.mkdirs()
            val tmp = File(dest.path + ".part")
            var url = URL("https://github.com/tesseract-ocr/tessdata_fast/raw/main/$lang.traineddata")
            var conn: HttpURLConnection
            var hops = 0
            while (true) { // follow redirects (github -> raw.githubusercontent.com)
                conn = url.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 15000; conn.readTimeout = 30000
                val code = conn.responseCode
                if (code in 300..399 && hops++ < 5) { url = URL(url, conn.getHeaderField("Location")); conn.disconnect(); continue }
                if (code != 200) error("Ошибка загрузки $lang: HTTP $code")
                break
            }
            val total = conn.contentLengthLong
            conn.inputStream.use { i ->
                tmp.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val n = i.read(buf); if (n < 0) break
                        o.write(buf, 0, n); read += n
                        if (total > 0) progress(lang, read.toFloat() / total)
                    }
                }
            }
            if (!tmp.renameTo(dest)) error("Не удалось сохранить $lang")
        }
    }

    fun recognize(ctx: Context, bmp: Bitmap, langs: List<String>): String {
        val api = TessBaseAPI()
        try {
            if (!api.init(dataDir(ctx).path, langs.joinToString("+"))) error("Не удалось запустить распознавание")
            api.setImage(bmp)
            return api.getUTF8Text()?.trim().orEmpty()
        } finally {
            api.recycle()
        }
    }
}
