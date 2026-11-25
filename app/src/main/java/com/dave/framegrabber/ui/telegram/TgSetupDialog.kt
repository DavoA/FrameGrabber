package com.dave.framegrabber.ui.telegram

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.dave.framegrabber.R
import com.dave.framegrabber.telegram.TelegramApi
import com.dave.framegrabber.telegram.TgStore

/**
 * ⚙ → «Telegram»: bot token + channel, then a real check: the bot exists, the channel is found,
 * the bot is an admin there and may post. Saved only when the check passes.
 */
class TgSetupDialog(private val act: Activity, private val onSaved: () -> Unit = {}) {

    private val store = TgStore(act)
    private val d = act.resources.displayMetrics.density
    private val ui = Handler(Looper.getMainLooper())

    fun show() {
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), 0)
        }
        box.addView(text(
            "1. В Telegram открой @BotFather → /newbot → получишь токен.\n" +
            "2. Добавь бота в свой канал администратором с правом «Публикация сообщений».\n" +
            "3. Вставь токен и канал ниже.", 13f, R.color.textDim))
        box.addView(label("Токен бота"))
        val token = input("123456789:AA…", store.token).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        box.addView(token)
        box.addView(label("Канал"))
        val chat = input("@mychannel или -100…", store.chat)
        box.addView(chat)
        box.addView(text("Для закрытого канала — его id (-100…). Его можно узнать, переслав пост из канала боту @userinfobot.", 11f, R.color.textDim))
        val progress = ProgressBar(act).apply { visibility = View.GONE; isIndeterminate = true }
        box.addView(progress)
        val result = text("", 14f, R.color.text).apply { visibility = View.GONE }
        box.addView(result)

        val dlg = AlertDialog.Builder(act)
            .setTitle("Telegram-канал")
            .setView(ScrollView(act).apply { addView(box) })
            .setPositiveButton("Проверить и сохранить", null)
            .setNegativeButton("Отмена", null)
            .create()
        dlg.setOnShowListener {
            val ok = dlg.getButton(AlertDialog.BUTTON_POSITIVE)
            ok.setOnClickListener {
                val t = token.text.toString().trim()
                var c = chat.text.toString().trim()
                    .removePrefix("https://t.me/").removePrefix("t.me/")
                if (c.isNotEmpty() && !c.startsWith("@") && !c.startsWith("-") && c.toLongOrNull() == null) c = "@$c"
                if (t.isEmpty() || c.isEmpty()) { show(result, "Заполни токен и канал", R.color.danger); return@setOnClickListener }
                ok.isEnabled = false
                progress.visibility = View.VISIBLE
                show(result, "Проверяю…", R.color.textDim)
                Thread {
                    val r = runCatching { check(t, c) }
                    ui.post {
                        ok.isEnabled = true
                        progress.visibility = View.GONE
                        r.onSuccess { msg ->
                            store.token = t; store.chat = c
                            show(result, msg, R.color.accent)
                            onSaved()
                            ui.postDelayed({ dlg.dismiss() }, 1500)
                        }.onFailure { show(result, "✗ " + (it.message ?: it.javaClass.simpleName), R.color.danger) }
                    }
                }.start()
            }
        }
        dlg.show()
    }

    /** Throws with a clear message if something's wrong; returns the "all good" text. */
    private fun check(token: String, chat: String): String {
        val api = TelegramApi(token)
        val bot = runCatching { api.getMe() }.getOrElse {
            if (it is java.io.IOException) throw IllegalStateException("Нет связи с Telegram. Проверь интернет.")
            throw it
        }
        val ch = api.getChat(chat)
        val (status, canPost) = api.memberStatus(chat, bot.id)
        if (status != "administrator" && status != "creator") throw IllegalStateException(
            "Бот @${bot.username} не администратор в «${ch.title}». Добавь его в админы канала.")
        if (!canPost) throw IllegalStateException("У бота нет права «Публикация сообщений» в «${ch.title}».")
        store.botName = "@" + bot.username
        store.chatTitle = ch.title
        store.chatUsername = ch.username ?: ""
        return "✓ Всё готово: @${bot.username} публикует в «${ch.title}»"
    }

    private fun show(v: TextView, msg: String, color: Int) {
        v.visibility = View.VISIBLE; v.text = msg; v.setTextColor(act.getColor(color))
    }

    private fun label(s: String) = text(s, 13f, R.color.accent).apply { setPadding(0, (14 * d).toInt(), 0, (4 * d).toInt()) }

    private fun text(s: String, size: Float, color: Int) = TextView(act).apply {
        text = s; textSize = size; setTextColor(act.getColor(color))
        setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
    }

    private fun input(hint: String, value: String) = EditText(act).apply {
        this.hint = hint; setText(value)
        setBackgroundResource(R.drawable.bg_input)
        setTextColor(act.getColor(R.color.text)); setHintTextColor(act.getColor(R.color.textDim))
        textSize = 14f; isSingleLine = true
        minHeight = (46 * d).toInt()
        setPadding((12 * d).toInt(), 0, (12 * d).toInt(), 0)
    }
}
