package com.dave.framegrabber

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dave.framegrabber.telegram.PhotoPrep
import com.dave.framegrabber.telegram.PostCaption
import com.dave.framegrabber.telegram.TelegramApi
import com.dave.framegrabber.telegram.TgException
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import kotlin.concurrent.thread

/** Telegram posting without the real Telegram: caption shape, photo prep, and the HTTP request (fake local server). */
@RunWith(AndroidJUnit4::class)
class TelegramTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun caption_shape() {
        val c = PostCaption.build("Studio name", listOf("Model A", "Model B"), listOf("tag1", "tag2"))
        assertEquals("Studio name - Model A & Model B\n------------------------------\n#tag1 #tag2", c)
        assertEquals("Studio - Solo\n------------------------------",
            PostCaption.build(" Studio ", listOf(" Solo "), emptyList()))
    }

    @Test fun hashtag_rules() {
        assertEquals("big_studio", PostCaption.normalizeTag("big studio"))
        assertEquals("Sci_Fi", PostCaption.normalizeTag("#Sci-Fi"))
        assertEquals("Ереван2026", PostCaption.normalizeTag("#Ереван2026"))
        assertEquals("Երևան", PostCaption.normalizeTag("Երևան"))
        assertNull(PostCaption.normalizeTag("###"))
        assertNull(PostCaption.normalizeTag("2026"))
    }

    @Test fun photo_prep_makes_small_jpeg() {
        val big = Bitmap.createBitmap(3840, 2160, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(20, 140, 80)) }
        val f = File(ctx.cacheDir, "big.png")
        f.outputStream().use { big.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val jpg = PhotoPrep.jpeg(f)
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpg, 0, jpg.size, o)
        assertEquals("image/jpeg", o.outMimeType)
        assertEquals(2560, o.outWidth)
        assertEquals(1440, o.outHeight)
        assertTrue(jpg.size < 10_000_000)
    }

    /** One-shot HTTP server on 127.0.0.1 that answers [reply] and hands back the raw request. */
    private fun fakeTelegram(reply: String, onRequest: (String, ByteArray) -> Unit): Int {
        val ss = ServerSocket(0)
        thread {
            ss.accept().use { sock ->
                val inp = sock.getInputStream()
                val head = ByteArrayOutputStream()
                // headers end with \r\n\r\n
                var last4 = 0
                while (true) {
                    val b = inp.read(); if (b < 0) break
                    head.write(b)
                    last4 = (last4 shl 8) or b
                    if (last4 == 0x0D0A0D0A) break
                }
                val headers = head.toString("UTF-8")
                val len = Regex("Content-Length: (\\d+)", RegexOption.IGNORE_CASE).find(headers)?.groupValues?.get(1)?.toInt() ?: 0
                val body = ByteArray(len)
                var off = 0
                while (off < len) { val n = inp.read(body, off, len - off); if (n < 0) break; off += n }
                onRequest(headers, body)
                val r = reply.toByteArray()
                sock.getOutputStream().apply {
                    write("HTTP/1.1 ${if (reply.contains("\"ok\":true")) "200 OK" else "400 Bad Request"}\r\nContent-Type: application/json\r\nContent-Length: ${r.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(r); flush()
                }
            }
            ss.close()
        }
        return ss.localPort
    }

    @Test fun album_request() {
        var headers = ""; var body = ByteArray(0)
        val port = fakeTelegram("""{"ok":true,"result":[{"message_id":77},{"message_id":78}]}""") { h, b -> headers = h; body = b }
        val api = TelegramApi("123:ABC", "http://127.0.0.1:$port")
        val caption = PostCaption.build("Студия", listOf("Модель A", "B"), listOf("тег", "tag_2"))
        val photos = List(6) { i -> byteArrayOf(0xFF.toByte(), 0xD8.toByte(), i.toByte()) }
        val id = api.sendAlbum("@chan", photos, caption)
        assertEquals(77L, id)
        assertTrue(headers, headers.startsWith("POST /bot123:ABC/sendMediaGroup"))
        val text = String(body, Charsets.UTF_8)
        // 6 photos attached
        repeat(6) { i -> assertTrue("p$i", text.contains("name=\"p$i\"; filename=\"photo_$i.jpg\"")) }
        // media JSON: caption only on the first, exactly as built, no parse mode
        val media = Regex("name=\"media\"\r\nContent-Type: text/plain; charset=utf-8\r\n\r\n(.*?)\r\n").find(text)!!.groupValues[1]
        val arr = JSONArray(media)
        assertEquals(6, arr.length())
        assertEquals(caption, arr.getJSONObject(0).getString("caption"))
        assertTrue(!arr.getJSONObject(0).has("parse_mode"))
        assertTrue((1 until 6).none { arr.getJSONObject(it).has("caption") })
        assertEquals("attach://p3", arr.getJSONObject(3).getString("media"))
        assertTrue(text.contains("name=\"chat_id\"\r\nContent-Type: text/plain; charset=utf-8\r\n\r\n@chan\r\n"))
    }

    @Test fun errors_in_plain_words() {
        val port = fakeTelegram("""{"ok":false,"error_code":400,"description":"Bad Request: chat not found"}""") { _, _ -> }
        try {
            TelegramApi("1:x", "http://127.0.0.1:$port").getChat("@nope")
            fail("should throw")
        } catch (e: TgException) {
            assertTrue(e.message!!, e.message!!.contains("Канал не найден"))
        }
    }
}
