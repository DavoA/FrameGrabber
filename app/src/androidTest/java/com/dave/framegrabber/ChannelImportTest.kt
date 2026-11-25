package com.dave.framegrabber

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dave.framegrabber.telegram.ChannelImport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Reading a public channel page: captions, the page format (fixture), and the real t.me (if online). */
@RunWith(AndroidJUnit4::class)
class ChannelImportTest {

    @Test fun captions() {
        val (s, m, t) = ChannelImport.parseCaption("Studio name - Model A & Model B\n————————————————\n#натуральные #hd")
        assertEquals("Studio name", s); assertEquals(listOf("Model A", "Model B"), m); assertEquals(listOf("натуральные", "hd"), t)
        val (s2, m2, _) = ChannelImport.parseCaption("Brazzers – Anna\n----\n#a")
        assertEquals("Brazzers", s2); assertEquals(listOf("Anna"), m2)
        val (s3, m3, t3) = ChannelImport.parseCaption("Просто текст без шаблона #тег")
        assertEquals("", s3); assertTrue(m3.isEmpty()); assertEquals(listOf("тег"), t3)
        assertEquals("my_channel", ChannelImport.channelName("https://t.me/s/my_channel"))
        assertEquals("my_channel", ChannelImport.channelName("@my_channel"))
    }

    @Test fun page_format() {
        val html = """
            <div class="tgme_channel_info"></div>
            <div class="tgme_widget_message_wrap js-widget_message_wrap"><div class="tgme_widget_message text_not_supported_wrap js-widget_message" data-post="mych/101" data-view="x">
              <div class="tgme_widget_message_grouped_wrap"></div>
              <div class="tgme_widget_message_text js-message_text" dir="auto">Studio &amp; Co - Model A &amp; Model B<br/>————————————————<br/><a href="?q=%23hd">#hd</a> <a href="?q=%23tag_2">#tag_2</a></div>
              <a class="tgme_widget_message_date" href="https://t.me/mych/101"><time datetime="2026-11-20T10:15:00+00:00" class="time">10:15</time></a>
            </div></div>
            <div class="tgme_widget_message_wrap"><div class="tgme_widget_message js-widget_message" data-post="mych/107">
              <div class="tgme_widget_message_video_wrap"></div>
              <a class="tgme_widget_message_date" href="https://t.me/mych/107"><time datetime="2026-11-20T10:16:00+00:00">10:16</time></a>
            </div></div>
        """.trimIndent()
        val msgs = ChannelImport.parse(html)
        assertEquals(listOf(101L, 107L), msgs.map { it.id })
        assertEquals("Studio & Co - Model A & Model B\n————————————————\n#hd #tag_2", msgs[0].text)
        assertEquals("", msgs[1].text)
        val posts = ChannelImport.toPosts("mych", msgs)
        assertEquals(1, posts.size)   // the textless video is not a post
        assertEquals("Studio & Co", posts[0].studio)
        assertEquals(listOf("Model A", "Model B"), posts[0].models)
        assertEquals(listOf("hd", "tag_2"), posts[0].tags)
        assertEquals("https://t.me/mych/101", posts[0].link)
    }

    /** The real page of a big public channel: checks the format assumptions against today's t.me. */
    @Test fun real_public_channel() {
        val r = runCatching {
            var pages = 0
            ChannelImport("telegram").readAll(0) { _, _ -> ++pages < 3 }
        }
        val e = r.exceptionOrNull()
        Log.i("FrameGrabber", "LIVE t.me: ${r.getOrNull()?.size} msgs, error=$e")
        assumeTrue("no internet on this emulator: $e", e !is java.net.UnknownHostException && e !is java.net.ConnectException && e !is java.net.SocketTimeoutException)
        val msgs = r.getOrThrow()
        assertTrue("messages: ${msgs.size}", msgs.size >= 30)
        assertTrue("ids decrease over 3 pages", msgs.first().id < msgs.last().id)
        assertTrue("dates", msgs.all { it.time > 1_400_000_000_000L })
        assertTrue("texts", msgs.count { it.text.length > 20 } >= 10)
        Log.i("FrameGrabber", "LIVE sample: " + msgs.last().text.take(200).replace('\n', '|'))
    }
}
