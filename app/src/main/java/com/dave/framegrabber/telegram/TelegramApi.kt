package com.dave.framegrabber.telegram

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A Telegram error in words ("Bad Request: chat not found"); [retryAfter] for "too many requests". */
class TgException(message: String, val code: Int = 0, val retryAfter: Int = 0) : Exception(message)

/**
 * The few Bot API calls the app needs. Plain HttpURLConnection, no libraries.
 * Blocking: call off the main thread. [base] is replaceable for tests.
 */
class TelegramApi(private val token: String, private val base: String = "https://api.telegram.org") {

    class Bot(val id: Long, val username: String, val name: String)
    class Chat(val id: Long, val title: String, val username: String?, val type: String)

    fun getMe(): Bot {
        val r = get("getMe")
        return Bot(r.getLong("id"), r.optString("username"), r.optString("first_name"))
    }

    fun getChat(chat: String): Chat {
        val r = get("getChat", "chat_id" to chat)
        return Chat(r.getLong("id"), r.optString("title"), r.optString("username").ifEmpty { null }, r.optString("type"))
    }

    /** "administrator" / "creator" / "member" / "left"…, and whether it may post in a channel. */
    fun memberStatus(chat: String, userId: Long): Pair<String, Boolean> {
        val r = get("getChatMember", "chat_id" to chat, "user_id" to userId.toString())
        val status = r.optString("status")
        val canPost = status == "creator" || (status == "administrator" && r.optBoolean("can_post_messages", true))
        return status to canPost
    }

    /**
     * An album; the caption goes under the first photo. Returns the message id of the first photo.
     * [photos] are JPEG bytes.
     */
    fun sendAlbum(chat: String, photos: List<ByteArray>, caption: String, progress: (Int) -> Unit = {}): Long {
        val media = JSONArray()
        photos.indices.forEach { i ->
            media.put(JSONObject().put("type", "photo").put("media", "attach://p$i").apply {
                if (i == 0 && caption.isNotEmpty()) put("caption", caption)
            })
        }
        val fields = listOf("chat_id" to chat, "media" to media.toString())
        val files = photos.mapIndexed { i, b -> Triple("p$i", "photo_$i.jpg", b) }
        val res = multipart("sendMediaGroup", fields, files, progress)
        val arr = res as? JSONArray ?: throw TgException("Неожиданный ответ Telegram")
        return arr.getJSONObject(0).getLong("message_id")
    }

    // ================================================================ HTTP

    private fun url(method: String) = URL("$base/bot$token/$method")

    private fun get(method: String, vararg q: Pair<String, String>): JSONObject {
        val query = q.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
        val c = URL(url(method).toString() + if (query.isEmpty()) "" else "?$query").openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 20_000
        return read(c) as? JSONObject ?: throw TgException("Неожиданный ответ Telegram")
    }

    private fun multipart(
        method: String, fields: List<Pair<String, String>>,
        files: List<Triple<String, String, ByteArray>>, progress: (Int) -> Unit
    ): Any {
        val boundary = "----FrameGrabber" + System.nanoTime()
        val body = ByteArrayOutputStream()
        fun line(s: String) = body.write((s + "\r\n").toByteArray(Charsets.UTF_8))
        for ((k, v) in fields) {
            line("--$boundary")
            line("Content-Disposition: form-data; name=\"$k\"")
            line("Content-Type: text/plain; charset=utf-8")
            line("")
            line(v)
        }
        for ((name, fileName, bytes) in files) {
            line("--$boundary")
            line("Content-Disposition: form-data; name=\"$name\"; filename=\"$fileName\"")
            line("Content-Type: image/jpeg")
            line("")
            body.write(bytes); line("")
        }
        line("--$boundary--")
        val data = body.toByteArray()

        val c = url(method).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 120_000
        c.doOutput = true
        c.requestMethod = "POST"
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        c.setFixedLengthStreamingMode(data.size)
        c.outputStream.use { o -> writeWithProgress(o, data, progress) }
        return read(c)
    }

    private fun writeWithProgress(o: OutputStream, data: ByteArray, progress: (Int) -> Unit) {
        var off = 0
        var last = -1
        while (off < data.size) {
            val n = minOf(64 * 1024, data.size - off)
            o.write(data, off, n); off += n
            val p = (off * 100L / data.size).toInt()
            if (p != last) { last = p; progress(p) }
        }
    }

    /** "result" of a reply, or a [TgException] with Telegram's own description. */
    private fun read(c: HttpURLConnection): Any {
        try {
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream ?: c.inputStream)
                .bufferedReader().use { it.readText() }
            val o = runCatching { JSONObject(text) }.getOrNull()
                ?: throw TgException("Telegram ответил кодом $code", code)
            if (!o.optBoolean("ok")) {
                val retry = o.optJSONObject("parameters")?.optInt("retry_after") ?: 0
                throw TgException(explain(o.optString("description"), o.optInt("error_code", code)), o.optInt("error_code", code), retry)
            }
            return o.get("result")
        } finally {
            c.disconnect()
        }
    }

    /** Telegram's English errors in plain words. */
    private fun explain(d: String, code: Int): String = when {
        code == 401 || d.contains("Unauthorized") -> "Неверный токен бота (проверь, что скопирован целиком)"
        d.contains("chat not found") -> "Канал не найден: проверь @имя канала и что бот добавлен в канал"
        d.contains("not enough rights") || d.contains("need administrator rights") ->
            "У бота нет прав публиковать: сделай его админом канала с правом «Публикация сообщений»"
        d.contains("bot is not a member") -> "Бот не добавлен в канал"
        d.contains("Too Many Requests") -> "Слишком часто — Telegram просит подождать"
        d.contains("caption is too long") -> "Подпись длиннее 1024 символов"
        else -> "Telegram: $d"
    }
}
