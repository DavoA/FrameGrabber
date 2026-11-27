package com.dave.framegrabber.ui

import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.dave.framegrabber.R
import com.dave.framegrabber.data.AppLock

/**
 * The PIN screen. MODE_UNLOCK covers the app until the PIN (or fingerprint) is given;
 * MODE_SET / MODE_CHANGE / MODE_OFF are used from the settings to set, change or remove it.
 */
class LockActivity : Activity() {

    companion object {
        const val MODE_UNLOCK = 0
        const val MODE_SET = 1
        const val MODE_CHANGE = 2
        const val MODE_OFF = 3
        private const val EXTRA_MODE = "mode"
        fun intent(ctx: Context, mode: Int) = Intent(ctx, LockActivity::class.java).putExtra(EXTRA_MODE, mode)
    }

    private enum class Step { CHECK, NEW, REPEAT }

    private val d get() = resources.displayMetrics.density
    private val h = Handler(Looper.getMainLooper())
    private var mode = MODE_UNLOCK
    private var step = Step.CHECK
    private val typed = StringBuilder()
    private var firstNew = ""
    private lateinit var title: TextView
    private lateinit var hint: TextView
    private lateinit var dots: LinearLayout
    private var bioCancel: CancellationSignal? = null
    private var bioAsked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = intent.getIntExtra(EXTRA_MODE, MODE_UNLOCK)
        step = if (mode == MODE_SET) Step.NEW else Step.CHECK
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)

        val root = FrameLayout(this).apply { setBackgroundColor(tc(R.color.bg)) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        root.addView(col, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        if (Build.VERSION.SDK_INT >= 30) col.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars()); v.setPadding(b.left, b.top, b.right, b.bottom); insets
        }

        col.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        // the logo in the scheme's color
        val ta = theme.obtainStyledAttributes(intArrayOf(R.attr.logoMark))
        val logo = ta.getResourceId(0, R.drawable.logo_mark); ta.recycle()
        col.addView(ImageView(this).apply { setImageResource(logo) }, LinearLayout.LayoutParams((64 * d).toInt(), (64 * d).toInt()))
        title = TextView(this).apply {
            textSize = 20f; setTextColor(tc(R.color.text)); gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        col.addView(title, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (18 * d).toInt() })
        hint = TextView(this).apply { textSize = 13f; setTextColor(tc(R.color.textDim)); gravity = Gravity.CENTER; setPadding((24 * d).toInt(), 0, (24 * d).toInt(), 0) }
        col.addView(hint, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (6 * d).toInt() })
        dots = LinearLayout(this).apply { gravity = Gravity.CENTER }
        col.addView(dots, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (26 * d).toInt() })
        col.addView(View(this), LinearLayout.LayoutParams(1, 0, 0.6f))
        col.addView(keypad(), LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = (34 * d).toInt() })
        if (mode != MODE_UNLOCK) col.addView(TextView(this).apply {
            text = "Отмена"; textSize = 15f; setTextColor(tc(R.color.textDim)); setPadding((20 * d).toInt(), (10 * d).toInt(), (20 * d).toInt(), (10 * d).toInt())
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = (18 * d).toInt() })
        refresh()
    }

    // ---------------------------------------------------------------- views

    private fun keypad(): View {
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "bio", "0", "del")
        val size = (74 * d).toInt(); val gap = (12 * d).toInt()
        keys.chunked(3).forEach { row ->
            val r = LinearLayout(this)
            row.forEach { k ->
                val cell: View = when (k) {
                    "bio" -> ImageView(this).apply {
                        setImageResource(R.drawable.ic_fingerprint); setColorFilter(tc(R.color.accent))
                        scaleType = ImageView.ScaleType.CENTER; contentDescription = "Отпечаток"
                        visibility = if (mode == MODE_UNLOCK && canBio()) View.VISIBLE else View.INVISIBLE
                        setOnClickListener { askBio() }
                    }
                    "del" -> TextView(this).apply {
                        text = "⌫"; textSize = 24f; gravity = Gravity.CENTER; setTextColor(tc(R.color.textDim)); contentDescription = "Стереть"
                        setOnClickListener { if (typed.isNotEmpty()) { typed.setLength(typed.length - 1); refresh() } }
                        setOnLongClickListener { typed.setLength(0); refresh(); true }
                    }
                    else -> TextView(this).apply {
                        text = k; textSize = 28f; gravity = Gravity.CENTER; setTextColor(tc(R.color.text))
                        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(tc(R.color.surface)); setStroke((1 * d).toInt(), tc(R.color.stroke)) }
                        setOnClickListener { press(k[0]); it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
                    }
                }
                cell.isClickable = true
                r.addView(cell, LinearLayout.LayoutParams(size, size).apply { setMargins(gap, gap / 2, gap, gap / 2) })
            }
            grid.addView(r)
        }
        return grid
    }

    private fun refresh() {
        title.text = when {
            mode == MODE_UNLOCK -> "FrameGrabber закрыт"
            step == Step.CHECK -> "Введи текущий PIN"
            step == Step.NEW -> "Придумай PIN из 4 цифр"
            else -> "Повтори PIN"
        }
        val wait = AppLock.waitUntil - SystemClock.elapsedRealtime()
        hint.text = when {
            wait > 0 -> "Слишком много попыток. Подожди ${(wait / 1000) + 1} сек"
            mode == MODE_UNLOCK -> if (canBio()) "Введи PIN или приложи палец" else "Введи PIN"
            step == Step.NEW -> "Если забудешь PIN, войти можно будет только после очистки данных приложения"
            else -> ""
        }
        if (wait > 0) h.postDelayed({ if (!isDestroyed) refresh() }, 1000)
        dots.removeAllViews()
        repeat(AppLock.PIN_LEN) { i ->
            dots.addView(View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    if (i < typed.length) setColor(tc(R.color.accent)) else setStroke((1.5f * d).toInt(), tc(R.color.textDim))
                }
            }, LinearLayout.LayoutParams((14 * d).toInt(), (14 * d).toInt()).apply { setMargins((9 * d).toInt(), 0, (9 * d).toInt(), 0) })
        }
    }

    private fun press(c: Char) {
        if (AppLock.waitUntil > SystemClock.elapsedRealtime()) return
        if (typed.length >= AppLock.PIN_LEN) return
        typed.append(c); refresh()
        if (typed.length == AppLock.PIN_LEN) h.postDelayed({ entered(typed.toString()) }, 120)
    }

    private fun entered(pin: String) {
        typed.setLength(0)
        when (step) {
            Step.CHECK -> {
                if (!AppLock.check(this, pin)) { wrong(); return }
                AppLock.fails = 0
                when (mode) {
                    MODE_UNLOCK -> { done(); return }
                    MODE_OFF -> { AppLock.disable(this); toast("Защита выключена"); finish(); return }
                    else -> step = Step.NEW
                }
            }
            Step.NEW -> { firstNew = pin; step = Step.REPEAT }
            Step.REPEAT -> {
                if (pin != firstNew) { step = Step.NEW; shake(); refresh(); hint.text = "PIN не совпал — придумай заново"; return }
                AppLock.setPin(this, pin)
                toast(if (mode == MODE_CHANGE) "PIN изменён" else "Защита включена")
                finish(); return
            }
        }
        refresh()
    }

    private fun wrong() {
        AppLock.fails++
        if (AppLock.fails >= 5) { AppLock.waitUntil = SystemClock.elapsedRealtime() + 30_000; AppLock.fails = 0 }
        shake(); refresh()
        if (AppLock.waitUntil <= SystemClock.elapsedRealtime()) hint.text = "Неверный PIN"
    }

    private fun shake() {
        dots.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        ObjectAnimator.ofFloat(dots, "translationX", 0f, 22f, -22f, 16f, -16f, 8f, -8f, 0f).setDuration(380).start()
    }

    private fun done() {
        AppLock.unlocked()
        finish()
        overridePendingTransition(0, android.R.anim.fade_out)
    }

    private fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()

    // ---------------------------------------------------------------- fingerprint

    private fun canBio(): Boolean {
        if (!AppLock.bio(this)) return false
        val bm = getSystemService(BiometricManager::class.java) ?: return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= 30) bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
            else @Suppress("DEPRECATION") (bm.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS)
        }.getOrDefault(false)
    }

    private fun askBio() {
        if (!canBio() || isFinishing) return
        bioCancel?.cancel()
        val cancel = CancellationSignal().also { bioCancel = it }
        val ex = mainExecutor
        runCatching {
            BiometricPrompt.Builder(this)
                .setTitle("FrameGrabber")
                .setSubtitle("Приложи палец, чтобы открыть")
                .setNegativeButton("Ввести PIN", ex) { _, _ -> }
                .build()
                .authenticate(cancel, ex, object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { done() }
                })
        }
    }

    override fun onResume() {
        super.onResume()
        if (mode == MODE_UNLOCK && !bioAsked) { bioAsked = true; h.postDelayed({ if (!isFinishing) askBio() }, 250) }
    }

    override fun onStop() {
        super.onStop()
        // the next time the screen comes back, offer the finger again
        if (!isChangingConfigurations) bioAsked = false
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (mode == MODE_UNLOCK) moveTaskToBack(true) else super.onBackPressed()
    }

    override fun onDestroy() {
        bioCancel?.cancel()
        h.removeCallbacksAndMessages(null)
        if (mode == MODE_UNLOCK) AppLock.lockScreenGone()
        super.onDestroy()
    }
}
