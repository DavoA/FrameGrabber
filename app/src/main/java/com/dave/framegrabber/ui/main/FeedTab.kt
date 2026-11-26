package com.dave.framegrabber.ui.main

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.dave.framegrabber.R
import com.dave.framegrabber.core.TimeUtil
import com.dave.framegrabber.telegram.TgStore
import com.dave.framegrabber.tgclient.Tg
import com.dave.framegrabber.tgclient.TgDownloads
import com.dave.framegrabber.tgclient.TgKeys
import com.dave.framegrabber.ui.hideKeyboard
import com.dave.framegrabber.ui.toast
import org.drinkless.tdlib.TdApi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Page «Канал»: your Telegram channel inside the app, through your own account (TDLib).
 * Login: api keys (my.telegram.org) → phone → code → 2-step password. Then the feed: posts
 * (albums grouped), captions with hashtags, and ⬇ for every video — it is downloaded by the app
 * (in the background, with a notification) into Movies/FrameGrabber/Telegram and shows up in «Плеер».
 */
class FeedTab(private val s: MainScreen) {

    /** One post on the screen: a single message or an album. */
    class Post(val ids: List<Long>, val date: Int, val caption: TdApi.FormattedText?, val media: List<Media>)
    class Media(val msgId: Long, val thumb: TdApi.File?, val mini: ByteArray?, val w: Int, val h: Int,
                val big: TdApi.File?, val video: TdApi.Video?)

    private val act = s.act
    private val d = act.resources.displayMetrics.density
    private val loginScroll = s.h<ScrollView>(R.id.feedLogin)
    private val loginBox = s.h<LinearLayout>(R.id.feedLoginBox)
    private val main = s.h<View>(R.id.feedMain)
    private val list = s.h<ListView>(R.id.feedList)
    private val title = s.h<TextView>(R.id.txtFeedTitle)
    private val sub = s.h<TextView>(R.id.txtFeedSub)
    private val keys = TgKeys(act)

    private val posts = ArrayList<Post>()
    private var chatId = 0L
    private var chatName = ""
    private var oldest = 0L
    private var loading = false
    private var reachedEnd = false
    private var loadError: String? = null
    private var shownState: Tg.State? = null
    private var visible = false

    private val net = Executors.newFixedThreadPool(3)
    private val thumbs = object : LruCache<Int, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(k: Int, b: Bitmap) = b.byteCount
    }
    private val pendingThumbs = HashSet<Int>()
    private val adapter = Adapter()
    private val refreshRows = Runnable { adapter.notifyDataSetChanged() }

    init {
        list.adapter = adapter
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(v: AbsListView, st: Int) {
                act.bgView.scrolling = st != AbsListView.OnScrollListener.SCROLL_STATE_IDLE
            }
            override fun onScroll(v: AbsListView, first: Int, count: Int, total: Int) {
                if (total > 0 && first + count >= total - 2) loadMore()
            }
        })
        s.h<View>(R.id.btnFeedRefresh).setOnClickListener { reload() }
        s.h<View>(R.id.btnFeedMenu).setOnClickListener { menu() }
        Tg.stateListeners += { if (visible) render() }
        TgDownloads.listeners += { if (visible) { s.ui.removeCallbacks(refreshRows); s.ui.postDelayed(refreshRows, 250) } }
    }

    /** The page was opened. */
    fun onShown() {
        visible = true
        Tg.start(act)
        render()
    }

    fun onHidden() { visible = false }

    // ================================================================ login steps

    private fun render() {
        val st = Tg.state
        if (st == Tg.State.READY) {
            loginScroll.visibility = View.GONE
            main.visibility = View.VISIBLE
            if (channel().isEmpty()) { askChannel(); return }
            if (chatId == 0L && !loading) reload()
            shownState = st
            return
        }
        main.visibility = View.GONE
        loginScroll.visibility = View.VISIBLE
        if (st == shownState && st != Tg.State.ERROR) return
        shownState = st
        loginBox.removeAllViews()
        title("Мой канал в приложении")
        when (st) {
            Tg.State.UNSUPPORTED -> text("Этот телефон 32-битный, а Telegram-модуль приложения собран только для 64-битных телефонов (почти все телефоны после 2017 года). Здесь лента недоступна.")
            Tg.State.NO_KEYS -> keysForm()
            Tg.State.STARTING, Tg.State.LOGGING_OUT -> spinner(if (st == Tg.State.STARTING) "Подключаюсь к Telegram…" else "Выхожу из аккаунта…")
            Tg.State.WAIT_PHONE -> step(
                "Вход через твой аккаунт Telegram. Номер — в международном формате, например +374…",
                "Номер телефона", InputType.TYPE_CLASS_PHONE, "Получить код"
            ) { v, done -> Tg.sendPhone(v, done) }
            Tg.State.WAIT_CODE -> step(
                "Telegram прислал код — он придёт сообщением в самом Telegram (чат «Telegram»), реже — SMS.",
                "Код", InputType.TYPE_CLASS_NUMBER, "Войти"
            ) { v, done -> Tg.sendCode(v, done) }
            Tg.State.WAIT_PASSWORD -> step(
                "На аккаунте стоит облачный пароль (двухэтапная проверка)." +
                    (if (Tg.passwordHint.isNotBlank()) "\nПодсказка: ${Tg.passwordHint}" else ""),
                "Облачный пароль", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, "Войти"
            ) { v, done -> Tg.sendPassword(v, done) }
            Tg.State.ERROR -> {
                text(Tg.lastError ?: "Ошибка Telegram", R.color.danger)
                button("Попробовать снова", primary = true) { Tg.restart(act) }
                button("Изменить api_id / api_hash") { keys.apiId = 0; Tg.restart(act) }
            }
            Tg.State.READY -> {}
        }
    }

    private fun keysForm() {
        text("Чтобы приложение могло зайти в Telegram от твоего имени, нужны личные ключи — их выдаёт сам Telegram, один раз и бесплатно:\n\n" +
            "1. Открой my.telegram.org и войди по номеру.\n" +
            "2. «API development tools» → заполни App title (любое, например FrameGrabber) и Short name → Create.\n" +
            "3. Скопируй сюда App api_id и App api_hash.\n\n" +
            "Ключи и вход хранятся только на этом телефоне.")
        button("Открыть my.telegram.org") {
            runCatching { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://my.telegram.org/apps"))) }
        }
        val id = input("api_id", InputType.TYPE_CLASS_NUMBER)
        val hash = input("api_hash", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        button("Сохранить и продолжить", primary = true) {
            val i = id.text.toString().trim().toIntOrNull() ?: 0
            val h = hash.text.toString().trim()
            if (i <= 0 || !h.matches(Regex("[0-9a-fA-F]{32}"))) { act.toast("api_id — число, api_hash — 32 символа (цифры и a–f)"); return@button }
            keys.apiId = i; keys.apiHash = h
            hash.hideKeyboard()
            Tg.restart(act)
        }
    }

    private fun step(info: String, hint: String, type: Int, action: String, send: (String, (String?) -> Unit) -> Unit) {
        text(info)
        val e = input(hint, type)
        val err = text("", R.color.danger).apply { visibility = View.GONE }
        lateinit var b: Button
        b = button(action, primary = true) {
            val v = e.text.toString()
            if (v.isBlank()) return@button
            b.isEnabled = false; b.text = "…"
            e.hideKeyboard()
            send(v) { error ->
                b.isEnabled = true; b.text = action
                if (error != null) { err.text = error; err.visibility = View.VISIBLE }
            }
        }
        e.setOnEditorActionListener { _, _, _ -> b.performClick(); true }
        if (Tg.state != Tg.State.WAIT_PHONE) button("Начать заново") { Tg.logOut() }
    }

    private fun title(t: String) = loginBox.addView(TextView(act, null, 0, R.style.CardTitle).apply { text = t })

    private fun text(t: String, color: Int = R.color.textDim): TextView = TextView(act).apply {
        text = t; textSize = 14f; setTextColor(act.getColor(color)); setLineSpacing(0f, 1.15f)
        setPadding(0, (4 * d).toInt(), 0, (4 * d).toInt())
        loginBox.addView(this)
    }

    private fun input(hint: String, type: Int): EditText = EditText(act, null, 0, R.style.Input).apply {
        this.hint = hint; inputType = type
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (10 * d).toInt() }
        loginBox.addView(this)
    }

    private fun button(t: String, primary: Boolean = false, click: () -> Unit): Button =
        Button(act, null, 0, if (primary) R.style.BtnPrimary else R.style.BtnSecondary).apply {
            text = t
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (10 * d).toInt() }
            setOnClickListener { click() }
            loginBox.addView(this)
        }

    private fun spinner(t: String) {
        val row = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt()) }
        row.addView(ProgressBar(act).apply { layoutParams = LinearLayout.LayoutParams((28 * d).toInt(), (28 * d).toInt()) })
        row.addView(TextView(act).apply { text = t; setTextColor(act.getColor(R.color.textDim)); setPadding((12 * d).toInt(), 0, 0, 0) })
        loginBox.addView(row)
    }

    // ================================================================ channel / menu

    /** The channel to show: the one picked here, else the posting channel's @name. */
    private fun channel(): String = keys.feedChannel.ifBlank {
        TgStore(act).chatUsername.removePrefix("@").ifBlank { TgStore(act).chat.takeIf { it.startsWith("@") }?.removePrefix("@").orEmpty() }
    }

    private fun askChannel() {
        val e = EditText(act, null, 0, R.style.Input).apply {
            hint = "@имя_канала"; setText(channel().takeIf { it.isNotEmpty() }?.let { "@$it" } ?: "")
        }
        val box = FrameLayout(act).apply { setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), 0); addView(e) }
        AlertDialog.Builder(act, R.style.DialogTheme)
            .setTitle("Какой канал показывать?")
            .setMessage("Публичное имя канала (или ссылка t.me/…)")
            .setView(box)
            .setPositiveButton("Показать") { _, _ ->
                val v = e.text.toString().trim().substringAfterLast("t.me/").substringBefore("/").removePrefix("@")
                if (v.isNotEmpty()) { keys.feedChannel = v; chatId = 0; reload() }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun menu() {
        val items = arrayOf("Другой канал", "Загрузки", "Выйти из аккаунта Telegram")
        AlertDialog.Builder(act, R.style.DialogTheme).setItems(items) { _, i ->
            when (i) {
                0 -> askChannel()
                1 -> showDownloads()
                2 -> AlertDialog.Builder(act, R.style.DialogTheme)
                    .setMessage("Выйти из аккаунта в приложении? Скачанные видео останутся.")
                    .setPositiveButton("Выйти") { _, _ -> posts.clear(); chatId = 0; adapter.notifyDataSetChanged(); Tg.logOut() }
                    .setNegativeButton("Отмена", null).show()
            }
        }.show()
    }

    private fun showDownloads() {
        val jobs = TgDownloads.jobs.toList().reversed()
        if (jobs.isEmpty()) { act.toast("Загрузок пока нет"); return }
        val lines = jobs.map { j ->
            val st = when (j.stage) {
                "downloading" -> "${(j.fraction * 100).toInt()}% · ${size(j.done)} из ${size(j.total)}"
                "copying" -> "сохраняю…"
                "finished" -> "✓ скачано"
                "cancelled" -> "отменено"
                else -> "ошибка: ${j.error}"
            }
            "${j.name}\n$st"
        }.toTypedArray()
        AlertDialog.Builder(act, R.style.DialogTheme).setTitle("Загрузки").setItems(lines) { _, i ->
            val j = jobs[i]
            TgDownloads.saved(act, j.key)?.let { s.playerTab.play(it, name = j.name) }
        }.setPositiveButton("Закрыть", null).show()
    }

    // ================================================================ feed loading

    private fun reload() {
        if (Tg.state != Tg.State.READY) { render(); return }
        val name = channel()
        if (name.isEmpty()) { askChannel(); return }
        posts.clear(); oldest = 0; reachedEnd = false; loadError = null; chatId = 0
        title.text = "@$name"; sub.text = "Загрузка…"
        adapter.notifyDataSetChanged()
        loading = true
        net.execute {
            val r = Tg.call(TdApi.SearchPublicChat(name))
            s.ui.post {
                loading = false
                val chat = r as? TdApi.Chat
                if (chat == null) {
                    loadError = Tg.errorOf(r) ?: "Канал не найден"
                    sub.text = loadError
                    adapter.notifyDataSetChanged()
                    return@post
                }
                chatId = chat.id; chatName = name
                title.text = chat.title.ifBlank { "@$name" }
                sub.text = "@$name"
                Tg.send(TdApi.OpenChat(chat.id)) {}
                loadMore()
            }
        }
    }

    private fun loadMore() {
        if (loading || reachedEnd || chatId == 0L) return
        loading = true
        adapter.notifyDataSetChanged()
        val chat = chatId
        val from = oldest
        net.execute {
            // the first request may return just a few messages from the local cache: ask until a page is full
            val got = ArrayList<TdApi.Message>()
            var cursor = from
            var error: String? = null
            var tries = 0
            while (got.size < 30 && tries < 6) {
                tries++
                val r = Tg.call(TdApi.GetChatHistory(chat, cursor, 0, 50, false))
                val m = r as? TdApi.Messages ?: run { error = Tg.errorOf(r); null } ?: break
                if (m.messages.isEmpty()) break
                got += m.messages
                cursor = m.messages.last().id
            }
            val grouped = group(got)
            s.ui.post {
                loading = false
                if (chat != chatId) return@post
                if (got.isEmpty()) { reachedEnd = true; loadError = error }
                else {
                    oldest = got.last().id
                    // an album cut by the page edge: glue its halves together
                    val last = posts.lastOrNull()
                    if (last != null && grouped.isNotEmpty() && sameAlbum(last, grouped.first())) {
                        val f = grouped.removeAt(0)
                        posts[posts.size - 1] = Post(last.ids + f.ids, last.date, last.caption ?: f.caption, last.media + f.media)
                    }
                    posts += grouped
                }
                adapter.notifyDataSetChanged()
            }
        }
    }

    private val albumOf = HashMap<Long, Long>()
    private fun sameAlbum(a: Post, b: Post) = albumOf[a.ids.last()]?.let { it != 0L && it == albumOf[b.ids.first()] } ?: false

    /** Newest first; messages of one album → one post (photos in the album's order). */
    private fun group(ms: List<TdApi.Message>): ArrayList<Post> {
        val out = ArrayList<Post>()
        var i = 0
        while (i < ms.size) {
            val album = ms[i].mediaAlbumId
            var j = i + 1
            if (album != 0L) while (j < ms.size && ms[j].mediaAlbumId == album) j++
            val part = ms.subList(i, j).reversed()   // album items oldest → newest = their order in the post
            part.forEach { albumOf[it.id] = it.mediaAlbumId }
            val caption = part.firstNotNullOfOrNull { captionOf(it.content)?.takeIf { c -> c.text.isNotBlank() } }
            val media = part.mapNotNull { mediaOf(it) }
            if (caption != null || media.isNotEmpty()) out += Post(part.map { it.id }, part.first().date, caption, media)
            i = j
        }
        return out
    }

    private fun captionOf(c: TdApi.MessageContent): TdApi.FormattedText? = when (c) {
        is TdApi.MessageText -> c.text
        is TdApi.MessagePhoto -> c.caption
        is TdApi.MessageVideo -> c.caption
        is TdApi.MessageAnimation -> c.caption
        is TdApi.MessageDocument -> c.caption
        else -> null
    }

    private fun mediaOf(m: TdApi.Message): Media? = when (val c = m.content) {
        is TdApi.MessagePhoto -> {
            val sizes = c.photo.sizes.sortedBy { it.width }
            val small = sizes.firstOrNull { it.width >= 320 } ?: sizes.lastOrNull()
            val big = sizes.lastOrNull()
            small?.let { Media(m.id, it.photo, c.photo.minithumbnail?.data, it.width, it.height, big?.photo, null) }
        }
        is TdApi.MessageVideo -> Media(m.id, c.video.thumbnail?.file, c.video.minithumbnail?.data,
            c.video.width, c.video.height, null, c.video)
        is TdApi.MessageAnimation -> Media(m.id, c.animation.thumbnail?.file, c.animation.minithumbnail?.data,
            c.animation.width, c.animation.height, null, null)
        is TdApi.MessageDocument -> {
            val doc = c.document
            // a video sent as a file: still downloadable
            if (doc.mimeType.startsWith("video/")) {
                val v = TdApi.Video(0, 0, 0, doc.fileName, doc.mimeType, false, false, doc.minithumbnail, doc.thumbnail, doc.document)
                Media(m.id, doc.thumbnail?.file, doc.minithumbnail?.data, 16, 9, null, v)
            } else null
        }
        else -> null
    }

    // ================================================================ images

    private fun thumbInto(iv: ImageView, f: TdApi.File?, mini: ByteArray?) {
        iv.tag = f?.id
        val cached = f?.let { thumbs.get(it.id) }
        if (cached != null) { iv.setImageBitmap(cached); return }
        // tiny blurred preview while the real one loads
        iv.setImageBitmap(mini?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() })
        if (f == null || !pendingThumbs.add(f.id)) return
        net.execute {
            val path = Tg.fetch(f, 16)
            val bmp = path?.let { decode(it, 480) }
            s.ui.post {
                pendingThumbs.remove(f.id)
                if (bmp != null) { thumbs.put(f.id, bmp); s.ui.removeCallbacks(refreshRows); s.ui.postDelayed(refreshRows, 120) }
            }
        }
    }

    private fun decode(path: String, max: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        var sample = 1
        while (o.outWidth / (sample * 2) >= max && o.outHeight / (sample * 2) >= max) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** A photo full screen. */
    private fun openPhoto(m: Media) {
        val f = m.big ?: m.thumb ?: return
        val iv = ImageView(act).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER }
        thumbs.get(m.thumb?.id ?: -1)?.let { iv.setImageBitmap(it) }
        val dlg = AlertDialog.Builder(act, R.style.DialogTheme).setView(iv).setPositiveButton("Закрыть", null).show()
        net.execute {
            val bmp = Tg.fetch(f, 24)?.let { decode(it, 1600) }
            s.ui.post { if (bmp != null && dlg.isShowing) iv.setImageBitmap(bmp) }
        }
    }

    // ================================================================ list

    private fun key(m: Media) = "$chatId:${m.msgId}"

    private fun videoTap(m: Media) {
        val v = m.video ?: return
        val k = key(m)
        TgDownloads.saved(act, k)?.let { s.playerTab.play(it, name = v.fileName.ifBlank { null }); return }
        if (TgDownloads.active(k) != null) {
            AlertDialog.Builder(act, R.style.DialogTheme).setMessage("Отменить загрузку?")
                .setPositiveButton("Отменить") { _, _ -> TgDownloads.cancel(k) }
                .setNegativeButton("Нет", null).show()
            return
        }
        TgDownloads.start(act, k, v)
        act.toast("Скачиваю — прогресс в уведомлении")
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = posts.size + 1
        override fun getItem(p: Int) = p
        override fun getItemId(p: Int) = p.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(p: Int) = if (p < posts.size) 0 else 1

        override fun getView(p: Int, cv: View?, parent: ViewGroup): View =
            if (p < posts.size) postView(posts[p], parent.width) else footer()
    }

    private fun footer(): View {
        val t = TextView(act).apply {
            gravity = Gravity.CENTER; setPadding(0, (18 * d).toInt(), 0, (18 * d).toInt())
            setTextColor(act.getColor(R.color.textDim)); textSize = 13f
            text = when {
                loading -> "Загрузка…"
                loadError != null -> loadError
                reachedEnd && posts.isEmpty() -> "Постов нет"
                reachedEnd -> "Это самый первый пост"
                else -> ""
            }
        }
        return t
    }

    private fun postView(post: Post, listW: Int): View {
        val card = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding((10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt(), (12 * d).toInt())
            layoutParams = AbsListView.LayoutParams(-1, -2)
        }
        val outer = FrameLayout(act).apply { setPadding(0, (10 * d).toInt(), 0, 0); addView(card) }

        val w = (if (listW > 0) listW - list.paddingStart - list.paddingEnd else act.resources.displayMetrics.widthPixels) - (20 * d).toInt()
        if (post.media.isNotEmpty()) card.addView(mediaGrid(post.media, w))

        // videos: one line each with length, size and the download state
        post.media.filter { it.video != null }.forEach { card.addView(videoRow(it)) }

        post.caption?.let { c ->
            card.addView(TextView(act).apply {
                text = styled(c)
                textSize = 14.5f
                setTextColor(act.getColor(R.color.text))
                setLineSpacing(0f, 1.12f)
                setTextIsSelectable(true)
                movementMethod = LinkMovementMethod.getInstance()
                setPadding((4 * d).toInt(), (10 * d).toInt(), (4 * d).toInt(), 0)
            })
        }
        card.addView(TextView(act).apply {
            text = DATE.format(Date(post.date * 1000L))
            textSize = 11.5f
            setTextColor(act.getColor(R.color.textDim))
            gravity = Gravity.END
            setPadding(0, (6 * d).toInt(), (4 * d).toInt(), 0)
        })
        return outer
    }

    /** Telegram-like layout: 1 item wide, 2 side by side, 3+ in rows of 3 (first row of 2 if needed). */
    private fun mediaGrid(media: List<Media>, w: Int): View {
        val gap = (3 * d).toInt()
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        if (media.size == 1) {
            val m = media[0]
            val ratio = if (m.w > 0 && m.h > 0) (m.h.toFloat() / m.w).coerceIn(0.5f, 1.25f) else 0.75f
            box.addView(tile(m, w, (w * ratio).toInt()))
            return box
        }
        val rows = ArrayList<List<Media>>()
        var rest = media
        if (media.size % 3 == 1 && media.size > 1) { rows += rest.take(2); rest = rest.drop(2) }
        if (rest.size % 3 == 2) { rows += rest.take(2); rest = rest.drop(2) }
        while (rest.isNotEmpty()) { rows += rest.take(3); rest = rest.drop(3) }
        rows.forEachIndexed { ri, row ->
            val line = LinearLayout(act).apply {
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { if (ri > 0) topMargin = gap }
            }
            val cw = (w - gap * (row.size - 1)) / row.size
            val ch = if (row.size == 2) (cw * 1.1f).toInt() else cw
            row.forEachIndexed { ci, m ->
                line.addView(tile(m, cw, ch).apply { (layoutParams as LinearLayout.LayoutParams).marginStart = if (ci > 0) gap else 0 })
            }
            box.addView(line)
        }
        return box
    }

    private fun tile(m: Media, w: Int, h: Int): View {
        val f = FrameLayout(act).apply {
            layoutParams = LinearLayout.LayoutParams(w, h)
            setBackgroundResource(R.drawable.bg_thumb)
            clipToOutline = true
        }
        val iv = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        f.addView(iv, FrameLayout.LayoutParams(-1, -1))
        thumbInto(iv, m.thumb, m.mini)
        val v = m.video
        if (v != null) {
            val k = key(m)
            val saved = TgDownloads.saved(act, k) != null
            f.addView(ImageView(act).apply {
                setImageResource(if (saved) R.drawable.ic_play else R.drawable.ic_download)
                setColorFilter(0xFFFFFFFF.toInt())
                setBackgroundResource(R.drawable.bg_badge)
                val p = (10 * d).toInt(); setPadding(p, p, p, p)
            }, FrameLayout.LayoutParams((46 * d).toInt(), (46 * d).toInt(), Gravity.CENTER))
            if (v.duration > 0) f.addView(TextView(act).apply {
                text = TimeUtil.format(v.duration * 1_000_000L)
                textSize = 11f; setTextColor(0xFFFFFFFF.toInt()); typeface = Typeface.DEFAULT_BOLD
                setBackgroundResource(R.drawable.bg_badge)
                setPadding((6 * d).toInt(), (2 * d).toInt(), (6 * d).toInt(), (2 * d).toInt())
            }, FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.TOP).apply { setMargins((6 * d).toInt(), (6 * d).toInt(), 0, 0) })
            f.setOnClickListener { videoTap(m) }
        } else if (m.big != null) {
            f.setOnClickListener { openPhoto(m) }
        }
        return f
    }

    private fun videoRow(m: Media): View {
        val v = m.video!!
        val k = key(m)
        val job = TgDownloads.active(k)
        val saved = TgDownloads.saved(act, k)
        val total = maxOf(v.video.size, v.video.expectedSize)
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_row)
            setPadding((12 * d).toInt(), (8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (6 * d).toInt() }
        }
        val top = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL }
        val info = buildString {
            append("🎬 ")
            if (v.duration > 0) append(TimeUtil.format(v.duration * 1_000_000L)).append(" · ")
            if (v.width > 0 && v.height > 0) append("${v.width}×${v.height} · ")
            append(size(total))
        }
        top.addView(TextView(act).apply {
            text = when {
                job != null && job.stage == "copying" -> "Сохраняю в галерею…"
                job != null -> "${(job.fraction * 100).toInt()}% · ${size(job.done)} из ${size(job.total)}"
                else -> info
            }
            textSize = 13f; setTextColor(act.getColor(if (saved != null) R.color.accent else R.color.text))
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        top.addView(Button(act, null, 0, R.style.BtnSecondary).apply {
            text = when {
                job != null -> "Отменить"
                saved != null -> "▶ Смотреть"
                else -> "⬇ Скачать"
            }
            minHeight = (38 * d).toInt(); minimumHeight = (38 * d).toInt(); textSize = 13f
            setOnClickListener { videoTap(m) }
        })
        row.addView(top)
        if (job != null) row.addView(ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progress = (job.fraction * 1000).toInt()
            isIndeterminate = job.stage == "copying"
            progressTintList = android.content.res.ColorStateList.valueOf(act.getColor(R.color.primaryBright))
            layoutParams = LinearLayout.LayoutParams(-1, (6 * d).toInt()).apply { topMargin = (6 * d).toInt() }
        })
        else TgDownloads.jobs.lastOrNull { it.key == k && it.stage == "failed" }?.let { j ->
            row.addView(TextView(act).apply { text = "Ошибка: ${j.error}"; textSize = 12f; setTextColor(act.getColor(R.color.danger)) })
        }
        return row
    }

    /** Caption: hashtags in the accent color, bold/italic kept, the separator dimmed. */
    private fun styled(c: TdApi.FormattedText): CharSequence {
        val sp = SpannableString(c.text)
        val accent = act.getColor(R.color.accent)
        c.entities?.forEach { e ->
            val end = (e.offset + e.length).coerceAtMost(sp.length)
            if (e.offset < 0 || e.offset >= end) return@forEach
            when (e.type) {
                is TdApi.TextEntityTypeBold -> sp.setSpan(StyleSpan(Typeface.BOLD), e.offset, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                is TdApi.TextEntityTypeItalic -> sp.setSpan(StyleSpan(Typeface.ITALIC), e.offset, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                else -> {}
            }
        }
        Regex("#[\\p{L}\\p{N}_]+").findAll(c.text).forEach {
            sp.setSpan(ForegroundColorSpan(accent), it.range.first, it.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        Regex("—{4,}").findAll(c.text).forEach {
            sp.setSpan(ForegroundColorSpan(act.getColor(R.color.textDim)), it.range.first, it.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sp
    }

    private fun size(b: Long): String = when {
        b <= 0 -> "?"
        b >= 1L shl 30 -> String.format(Locale.US, "%.2f ГБ", b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> String.format(Locale.US, "%.0f МБ", b / (1L shl 20).toDouble())
        else -> String.format(Locale.US, "%.0f КБ", b / 1024.0)
    }

    companion object {
        private val DATE = SimpleDateFormat("d MMM yyyy, HH:mm", Locale("ru"))
    }
}
