package com.dave.framegrabber.torrent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.dave.framegrabber.MainActivity
import com.dave.framegrabber.R
import com.dave.framegrabber.player.Library
import org.json.JSONArray
import org.json.JSONObject
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.TorrentStatus
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Torrents downloaded by the app itself (libtorrent). Only the chosen files (videos by default)
 * are downloaded, into the app's Movies/Torrents folder, which «Плеер» shows. Downloads go on
 * in the background (a notification with progress), survive restarts and can be paused.
 */
object TorrentEngine {

    class Job(
        val hash: String, val title: String, val name: String,
        val selected: List<Int>, val wanted: Long,
    ) {
        @Volatile var progress = 0f
        @Volatile var done = 0L
        @Volatile var rate = 0
        @Volatile var peers = 0
        @Volatile var seeds = 0
        @Volatile var paused = false
        @Volatile var finished = false
        @Volatile var state = "Подключаюсь…"
        @Volatile var error: String? = null
        /** libtorrent's id of the torrent (set when it starts). */
        @Volatile var sha: org.libtorrent4j.Sha1Hash? = null
        /** The downloaded files (chosen ones), when finished. */
        val files = ArrayList<File>()
    }

    val jobs = CopyOnWriteArrayList<Job>()
    val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private var sm: SessionManager? = null
    private var poller: Thread? = null
    private lateinit var app: Context

    /** Where torrents are saved (shown in «Плеер»). */
    fun dir(ctx: Context) = File(ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "Torrents").apply { mkdirs() }
    private fun store(ctx: Context) = File(ctx.filesDir, "torrents").apply { mkdirs() }

    fun isVideo(name: String) = name.substringAfterLast('.', "").lowercase() in
        setOf("mp4", "mkv", "avi", "mov", "m4v", "wmv", "ts", "m2ts", "webm", "flv", "mpg", "mpeg", "3gp")

    // ================================================================ session

    @Synchronized private fun session(ctx: Context): SessionManager {
        app = ctx.applicationContext
        sm?.let { return it }
        val s = SessionManager(false)
        s.start()
        sm = s
        poller = Thread({
            while (true) {
                try { Thread.sleep(1000) } catch (e: InterruptedException) { break }
                runCatching { poll() }
            }
        }, "torrents").apply { isDaemon = true; start() }
        return s
    }

    /** Restores unfinished downloads (at app start). */
    fun restore(ctx: Context) {
        app = ctx.applicationContext
        val list = load(ctx)
        if (list.isEmpty()) return
        list.forEach { o ->
            val hash = o.getString("h")
            if (jobs.any { it.hash == hash }) return@forEach
            val bytes = File(store(ctx), "$hash.torrent").takeIf { it.exists() }?.readBytes() ?: return@forEach
            val sel = o.getJSONArray("sel").let { a -> List(a.length()) { a.getInt(it) } }
            val job = Job(hash, o.optString("t"), o.optString("n"), sel, o.optLong("w"))
            job.paused = o.optBoolean("p"); job.finished = o.optBoolean("f")
            jobs += job
            if (job.finished) { job.state = "Готово"; collectFiles(job, TorrentInfo(bytes)); return@forEach }
            runCatching { begin(ctx, job, bytes) }.onFailure { job.error = it.message }
        }
        changed()
        if (jobs.any { !it.finished && !it.paused }) ctx.startForegroundService(Intent(ctx, TorrentService::class.java))
    }

    /** A magnet link → the .torrent (asks the peers for it; blocking, up to ~40 s). */
    fun magnetBytes(ctx: Context, uri: String): ByteArray? =
        session(ctx).fetchMagnet(uri, 40, File(store(ctx), "magnet").apply { mkdirs() })

    /** Files of a .torrent: (index, path, size). */
    fun files(bytes: ByteArray): Pair<String, List<Triple<Int, String, Long>>> {
        val ti = TorrentInfo(bytes)
        val fs = ti.files()
        return ti.name() to List(fs.numFiles()) { i -> Triple(i, fs.filePath(i), fs.fileSize(i)) }
    }

    /** Starts downloading the [selected] files of a .torrent. */
    fun add(ctx: Context, bytes: ByteArray, title: String, selected: List<Int>): Job {
        val ti = TorrentInfo(bytes)
        val hash = ti.infoHash().toHex()
        jobs.firstOrNull { it.hash == hash }?.let { return it }
        File(store(ctx), "$hash.torrent").writeBytes(bytes)
        val fs = ti.files()
        val job = Job(hash, title, ti.name(), selected, selected.sumOf { fs.fileSize(it) })
        jobs.add(0, job)
        save(ctx)
        begin(ctx, job, bytes)
        changed()
        ctx.startForegroundService(Intent(ctx, TorrentService::class.java))
        return job
    }

    private fun begin(ctx: Context, job: Job, bytes: ByteArray) {
        val s = session(ctx)
        val ti = TorrentInfo(bytes)
        job.sha = ti.infoHash()
        val pr = Array(ti.numFiles()) { if (it in job.selected) Priority.DEFAULT else Priority.IGNORE }
        s.download(ti, dir(ctx), null, pr, null, org.libtorrent4j.TorrentFlags.AUTO_MANAGED)
        if (job.paused) handle(job)?.pause()
        // «Плеер» shows the torrents folder
        Library(ctx).addFolder(dir(ctx).path)
    }

    private fun handle(job: Job): TorrentHandle? = job.sha?.let { sm?.find(it) }?.takeIf { it.isValid }

    fun pause(hash: String) {
        val j = jobs.firstOrNull { it.hash == hash } ?: return
        j.paused = true; j.state = "Пауза"
        handle(j)?.let { it.unsetFlags(org.libtorrent4j.TorrentFlags.AUTO_MANAGED); it.pause() }
        save(app); changed()
    }

    fun resume(hash: String) {
        val j = jobs.firstOrNull { it.hash == hash } ?: return
        j.paused = false; j.state = "Подключаюсь…"
        val h = handle(j)
        if (h != null) { h.setFlags(org.libtorrent4j.TorrentFlags.AUTO_MANAGED); h.resume() }
        else File(store(app), "${j.hash}.torrent").takeIf { it.exists() }?.let { runCatching { begin(app, j, it.readBytes()) } }
        save(app); changed()
        app.startForegroundService(Intent(app, TorrentService::class.java))
    }

    /** Stops and forgets; [deleteFiles] also removes what was downloaded. */
    fun remove(hash: String, deleteFiles: Boolean) {
        val j = jobs.firstOrNull { it.hash == hash } ?: return
        handle(j)?.let { h ->
            if (deleteFiles) sm?.remove(h, org.libtorrent4j.SessionHandle.DELETE_FILES) else sm?.remove(h)
        }
        if (deleteFiles) File(dir(app), j.name).let { if (it.isDirectory) it.deleteRecursively() else it.delete() }
        jobs.remove(j)
        File(store(app), "$hash.torrent").delete()
        save(app); changed()
    }

    // ================================================================ progress

    private fun poll() {
        var any = false
        for (j in jobs) {
            if (j.finished) continue
            val h = handle(j) ?: continue
            val st = h.status()
            j.done = st.totalWantedDone(); j.progress = st.progress(); j.rate = st.downloadPayloadRate()
            j.peers = st.numPeers(); j.seeds = st.numSeeds()
            if (!j.paused) j.state = when (st.state()) {
                TorrentStatus.State.CHECKING_FILES, TorrentStatus.State.CHECKING_RESUME_DATA -> "Проверяю файлы…"
                TorrentStatus.State.DOWNLOADING_METADATA -> "Получаю данные…"
                TorrentStatus.State.DOWNLOADING -> if (st.numPeers() == 0) "Ищу раздающих…" else "Качаю"
                TorrentStatus.State.FINISHED, TorrentStatus.State.SEEDING -> "Готово"
                else -> j.state
            }
            if (st.isFinished || (j.wanted > 0 && st.totalWantedDone() >= j.wanted)) {
                j.finished = true; j.state = "Готово"; j.progress = 1f
                h.torrentFile()?.let { collectFiles(j, it) }
                // done: stop sharing (the app isn't a seeding box; saves battery and traffic)
                h.unsetFlags(org.libtorrent4j.TorrentFlags.AUTO_MANAGED); h.pause()
                save(app)
                TorrentService.finished(app, j)
            }
            any = true
        }
        if (any) changed()
    }

    private fun collectFiles(j: Job, ti: TorrentInfo) {
        j.files.clear()
        val fs = ti.files()
        j.selected.forEach { i -> File(dir(app), fs.filePath(i)).takeIf { it.exists() }?.let { j.files += it } }
    }

    val running get() = jobs.any { !it.finished && !it.paused && it.error == null }

    private fun changed() = main.post { listeners.forEach { it() }; TorrentService.update() }

    // ================================================================ saved list

    private fun file(ctx: Context) = File(store(ctx), "jobs.json")

    private fun load(ctx: Context): List<JSONObject> = runCatching {
        val a = JSONArray(file(ctx).readText()); List(a.length()) { a.getJSONObject(it) }
    }.getOrDefault(emptyList())

    @Synchronized private fun save(ctx: Context) {
        val a = JSONArray()
        jobs.forEach { j ->
            a.put(JSONObject().put("h", j.hash).put("t", j.title).put("n", j.name).put("w", j.wanted)
                .put("sel", JSONArray(j.selected)).put("p", j.paused).put("f", j.finished))
        }
        file(ctx).writeText(a.toString())
    }

    fun size(b: Long): String = com.dave.framegrabber.site.Lab.size(b).ifBlank { "0" }
}

/** Keeps torrent downloads going in the background, with progress in a notification. */
class TorrentService : Service() {
    companion object {
        private const val CHANNEL = "torrents"
        private const val ID = 73
        private const val ACTION_PAUSE = "com.dave.framegrabber.PAUSE_TORRENTS"
        @Volatile private var instance: TorrentService? = null
        fun update() { instance?.refresh() }

        fun finished(ctx: Context, j: TorrentEngine.Job) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Торренты", NotificationManager.IMPORTANCE_LOW))
            nm.notify(9500 + (j.hash.hashCode() % 400), Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_play)
                .setContentTitle("Скачано ✓")
                .setContentText(j.title.ifBlank { j.name } + " — в «Плеере»")
                .setContentIntent(PendingIntent.getActivity(ctx, 3, Intent(ctx, com.dave.framegrabber.ui.site.TorrentsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE))
                .setAutoCancel(true).build())
        }
    }

    private lateinit var nm: NotificationManager
    private var last = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Торренты", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onDestroy() { instance = null; super.onDestroy() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE) TorrentEngine.jobs.filter { !it.finished }.forEach { TorrentEngine.pause(it.hash) }
        startForeground(ID, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        refresh()
        return START_STICKY
    }

    fun refresh() {
        if (!TorrentEngine.running) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return }
        val now = System.currentTimeMillis()
        if (now - last < 1500) return
        last = now
        nm.notify(ID, build())
    }

    private fun build(): Notification {
        val act = TorrentEngine.jobs.filter { !it.finished && !it.paused }
        val done = act.sumOf { it.done }; val total = act.sumOf { it.wanted }.coerceAtLeast(1)
        val rate = act.sumOf { it.rate.toLong() }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_web)
            .setContentTitle(if (act.size == 1) act[0].title.ifBlank { act[0].name } else "Торренты: ${act.size}")
            .setContentText("${(done * 100 / total)}% · ${TorrentEngine.size(done)} из ${TorrentEngine.size(total)} · ${TorrentEngine.size(rate)}/с")
            .setProgress(1000, (done * 1000 / total).toInt(), false)
            .setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(PendingIntent.getActivity(this, 2, Intent(this, com.dave.framegrabber.ui.site.TorrentsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE))
            .addAction(Notification.Action.Builder(null, "Пауза",
                PendingIntent.getService(this, 4, Intent(this, TorrentService::class.java).setAction(ACTION_PAUSE), PendingIntent.FLAG_IMMUTABLE)).build())
            .build()
    }
}
