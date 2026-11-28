package com.dave.framegrabber.tgclient

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.dave.framegrabber.MainActivity
import com.dave.framegrabber.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * «Очередь постов»: posts composed in advance (6 photos, caption, the video) that go to the
 * channel one after another, right away, through [TgPublish]. A big video is cut into parts there
 * as usual. A post that fails is skipped; at the end the queue says which ones didn't go.
 *
 * Kept on disk (filesDir/postqueue): closing the app or restarting the phone doesn't lose it —
 * it goes on the next time the app runs. The 6 photos of each post are copied there, so deleting
 * the frames meanwhile doesn't break it.
 */
object PostQueue {

    enum class State { WAITING, SENDING, DONE, FAILED }

    class Item(
        val id: String,
        val title: String,            // «Студия - Модель» for the lists
        val videoName: String,
        val photos: List<String>,     // copied files
        val caption: String,
        val video: String?,           // uri, null = photos only
        var state: State = State.WAITING,
        var error: String? = null,
        var result: String? = null,
    )

    private val main = Handler(Looper.getMainLooper())
    val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    private var items: MutableList<Item>? = null
    private var hooked = false
    private lateinit var app: Context

    private fun dir(ctx: Context) = File(ctx.filesDir, "postqueue").apply { mkdirs() }
    private fun file(ctx: Context) = File(dir(ctx), "queue.json")

    @Synchronized
    fun items(ctx: Context): MutableList<Item> {
        items?.let { return it }
        val l = mutableListOf<Item>()
        runCatching {
            val a = JSONArray(file(ctx).readText())
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val ph = o.getJSONArray("photos")
                l += Item(
                    o.getString("id"), o.optString("title"), o.optString("vname"),
                    (0 until ph.length()).map { ph.getString(it) }, o.optString("caption"),
                    o.optString("video").ifEmpty { null },
                    runCatching { State.valueOf(o.optString("state")) }.getOrDefault(State.WAITING),
                    o.optString("error").ifEmpty { null }, o.optString("result").ifEmpty { null },
                )
            }
        }
        // the app was closed in the middle of a post: it goes again
        l.forEach { if (it.state == State.SENDING && !TgPublish.running) it.state = State.WAITING }
        items = l
        return l
    }

    @Synchronized
    private fun save(ctx: Context) {
        val a = JSONArray()
        items(ctx).forEach { it ->
            a.put(JSONObject().put("id", it.id).put("title", it.title).put("vname", it.videoName)
                .put("photos", JSONArray(it.photos)).put("caption", it.caption).put("video", it.video ?: "")
                .put("state", it.state.name).put("error", it.error ?: "").put("result", it.result ?: ""))
        }
        val f = file(ctx); val tmp = File(f.path + ".tmp")
        tmp.writeText(a.toString()); tmp.renameTo(f)
    }

    private fun changed(ctx: Context) { save(ctx); main.post { listeners.forEach { it() } } }

    // ---------------------------------------------------------------- what the screens show

    fun active(ctx: Context) = items(ctx).any { it.state == State.WAITING || it.state == State.SENDING }
    fun current(ctx: Context) = items(ctx).firstOrNull { it.state == State.SENDING }

    /** «Очередь: пост 2 из 3» while it runs, else null. */
    fun label(ctx: Context): String? {
        val l = items(ctx)
        val cur = l.indexOfFirst { it.state == State.SENDING }
        if (cur < 0) return null
        return "Очередь: пост ${cur + 1} из ${l.size}"
    }

    // ---------------------------------------------------------------- changing it

    /** Adds composed posts to the end (photos copied now) and starts if idle. */
    fun add(ctx: Context, posts: List<Item>) {
        hook(ctx)
        val l = items(ctx)
        // a finished queue is cleared when a new one starts
        if (l.none { it.state == State.WAITING || it.state == State.SENDING }) {
            l.forEach { File(dir(ctx), it.id).deleteRecursively() }
            l.clear()
        }
        posts.forEach { p ->
            val d = File(dir(ctx), p.id).apply { mkdirs() }
            val copies = p.photos.mapIndexed { i, path ->
                val dst = File(d, "photo$i." + File(path).extension.ifEmpty { "jpg" })
                File(path).copyTo(dst, overwrite = true); dst.path
            }
            l += Item(p.id, p.title, p.videoName, copies, p.caption, p.video)
        }
        changed(ctx)
        next(ctx)
    }

    fun remove(ctx: Context, id: String) {
        val l = items(ctx)
        val it = l.firstOrNull { x -> x.id == id } ?: return
        if (it.state == State.SENDING) return
        l.remove(it); File(dir(ctx), id).deleteRecursively()
        changed(ctx)
    }

    /** Stops the queue: the post being sent is cancelled, the waiting ones are dropped. */
    fun stopAll(ctx: Context) {
        val l = items(ctx)
        l.filter { it.state == State.WAITING }.forEach { l.remove(it); File(dir(ctx), it.id).deleteRecursively() }
        changed(ctx)
        if (current(ctx) != null && TgPublish.running) TgPublish.cancel()
    }

    /** The posts that didn't go, once more (at the end of the queue). */
    fun retryFailed(ctx: Context) {
        val l = items(ctx)
        val failed = l.filter { it.state == State.FAILED }
        if (failed.isEmpty()) return
        l.removeAll(failed)
        failed.forEach { it.state = State.WAITING; it.error = null; l += it }
        changed(ctx)
        next(ctx)
    }

    /** Clears a finished queue (nothing waiting). */
    fun clearFinished(ctx: Context) {
        val l = items(ctx)
        val done = l.filter { it.state == State.DONE || it.state == State.FAILED }
        done.forEach { File(dir(ctx), it.id).deleteRecursively() }
        l.removeAll(done)
        changed(ctx)
    }

    // ---------------------------------------------------------------- running it

    /** At the start of the app: hooks up and goes on with what was left. */
    fun resume(ctx: Context) {
        hook(ctx)
        if (!active(ctx)) return
        Tg.start(ctx.applicationContext)
        waitReadyThenNext(ctx, 0)
    }

    private fun waitReadyThenNext(ctx: Context, tries: Int) {
        if (Tg.state == Tg.State.READY) { next(ctx); return }
        if (tries > 90) return   // not logged in: it waits until the next start of the app
        main.postDelayed({ waitReadyThenNext(ctx, tries + 1) }, 1000)
    }

    private fun hook(ctx: Context) {
        if (hooked) return
        hooked = true
        app = ctx.applicationContext
        TgPublish.listeners += { onPublishChanged() }
    }

    private fun onPublishChanged() {
        if (TgPublish.running) { main.post { listeners.forEach { it() } }; return }
        val cur = current(app) ?: run { next(app); return }
        val err = TgPublish.error
        if (err == null) { cur.state = State.DONE; cur.result = TgPublish.doneText }
        else { cur.state = State.FAILED; cur.error = err }
        File(dir(app), cur.id).takeIf { cur.state == State.DONE }?.deleteRecursively()
        changed(app)
        // small pause: lets the last post settle in the channel before the next one starts
        main.postDelayed({ next(app, report = true) }, 1500)
    }

    @Synchronized
    private fun next(ctx: Context, report: Boolean = false) {
        if (TgPublish.running) return
        val l = items(ctx)
        if (l.any { it.state == State.SENDING }) return
        val n = l.firstOrNull { it.state == State.WAITING }
        if (n == null) { if (report && l.isNotEmpty()) finished(ctx, l); return }
        if (Tg.state != Tg.State.READY) { waitReadyThenNext(ctx, 0); return }
        val channel = com.dave.framegrabber.telegram.TgStore(ctx).chatUsername.removePrefix("@").ifBlank { TgKeys(ctx).feedChannel }
        val photos = n.photos.map(::File)
        if (channel.isBlank() || photos.any { !it.exists() }) {
            n.state = State.FAILED
            n.error = if (channel.isBlank()) "Не знаю @имя канала" else "Фото поста пропали"
            changed(ctx); next(ctx, report); return
        }
        n.state = State.SENDING
        changed(ctx)
        TgPublish.start(ctx, TgPublish.Request(channel, photos, n.caption, n.video?.let(Uri::parse), n.videoName, 0))
    }

    private var lastReport = ""

    /** Everything went: one notification with how it went (once per queue). */
    private fun finished(ctx: Context, l: List<Item>) {
        val key = l.joinToString { it.id + it.state }
        if (key == lastReport) return
        lastReport = key
        val failed = l.filter { it.state == State.FAILED }
        val ok = l.count { it.state == State.DONE }
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val text = if (failed.isEmpty()) "Все посты в канале: $ok"
            else "Ушло $ok из ${l.size}. Не ушли: " + failed.joinToString("; ") { it.title }
        runCatching {
            nm.notify(4711, android.app.Notification.Builder(ctx, "tgpub")
                .setSmallIcon(R.drawable.ic_send)
                .setContentTitle(if (failed.isEmpty()) "Очередь постов готова ✓" else "Очередь постов: не все ушли")
                .setContentText(text)
                .setStyle(android.app.Notification.BigTextStyle().bigText(text))
                .setContentIntent(PendingIntent.getActivity(ctx, 3,
                    Intent(ctx, com.dave.framegrabber.ui.telegram.QueueActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
                .setAutoCancel(true).build())
        }
    }
}
