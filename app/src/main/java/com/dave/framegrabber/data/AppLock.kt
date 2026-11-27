package com.dave.framegrabber.data

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import com.dave.framegrabber.ui.LockActivity
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * «Защита»: a 4-digit PIN (and the fingerprint, if the phone has one) asked when the app is opened
 * again after a while in the background, after the screen was off, or after a restart. The app's
 * picture in the recent-apps list is hidden while the lock is on (Android 13+).
 * Kept in its own prefs file, which the backup leaves out.
 */
object AppLock {
    const val PREFS = "applock"
    private const val K_HASH = "hash"
    private const val K_SALT = "salt"
    private const val K_BIO = "bio"
    private const val K_DELAY = "delay"
    const val PIN_LEN = 4

    /** How long the app may stay in the background before the PIN is asked again (seconds). */
    val DELAYS = listOf(30 to "30 сек", 60 to "1 мин", 300 to "5 мин", 900 to "15 мин")

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(ctx: Context) = p(ctx).getString(K_HASH, null) != null
    fun bio(ctx: Context) = p(ctx).getBoolean(K_BIO, true)
    fun setBio(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean(K_BIO, on).apply()
    fun delay(ctx: Context) = p(ctx).getInt(K_DELAY, 60)
    fun setDelay(ctx: Context, s: Int) = p(ctx).edit().putInt(K_DELAY, s).apply()

    fun setPin(ctx: Context, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        p(ctx).edit().putString(K_SALT, salt).putString(K_HASH, hash(salt, pin)).apply()
        locked = false
    }

    fun check(ctx: Context, pin: String): Boolean {
        val salt = p(ctx).getString(K_SALT, null) ?: return false
        return p(ctx).getString(K_HASH, null) == hash(salt, pin)
    }

    fun disable(ctx: Context) { p(ctx).edit().remove(K_HASH).remove(K_SALT).apply(); locked = false }

    private fun hash(salt: String, pin: String): String {
        var b = (salt + pin).toByteArray()
        val md = MessageDigest.getInstance("SHA-256")
        repeat(5000) { b = md.digest(b) }
        return b.joinToString("") { "%02x".format(it) }
    }

    /** Wrong tries in a row and when typing is allowed again. */
    var fails = 0
    var waitUntil = 0L

    // ---------------------------------------------------------------- the lock itself

    /** The PIN has to be typed before any screen is shown. True after a restart. */
    @Volatile var locked = true
    private var started = 0
    private var backgroundSince = 0L
    private var showing = false

    fun unlocked() { locked = false; showing = false; fails = 0 }
    fun lockScreenGone() { showing = false }

    /** Call once from Application.onCreate. */
    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) { hideInRecents(a) }
            override fun onActivityStarted(a: Activity) {
                if (started++ == 0 && backgroundSince > 0 &&
                    SystemClock.elapsedRealtime() - backgroundSince > delay(a) * 1000L) locked = true
                hideInRecents(a)
            }
            override fun onActivityResumed(a: Activity) {
                if (a is LockActivity || !enabled(a)) return
                if (locked && !showing) {
                    showing = true
                    a.startActivity(LockActivity.intent(a, LockActivity.MODE_UNLOCK)
                        .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION))
                    a.overridePendingTransition(0, 0)
                }
            }
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {
                started = (started - 1).coerceAtLeast(0)
                if (started == 0 && !a.isChangingConfigurations) backgroundSince = SystemClock.elapsedRealtime()
            }
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
        // screen off → locked straight away
        runCatching {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) { if (enabled(c)) locked = true }
            }
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED)
            else app.registerReceiver(r, IntentFilter(Intent.ACTION_SCREEN_OFF))
        }
    }

    /** No picture of the app in the recent-apps list while the lock is on. */
    fun hideInRecents(a: Activity) {
        if (Build.VERSION.SDK_INT >= 33) runCatching { a.setRecentsScreenshotEnabled(!enabled(a)) }
    }
}
