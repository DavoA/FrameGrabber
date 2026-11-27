package com.dave.framegrabber

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dave.framegrabber.data.Updater
import com.dave.framegrabber.tgclient.FeedFavs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Updates from GitHub and the feed's «Любимые». */
@RunWith(AndroidJUnit4::class)
class UpdaterTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun versions() {
        assertTrue(Updater.newer("3.12", "3.9"))
        assertTrue(Updater.newer("3.1", "3"))
        assertFalse(Updater.newer("3.11", "3.11"))
        assertFalse(Updater.newer("3.9", "3.10"))
    }

    @Test fun latestOnGithub() {
        val i = Updater.fetch()
        android.util.Log.i("UpdaterTest", "latest ${i.version} ${i.size} ${i.url}")
        assertTrue(i.version.matches(Regex("""\d+(\.\d+)*""")))
        assertTrue(i.url.endsWith(".apk"))
        assertTrue(i.size > 1_000_000)
    }

    @Test fun favorites() {
        val f = FeedFavs(ctx)
        val chat = -42L
        f.list(chat).forEach { f.toggle(chat, it.ids) }
        assertTrue(f.toggle(chat, listOf(10L, 11L, 12L)))
        assertTrue(f.toggle(chat, listOf(20L)))
        assertTrue(f.isFav(chat, listOf(11L)))
        assertEquals(2, f.count(chat))
        f.keepOnly(chat, setOf(20L))
        assertEquals(1, f.count(chat))
        assertFalse(f.toggle(chat, listOf(20L)))
        assertEquals(0, f.count(chat))
    }
}
