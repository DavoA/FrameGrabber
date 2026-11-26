package com.dave.framegrabber.tgclient

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The app's own Telegram client (TDLib), logged in with YOUR account — to show your channel's
 * feed in the app and download its videos. One per process.
 *
 * Session and cache: filesDir/tdlib (the login, like any Telegram app) and the app's external
 * files folder (downloads in progress). Needs your api_id / api_hash from my.telegram.org.
 */
object Tg {

    enum class State { NO_KEYS, STARTING, WAIT_PHONE, WAIT_CODE, WAIT_PASSWORD, READY, LOGGING_OUT, UNSUPPORTED, ERROR }

    @Volatile var state = State.NO_KEYS; private set
    @Volatile var lastError: String? = null; private set
    /** Hint for the 2-step password ("WAIT_PASSWORD"). */
    @Volatile var passwordHint = ""; private set

    private var client: Client? = null
    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())

    /** State changes (main thread). */
    val stateListeners = CopyOnWriteArrayList<() -> Unit>()
    /** File progress (main thread): file id -> listeners. */
    private val fileListeners = ConcurrentHashMap<Int, CopyOnWriteArrayList<(TdApi.File) -> Unit>>()
    /** New posts in a chat (main thread). */
    val messageListeners = CopyOnWriteArrayList<(TdApi.Message) -> Unit>()

    /** TDLib's native part is built for 64-bit ARM phones (and the x86_64 test emulator). */
    val supported: Boolean by lazy {
        runCatching { Class.forName("org.drinkless.tdlib.Client"); true }.getOrDefault(false) &&
            Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "x86_64" }
    }

    // ================================================================ start / login

    /** Starts (or restarts) with the saved keys. Safe to call many times. */
    fun start(ctx: Context) {
        app = ctx.applicationContext
        if (!supported) { setState(State.UNSUPPORTED); return }
        val keys = TgKeys(app)
        if (!keys.has) { setState(State.NO_KEYS); return }
        if (client != null) return
        setState(State.STARTING)
        runCatching {
            Client.execute(TdApi.SetLogVerbosityLevel(1))
            client = Client.create({ onUpdate(it) }, null, null)
        }.onFailure { fail("Не удалось запустить Telegram: ${it.message}") }
    }

    fun sendPhone(phone: String, done: (String?) -> Unit) =
        send(TdApi.SetAuthenticationPhoneNumber(phone.filter { it.isDigit() || it == '+' }, null)) { done(errorOf(it)) }

    fun sendCode(code: String, done: (String?) -> Unit) =
        send(TdApi.CheckAuthenticationCode(code.trim())) { done(errorOf(it)) }

    fun sendPassword(pw: String, done: (String?) -> Unit) =
        send(TdApi.CheckAuthenticationPassword(pw)) { done(errorOf(it)) }

    fun logOut() { setState(State.LOGGING_OUT); send(TdApi.LogOut()) {} }

    /** New keys: forget the old client and start again. */
    fun restart(ctx: Context) {
        client?.let { c -> runCatching { c.send(TdApi.Close(), null) } }
        client = null
        start(ctx)
    }

    private fun onUpdate(o: TdApi.Object) {
        when (o) {
            is TdApi.UpdateAuthorizationState -> onAuth(o.authorizationState)
            is TdApi.UpdateFile -> fileListeners[o.file.id]?.let { ls -> main.post { ls.forEach { it(o.file) } } }
            is TdApi.UpdateNewMessage -> main.post { messageListeners.forEach { it(o.message) } }
        }
    }

    private fun onAuth(a: TdApi.AuthorizationState) {
        when (a) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                val keys = TgKeys(app)
                val p = TdApi.SetTdlibParameters().apply {
                    useTestDc = false
                    databaseDirectory = File(app.filesDir, "tdlib").path
                    filesDirectory = File(app.getExternalFilesDir(null) ?: app.filesDir, "tdlib").path
                    databaseEncryptionKey = ByteArray(0)
                    useFileDatabase = true
                    useChatInfoDatabase = true
                    useMessageDatabase = true
                    useSecretChats = false
                    apiId = keys.apiId
                    apiHash = keys.apiHash
                    systemLanguageCode = "ru"
                    deviceModel = Build.MODEL
                    systemVersion = "Android " + Build.VERSION.RELEASE
                    applicationVersion = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "1"
                }
                send(p) { r -> errorOf(r)?.let { fail("Telegram не принял ключи: $it") } }
            }
            is TdApi.AuthorizationStateWaitPhoneNumber -> setState(State.WAIT_PHONE)
            is TdApi.AuthorizationStateWaitCode -> setState(State.WAIT_CODE)
            is TdApi.AuthorizationStateWaitPassword -> { passwordHint = a.passwordHint; setState(State.WAIT_PASSWORD) }
            is TdApi.AuthorizationStateReady -> {
                // channels marked 18+: show them if the account is allowed to (owners and adults)
                send(TdApi.GetOption("can_ignore_sensitive_content_restrictions")) { r ->
                    if (r is TdApi.OptionValueBoolean && r.value)
                        send(TdApi.SetOption("ignore_sensitive_content_restrictions", TdApi.OptionValueBoolean(true))) {}
                }
                setState(State.READY)
            }
            is TdApi.AuthorizationStateLoggingOut -> setState(State.LOGGING_OUT)
            is TdApi.AuthorizationStateClosed -> { client = null; if (state == State.LOGGING_OUT) { File(app.filesDir, "tdlib").deleteRecursively(); start(app) } }
            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation,
            is TdApi.AuthorizationStateWaitRegistration,
            is TdApi.AuthorizationStateWaitEmailAddress,
            is TdApi.AuthorizationStateWaitEmailCode -> fail("Этот способ входа приложение не поддерживает. Войди через номер телефона аккаунта, где уже есть Telegram.")
            else -> {}
        }
    }

    private fun setState(s: State) { state = s; main.post { stateListeners.forEach { it() } } }
    private fun fail(msg: String) { lastError = msg; setState(State.ERROR) }

    // ================================================================ requests

    /** Async request; [done] runs on the main thread. */
    fun send(f: TdApi.Function<*>, done: (TdApi.Object) -> Unit) {
        val c = client ?: run { main.post { done(TdApi.Error(400, "Telegram не запущен")) }; return }
        c.send(f) { r -> main.post { done(r) } }
    }

    /** Blocking request (background threads only). */
    fun call(f: TdApi.Function<*>, timeoutSec: Long = 30): TdApi.Object {
        val c = client ?: return TdApi.Error(400, "Telegram не запущен")
        val latch = CountDownLatch(1)
        var out: TdApi.Object = TdApi.Error(408, "Telegram не ответил")
        c.send(f) { r -> out = r; latch.countDown() }
        latch.await(timeoutSec, TimeUnit.SECONDS)
        return out
    }

    fun errorOf(r: TdApi.Object): String? = (r as? TdApi.Error)?.let { explain(it) }

    private fun explain(e: TdApi.Error): String = when {
        e.message.contains("PHONE_NUMBER_INVALID") -> "Неверный номер телефона"
        e.message.contains("PHONE_CODE_INVALID") -> "Неверный код"
        e.message.contains("PHONE_CODE_EXPIRED") -> "Код устарел — запроси новый"
        e.message.contains("PASSWORD_HASH_INVALID") -> "Неверный пароль"
        e.message.contains("API_ID_INVALID") -> "Неверные api_id / api_hash"
        e.message.contains("USERNAME_NOT_OCCUPIED") || e.message.contains("USERNAME_INVALID") -> "Канал не найден"
        e.message.contains("FLOOD_WAIT") -> "Слишком много попыток — Telegram просит подождать"
        else -> e.message
    }

    // ================================================================ files

    fun onFile(id: Int, l: (TdApi.File) -> Unit) { fileListeners.getOrPut(id) { CopyOnWriteArrayList() } += l }
    fun offFile(id: Int, l: (TdApi.File) -> Unit) { fileListeners[id]?.remove(l) }

    /** A small file (thumbnail, photo preview): downloaded and returned as a path (blocking). */
    fun fetch(file: TdApi.File, priority: Int = 8): String? {
        if (file.local.isDownloadingCompleted && File(file.local.path).exists()) return file.local.path
        val r = call(TdApi.DownloadFile(file.id, priority, 0, 0, true), 60)
        return (r as? TdApi.File)?.takeIf { it.local.isDownloadingCompleted }?.local?.path
    }
}

/** api_id / api_hash from my.telegram.org (saved on the phone). */
class TgKeys(ctx: Context) {
    private val sp = ctx.getSharedPreferences("telegram", Context.MODE_PRIVATE)
    var apiId: Int get() = sp.getInt("apiId", 0); set(v) = sp.edit().putInt("apiId", v).apply()
    var apiHash: String get() = sp.getString("apiHash", "") ?: ""; set(v) = sp.edit().putString("apiHash", v.trim()).apply()
    /** The channel to show in the feed (without @). */
    var feedChannel: String get() = sp.getString("feedChannel", "") ?: ""; set(v) = sp.edit().putString("feedChannel", v.trim().removePrefix("@")).apply()
    val has get() = apiId > 0 && apiHash.length >= 16
}
