package com.dave.framegrabber

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dave.framegrabber.torrent.TorrentEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/** The built-in torrent engine loads (native libtorrent), reads a .torrent, starts and removes a download. */
@RunWith(AndroidJUnit4::class)
class TorrentTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    /** A tiny single-file .torrent, bencoded by hand. */
    private fun torrent(): ByteArray {
        val o = ByteArrayOutputStream()
        fun s(x: String) = o.write("${x.toByteArray().size}:$x".toByteArray())
        o.write("d".toByteArray())
        s("info"); o.write("d".toByteArray())
        s("length"); o.write("i100000e".toByteArray())
        s("name"); s("clip test.mp4")
        s("piece length"); o.write("i65536e".toByteArray())
        s("pieces"); o.write("40:".toByteArray()); o.write(ByteArray(40) { 7 })
        o.write("e".toByteArray())
        o.write("e".toByteArray())
        return o.toByteArray()
    }

    @Test fun engine() {
        val bytes = torrent()
        val (name, files) = TorrentEngine.files(bytes)
        assertEquals("clip test.mp4", name)
        assertEquals(1, files.size)
        assertEquals(100000L, files[0].third)
        assertTrue(TorrentEngine.isVideo(files[0].second))
        val job = TorrentEngine.add(ctx, bytes, "Тест", listOf(0))
        Thread.sleep(3000)
        assertTrue(TorrentEngine.jobs.any { it.hash == job.hash })
        assertEquals(100000L, job.wanted)
        TorrentEngine.remove(job.hash, deleteFiles = true)
        assertTrue(TorrentEngine.jobs.none { it.hash == job.hash })
    }
}
