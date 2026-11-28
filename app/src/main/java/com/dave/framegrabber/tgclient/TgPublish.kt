package com.dave.framegrabber.tgclient

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import com.dave.framegrabber.MainActivity
import com.dave.framegrabber.R
import com.dave.framegrabber.export.Exporters
import com.dave.framegrabber.split.SplitPlan
import com.dave.framegrabber.telegram.PhotoPrep
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Publishing a whole post through YOUR account (TDLib), so it can do what the bot can't:
 * the 6 photos with the caption, then the video right after them — cut into «Part 1, Part 2…»
 * when it is bigger than Telegram allows (2 GB, 4 GB with Premium) — and/or at a chosen time
 * (Telegram keeps the scheduled post and publishes it by itself, even with the phone off).
 * Runs in a foreground service with progress in a notification.
 */
object TgPublish {

    class Request(
        val channel: String,              // @name of the channel (without @)
        val photos: List<File>,
        val caption: String,
        val video: Uri?,                  // null = photos only
        val videoName: String,
        val sendAt: Int,                  // unix time, 0 = now
    )

    @Volatile var running = false; private set
    @Volatile var stage = ""; private set
    @Volatile var fraction = 0f; private set
    @Volatile var error: String? = null; private set
    /** Upload: bytes sent / all bytes, seconds left (-1 = not known yet), what is being sent. */
    @Volatile var bytesDone = 0L; private set
    @Volatile var bytesTotal = 0L; private set
    @Volatile var etaSec = -1L; private set
    @Volatile var what = ""; private set
    /** (time, bytes) of the last ~20 s: the speed for «осталось». */
    private val samples = java.util.ArrayDeque<Pair<Long, Long>>()
    @Volatile var doneText: String? = null; private set
    @Volatile private var cancelled = false
    val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private var pending: Request? = null

    fun start(ctx: Context, r: Request) {
        if (running) return
        pending = r
        running = true; error = null; doneText = null; cancelled = false; stage = "Готовлю…"; fraction = 0f
        bytesDone = 0; bytesTotal = 0; etaSec = -1; samples.clear()
        what = when {
            r.video == null -> "${r.photos.size} фото"
            else -> "${r.photos.size} фото + видео"
        } + if (r.sendAt > 0) " · запланирован" else ""
        changed()
        ctx.startForegroundService(Intent(ctx, TgPublishService::class.java))
    }

    fun cancel() { cancelled = true; stage = "Отменяю…"; changed() }

    private fun changed() = main.post { listeners.forEach { it() }; TgPublishService.update() }
    private fun step(text: String, f: Float) { stage = text; fraction = f.coerceIn(0f, 1f); changed() }

    /** The whole job (background thread). */
    internal fun run(ctx: Context) {
        val r = pending ?: run { running = false; changed(); return }
        pending = null
        val tmp = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "upload").apply { deleteRecursively(); mkdirs() }
        val openFds = ArrayList<ParcelFileDescriptor>()
        try {
            if (Tg.state != Tg.State.READY) throw IllegalStateException("Сначала войди в Telegram во вкладке «Канал»")
            step("Ищу канал…", 0f)
            val chat = Tg.call(TdApi.SearchPublicChat(r.channel)) as? TdApi.Chat
                ?: throw IllegalStateException("Канал @${r.channel} не найден")
            // sends left hanging from before (app closed mid-upload, files gone): Telegram keeps
            // retrying them and the new upload waits behind them — remove them first
            clearStuck(chat.id)
            val premium = (Tg.call(TdApi.GetMe()) as? TdApi.User)?.isPremium == true
            val limit = if (premium) SplitPlan.TELEGRAM_PREMIUM_LIMIT else SplitPlan.TELEGRAM_LIMIT

            // photos: the same JPEGs the bot would send
            val photoFiles = r.photos.mapIndexed { i, f ->
                step("Готовлю фото ${i + 1} из ${r.photos.size}…", 0.02f * i)
                File(tmp, "photo$i.jpg").apply { writeBytes(PhotoPrep.jpeg(f)) }
            }

            // video: as it is, or cut into parts that fit
            val videos = ArrayList<VidInfo>()
            if (r.video != null) {
                val size = ctx.contentResolver.openAssetFileDescriptor(r.video, "r")?.use { it.length } ?: -1L
                if (size in 1..limit) {
                    videos += describe(ctx, r.video, pathOf(ctx, r.video, openFds, tmp, size), File(tmp, "thumb0.jpg"))
                } else {
                    step("Считаю, как разделить видео…", 0.12f)
                    val sc = SplitPlan.scan(ctx, r.video, size) { f -> step("Считаю, как разделить видео… ${(f * 100).toInt()}%", 0.12f); !cancelled }
                    val plan = SplitPlan.auto(sc, limit)
                    val fmt = Exporters.clipFormat(ctx, r.video)
                    plan.parts.forEachIndexed { i, p ->
                        if (cancelled) throw InterruptedException()
                        val out = File(tmp, "part${i + 1}.${fmt.ext}")
                        Exporters.clip(ctx, r.video, p.startAbs, p.endAbs - 1, out, absolute = true) { f ->
                            step("Режу на части: ${i + 1} из ${plan.parts.size} · ${(f * 100).toInt()}%", 0.15f); !cancelled
                        }
                        videos += describe(ctx, Uri.fromFile(out), out.path, File(tmp, "thumb${i + 1}.jpg"))
                    }
                }
            }
            if (cancelled) throw InterruptedException()
            if (videos.size > 1) what = "${r.photos.size} фото + ${videos.size} части видео" + if (r.sendAt > 0) " · запланирован" else ""

            // send: photos (caption on the first), then the videos — Telegram keeps this order
            val opts = TdApi.MessageSendOptions().apply {
                if (r.sendAt > 0) schedulingState = TdApi.MessageSchedulingStateSendAtDate(r.sendAt, 0)
            }
            val photoContents = photoFiles.mapIndexed { i, f ->
                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(f.path, o)
                TdApi.InputMessagePhoto(
                    TdApi.InputPhoto(TdApi.InputFileLocal(f.path), null, null, IntArray(0), o.outWidth, o.outHeight),
                    if (i == 0) TdApi.FormattedText(r.caption, emptyArray()) else null, false, null, false,
                )
            }
            val videoContents = videos.mapIndexed { i, v ->
                val caption = videoCaption(if (videos.size > 1) i + 1 else 0)
                TdApi.InputMessageVideo(
                    TdApi.InputVideo(TdApi.InputFileLocal(v.path),
                        v.thumb?.let { TdApi.InputThumbnail(TdApi.InputFileLocal(it.path), 320, 320 * v.h / maxOf(1, v.w)) },
                        null, 0, IntArray(0), v.durationS, v.w, v.h, true),
                    caption, false, null, false,
                )
            }

            // progress: bytes uploaded of every file, until each message is sent (or fails)
            val waiting = ConcurrentHashMap.newKeySet<Long>()
            /** Messages of this post already in the channel (deleted again if the post fails). */
            val sent = ConcurrentHashMap.newKeySet<Long>()
            var lastProgress = System.currentTimeMillis()
            val sizes = ConcurrentHashMap<Int, Long>()
            val videoFiles = ConcurrentHashMap.newKeySet<Int>()
            val uploaded = ConcurrentHashMap<Int, Long>()
            var failure: String? = null
            val allSent = CountDownLatch(1)
            val listener: (TdApi.Object) -> Unit = { u ->
                when (u) {
                    is TdApi.UpdateFile -> if (sizes.containsKey(u.file.id)) {
                        if ((uploaded[u.file.id] ?: -1L) != u.file.remote.uploadedSize) lastProgress = System.currentTimeMillis()
                        uploaded[u.file.id] = if (u.file.remote.isUploadingCompleted) sizes[u.file.id] ?: 0 else u.file.remote.uploadedSize
                        val total = sizes.values.sum().coerceAtLeast(1)
                        val done = uploaded.values.sum()
                        synchronized(samples) {
                            val now = System.currentTimeMillis()
                            samples.addLast(now to done)
                            while (samples.size > 2 && now - samples.first().first > 20_000) samples.removeFirst()
                            val (t0, b0) = samples.first()
                            val speed = if (now - t0 > 1500) (done - b0) * 1000.0 / (now - t0) else 0.0
                            etaSec = if (speed > 1) ((total - done) / speed).toLong() else -1
                        }
                        bytesDone = done; bytesTotal = total
                        step((if (r.sendAt > 0) "Загружаю (пост запланирован)… " else "Загружаю… ") +
                            "${mb(uploaded.values.sum())} из ${mb(total)}", 0.25f + 0.75f * uploaded.values.sum() / total)
                    }
                    is TdApi.UpdateMessageSendSucceeded -> if (waiting.remove(u.oldMessageId)) {
                        sent += u.message.id; lastProgress = System.currentTimeMillis()
                        if (waiting.isEmpty()) allSent.countDown()
                    }
                    is TdApi.UpdateMessageSendFailed -> if (waiting.remove(u.oldMessageId)) {
                        failure = Tg.errorOf(u.error) ?: u.error.message
                        if (waiting.isEmpty()) allSent.countDown()
                    }
                }
            }
            Tg.rawListeners += listener
            try {
                fun sendGroup(contents: List<TdApi.InputMessageContent>, isVideo: Boolean = false) {
                    for (chunk in contents.chunked(10)) {
                        val res = if (chunk.size == 1)
                            Tg.call(TdApi.SendMessage(chat.id, null, null, opts, null, chunk[0]), 120).let { m -> (m as? TdApi.Message)?.let { arrayOf(it) } ?: throw IllegalStateException(Tg.errorOf(m)) }
                        else (Tg.call(TdApi.SendMessageAlbum(chat.id, null, null, opts, chunk.toTypedArray()), 120) as? TdApi.Messages)?.messages
                            ?: throw IllegalStateException("Telegram не принял сообщения")
                        res.forEach { m ->
                            waiting += m.id
                            fileOf(m.content)?.let { f -> sizes[f.id] = maxOf(f.size, f.expectedSize, 1); if (isVideo) videoFiles += f.id }
                        }
                    }
                }
                step("Отправляю фото…", 0.25f)
                sendGroup(photoContents)
                if (videoContents.isNotEmpty()) sendGroup(videoContents, isVideo = true)
                // big uploads take a while: wait as long as bytes keep going, unless cancelled
                fun dropPost() {
                    val ids = (waiting + sent).toLongArray()
                    if (ids.isNotEmpty()) Tg.call(TdApi.DeleteMessages(chat.id, ids, true))
                }
                var diag = ""
                var lastDiag = 0L
                while (!allSent.await(2, TimeUnit.SECONDS)) {
                    if (cancelled) { dropPost(); throw InterruptedException() }
                    val idle = System.currentTimeMillis() - lastProgress
                    // nothing moves: ask Telegram what it is doing with the video, show it
                    if (idle > 40_000 && System.currentTimeMillis() - lastDiag > 10_000 && videoFiles.isNotEmpty()) {
                        lastDiag = System.currentTimeMillis()
                        diag = videoFiles.joinToString("\n") { id -> describeFile(id) }
                        step("Видео стоит ${idle / 1000} с.\n$diag", fraction)
                    }
                    if (idle > STALL_MS) {
                        dropPost()
                        throw IllegalStateException("Видео не загружалось ${STALL_MS / 60000} мин — пост убран из канала.\n$diag")
                    }
                }
                // a part failed: the rest of the post shouldn't hang in the channel without it
                if (failure != null) dropPost()
            } finally { Tg.rawListeners -= listener }
            failure?.let { throw IllegalStateException("$it — пост убран из канала") }
            doneText = when {
                r.sendAt > 0 -> "Пост запланирован на ${java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale("ru")).format(java.util.Date(r.sendAt * 1000L))}" +
                    (if (videos.size > 1) " (фото + ${videos.size} части видео)" else if (videos.isNotEmpty()) " (фото + видео)" else "")
                videos.size > 1 -> "Опубликовано: фото + ${videos.size} части видео"
                videos.isNotEmpty() -> "Опубликовано: фото + видео"
                else -> "Опубликовано"
            }
        } catch (e: InterruptedException) {
            error = "Отменено"
        } catch (e: Throwable) {
            error = e.message ?: e.javaClass.simpleName
        } finally {
            openFds.forEach { runCatching { it.close() } }
            tmp.deleteRecursively()
            running = false
            changed()
        }
    }

    /** No byte uploaded for this long = stuck. */
    private const val STALL_MS = 5 * 60_000L

    /**
     * Messages still «sending» or failed — in this channel and in the other chats too (Telegram
     * uploads only a few files at once: an old dead upload anywhere can hold the new one): deleted,
     * which cancels their uploads.
     */
    private fun clearStuck(chatId: Long) {
        val chats = LinkedHashSet<Long>().apply {
            add(chatId)
            // only channels (where this app posts): personal chats are left alone
            runCatching { (Tg.call(TdApi.GetChats(TdApi.ChatListMain(), 60)) as? TdApi.Chats)?.chatIds?.forEach { id ->
                val t = (Tg.call(TdApi.GetChat(id), 10) as? TdApi.Chat)?.type
                if (t is TdApi.ChatTypeSupergroup && t.isChannel) add(id)
            } }
        }
        var n = 0
        chats.forEach { c ->
            runCatching {
                val h = Tg.call(TdApi.GetChatHistory(c, 0, 0, 50, true), 10) as? TdApi.Messages ?: return@forEach
                val stuck = h.messages.filterNotNull().filter { it.isOutgoing && it.sendingState != null }.map { it.id }
                if (stuck.isEmpty()) return@forEach
                n += stuck.size
                step("Убираю зависшие отправки: $n…", 0.01f)
                Tg.call(TdApi.DeleteMessages(c, stuck.toLongArray(), true))
            }
        }
    }

    /** What Telegram says about an uploading file — for «видео стоит». */
    private fun describeFile(id: Int): String {
        val f = Tg.call(TdApi.GetFile(id), 10) as? TdApi.File ?: return "Telegram не отвечает про файл"
        val local = f.local
        val name = local.path.substringAfterLast('/').take(40)
        val exists = local.path.isNotEmpty() && File(local.path).let { it.exists() && it.canRead() }
        return buildString {
            append("Файл «").append(name.ifEmpty { "?" }).append("»: ")
            append(if (exists) "на месте" else "не читается")
            append(" · ").append(when {
                f.remote.isUploadingCompleted -> "загружен"
                f.remote.isUploadingActive -> "Telegram грузит, ${mb(f.remote.uploadedSize)}"
                else -> "Telegram не начал загрузку"
            })
            append(" · ").append(mb(maxOf(f.size, f.expectedSize)))
        }
    }

    /** The channel's signature under every video, linked to the channel. */
    const val SIGNATURE = "Сиси Сиси +🔞"
    const val SIGNATURE_URL = "https://t.me/itssisi69titi"

    /**
     * Whole video: «Сиси Сиси +🔞»; a part: «Part N», empty line, «Сиси Сиси +🔞» —
     * the signature is a link to the channel.
     */
    fun videoCaption(part: Int): TdApi.FormattedText {
        val head = if (part > 0) "Part $part\n\n" else ""
        val text = head + SIGNATURE
        // offsets in UTF-16 units, as Telegram counts them (🔞 is 2)
        val link = TdApi.TextEntity(head.length, SIGNATURE.length, TdApi.TextEntityTypeTextUrl(SIGNATURE_URL))
        return TdApi.FormattedText(text, arrayOf(link))
    }

    private fun fileOf(c: TdApi.MessageContent): TdApi.File? = when (c) {
        is TdApi.MessagePhoto -> c.photo.sizes.maxByOrNull { it.width }?.photo
        is TdApi.MessageVideo -> c.video.video
        else -> null
    }

    /** A path TDLib can read: the file itself, or the open file of a gallery / folder video. */
    /**
     * A path Telegram can read the video from. TDLib wants a real file: «/proc/self/fd/N» of a
     * content:// video often fails there ("Can't find real file path"). So: the real file behind
     * the descriptor when this app may read it directly, else a copy next to the other upload files
     * (deleted after the post).
     */
    private fun pathOf(ctx: Context, uri: Uri, keep: MutableList<ParcelFileDescriptor>, tmp: File, size: Long): String {
        if (uri.scheme == "file") return uri.path!!
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: throw IllegalStateException("Не открывается видео")
        keep += pfd
        val real = runCatching { android.system.Os.readlink("/proc/self/fd/${pfd.fd}") }.getOrNull()
        if (real != null && real.startsWith("/") && runCatching { java.io.FileInputStream(real).use { it.read() }; true }.getOrDefault(false))
            return real
        // a copy the app owns
        val free = tmp.usableSpace
        if (size > 0 && free < size + 200L * 1024 * 1024)
            throw IllegalStateException("Мало места на телефоне: для отправки нужна копия видео (${mb(size)}), свободно ${mb(free)}")
        val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(ctx.contentResolver.getType(uri)) ?: "mp4"
        val out = File(tmp, "video.$ext")
        java.io.FileInputStream(pfd.fileDescriptor).channel.use { src ->
            src.position(0)
            out.outputStream().channel.use { dst ->
                var pos = 0L
                val total = if (size > 0) size else src.size()
                while (pos < total) {
                    if (cancelled) throw InterruptedException()
                    val n = dst.transferFrom(src, pos, minOf(32L shl 20, total - pos))
                    if (n <= 0) break
                    pos += n
                    step("Готовлю видео к отправке… ${(pos * 100 / maxOf(total, 1)).toInt()}%", 0.05f + 0.15f * pos / maxOf(total, 1))
                }
            }
        }
        return out.path
    }

    private class VidInfo(val path: String, val durationS: Int, val w: Int, val h: Int, val thumb: File?)

    private fun describe(ctx: Context, uri: Uri, path: String, thumbOut: File): VidInfo = MediaMetadataRetriever().let { m ->
        try {
            m.setDataSource(ctx, uri)
            val dur = (m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000
            var w = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
            val thumb = runCatching {
                m.getScaledFrameAtTime(dur * 1_000_000L / 3, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 320)?.let { b ->
                    thumbOut.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 85, it) }; thumbOut
                }
            }.getOrNull()
            VidInfo(path, dur.toInt(), w, h, thumb)
        } finally { m.release() }
    }

    private fun mb(b: Long) = if (b >= 1L shl 30) String.format(java.util.Locale.US, "%.2f ГБ", b / (1L shl 30).toDouble())
        else String.format(java.util.Locale.US, "%.0f МБ", b / (1L shl 20).toDouble())
}

class TgPublishService : Service() {
    companion object {
        private const val CHANNEL = "tgpub"
        private const val ID = 71
        private const val DONE_ID = 72
        private const val ACTION_STOP = "com.dave.framegrabber.STOP_TGPUB"
        @Volatile private var instance: TgPublishService? = null
        fun update() { instance?.refresh() }
    }

    private lateinit var nm: NotificationManager
    private var last = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Публикация в канал", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onDestroy() { instance = null; super.onDestroy() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { TgPublish.cancel(); return START_NOT_STICKY }
        startForeground(ID, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        Thread({ TgPublish.run(applicationContext) }, "tg-publish").start()
        return START_NOT_STICKY
    }

    fun refresh() {
        if (!TgPublish.running) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            nm.notify(DONE_ID, Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_send)
                .setContentTitle(if (TgPublish.error == null) "Пост в канале ✓" else "Пост не опубликован")
                .setContentText(TgPublish.error ?: TgPublish.doneText ?: "")
                .setStyle(Notification.BigTextStyle().bigText(TgPublish.error ?: TgPublish.doneText ?: ""))
                .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
                .setAutoCancel(true).build())
            stopSelf()
            return
        }
        val now = System.currentTimeMillis()
        if (now - last < 700) return
        last = now
        nm.notify(ID, build())
    }

    private fun build(): Notification = Notification.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_send)
        .setContentTitle("Публикую пост в канал")
        .setContentText(TgPublish.stage)
        .setStyle(Notification.BigTextStyle().bigText(TgPublish.stage))
        .setProgress(1000, (TgPublish.fraction * 1000).toInt(), false)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .addAction(Notification.Action.Builder(null, "Отменить",
            PendingIntent.getService(this, 1, Intent(this, TgPublishService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)).build())
        .build()
}
