package com.dave.framegrabber

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dave.framegrabber.site.Lab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Tracker rows and torrent pages in TorrentPier markup → the app's data. */
@RunWith(AndroidJUnit4::class)
class SiteParseTest {

    private val tracker = """
<table class="forumline tablesorter" id="tor-tbl"><thead><tr><th>x</th></tr></thead><tbody>
<tr class="tCenter hl-tr" id="tor_101">
 <td class="row1"><img src="x.gif"></td>
 <td class="row1 f-name"><a class="gen f" href="tracker.php?f=1695">Клипы &amp; ролики</a></td>
 <td class="row4 med tLeft"><div><a class="med tLink bold" href="./viewtopic.php?t=101"><b>Blonde</b> Video 2026 [1080p]</a></div></td>
 <td class="row1"><a class="med" href="profile.php?u=5">user</a></td>
 <td class="row4 small nowrap"><u>2147483648</u><a class="small tr-dl dl-stub" href="dl.php?t=101">2&nbsp;GB&nbsp;↓</a></td>
 <td class="row4 seedmed" title="Сиды"><b>15</b></td>
 <td class="row4 leechmed" title="Личи"><b>3</b></td>
 <td class="row4 small">99</td>
 <td class="row4 small nowrap"><u>1764000000</u><p>24-Ноя-25</p></td>
</tr>
<tr class="tCenter hl-tr" id="tor_102">
 <td class="row4 med tLeft"><a class="med tLink" href="viewtopic.php?t=102">Second</a></td>
 <td class="row4 small nowrap" data-ts_text="1048576"><a class="small tr-dl" href="dl.php?t=102">1 MB</a></td>
 <td class="row4 nowrap" data-ts_text="0"><b class="seedmed">0</b></td>
 <td class="row4 leechmed">7</td>
 <td class="row4 small nowrap" data-ts_text="1700000000"><p>14-Ноя-23</p></td>
</tr>
</tbody></table>
<p>Результатов поиска: 2</p>"""

    private val topic = """
<h1 class="maintitle"><a id="topic-title" href="./viewtopic.php?t=101">Blonde Video 2026 [1080p]</a></h1>
<table><tr><td class="message td2">
<div class="post_body" id="p-555">
 <div class="post-user-message">
  <var class="postImg postImgAligned img-right" title="https://i.host/poster.jpg">&#10;</var>
  <span class="post-b">Описание</span>: что-то<span class="post-br"><br></span>
  Продолжительность: 00:30:00
  <div class="sp-wrap"><div class="sp-title">Скриншоты</div><div class="sp-body">
   <a href="https://i.host/big1.jpg" class="postLink"><var class="postImg" title="https://i.host/t1.jpg">&#10;</var></a>
   <a href="https://fastpic.org/view/1" class="postLink"><var class="postImg" title="https://i1.fastpic.org/thumb/2026/a.jpeg">&#10;</var></a>
  </div></div>
 </div>
</div><!--/post_body-->
</td></tr></table>
<table class="attach"><tr><td>Размер: <span id="tor-size-humn">2&nbsp;GB</span></td></tr>
<tr><td><a href="dl.php?t=101" class="dl-stub dl-link">Скачать .torrent</a>
<a href="magnet:?xt=urn:btih:ABC&amp;tr=x" class="magnet-link">magnet</a></td></tr>
<tr><td><span class="seed">Сиды: <b>15</b></span> <span class="leech">Личи: <b>3</b></span></td></tr></table>
<div class="post_body" id="p-556">второй пост <var class="postImg" title="https://i.host/other.jpg"></var></div>
"""

    @Test fun rows() {
        val rows = Lab.parseRows(tracker)
        assertEquals(2, rows.size)
        val a = rows[0]
        assertEquals(101L, a.id)
        assertEquals("Blonde Video 2026 [1080p]", a.title)
        assertEquals("Клипы & ролики", a.forum)
        assertEquals(2147483648L, a.size)
        assertEquals("2 GB", a.sizeText)
        assertEquals(15, a.seeds); assertEquals(3, a.leech)
        assertEquals(1764000000L, a.date)
        val b = rows[1]
        assertEquals(102L, b.id); assertEquals(1048576L, b.size); assertEquals(0, b.seeds); assertEquals(7, b.leech)
        assertEquals(1700000000L, b.date)
    }

    @Test fun topicPage() {
        val t = Lab.parseTopic(101, topic)
        assertEquals("Blonde Video 2026 [1080p]", t.title)
        assertEquals("https://i.host/poster.jpg", t.poster?.thumb)
        assertEquals(2, t.images.size)
        assertEquals("https://i.host/big1.jpg", t.images[0].full)
        assertEquals("https://i1.fastpic.org/big/2026/a.jpeg", t.images[1].full)
        assertEquals("2 GB", t.sizeText)
        assertEquals(15, t.seeds); assertEquals(3, t.leech)
        assertEquals("magnet:?xt=urn:btih:ABC&tr=x", t.magnet)
        assertEquals("https://pornolab.net/forum/dl.php?t=101", t.torrent)
        assertTrue(t.text, "Продолжительность: 00:30:00" in t.text && "▸ Скриншоты" in t.text && "второй пост" !in t.text)
    }
}
