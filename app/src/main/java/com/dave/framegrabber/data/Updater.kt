package com.dave.framegrabber.data

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.dave.framegrabber.R
import com.dave.framegrabber.ui.tc
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Updates from inside the app: looks at the «latest» release on GitHub, downloads the new APK
 * (Android's download manager, with its own notification) and hands it to the system installer —
 * a new version installs over the old one, everything stays.
 */
object Updater {
    private const val API = "https://api.github.com/repos/DavoA/FrameGrabber/releases/tags/latest"
    private const val PREFS = "updater"
    private const val CHECK_EVERY = 6 * 3600_000L

    class Info(val version: String, val notes: String, val url: String, val size: Long)

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun current(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"

    fun auto(ctx: Context) = p(ctx).getBoolean("auto", true)
    fun setAuto(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("auto", on).apply()

    /** "3.12" > "3.9" > "3". */
    fun newer(a: String, b: String): Boolean {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c > 0
        }
        return false
    }

    /** The newest build on GitHub (network; not on the main thread). */
    fun fetch(): Info {
        val c = URL(API).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 20000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "FrameGrabber")
        try {
            if (c.responseCode != 200) throw IllegalStateException("GitHub ответил ${c.responseCode}")
            val o = JSONObject(c.inputStream.bufferedReader().readText())
            val name = o.optString("name")
            val v = Regex("""\(([\d.]+)\)""").find(name)?.groupValues?.get(1)
                ?: throw IllegalStateException("Не понял номер версии: $name")
            val assets = o.getJSONArray("assets")
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk") } ?: throw IllegalStateException("В релизе нет APK")
            val notes = o.optString("body").lines().filterNot { it.startsWith("Коммит:") }.joinToString("\n").trim()
            return Info(v, notes, apk.getString("browser_download_url"), apk.optLong("size"))
        } finally { c.disconnect() }
    }

    // ---------------------------------------------------------------- asking

    /** At the start of the app: once in a while, quietly; a dialog only when there is something new. */
    fun autoCheck(a: Activity) {
        cleanOld(a)
        if (!auto(a)) return
        val last = p(a).getLong("checked", 0)
        if (System.currentTimeMillis() - last < CHECK_EVERY) { resumePending(a); return }
        io.execute {
            val info = runCatching { fetch() }.getOrNull() ?: return@execute
            p(a).edit().putLong("checked", System.currentTimeMillis()).apply()
            ui.post {
                if (a.isFinishing || a.isDestroyed) return@post
                if (newer(info.version, current(a)) && p(a).getString("skip", "") != info.version) offer(a, info, quiet = true)
            }
        }
    }

    /** «Проверить обновления» in the settings. */
    fun checkNow(a: Activity) {
        a.toastShort("Проверяю…")
        io.execute {
            val r = runCatching { fetch() }
            ui.post {
                if (a.isFinishing || a.isDestroyed) return@post
                r.onFailure { a.toastShort("Не удалось проверить: ${it.message}") }
                r.onSuccess { info ->
                    p(a).edit().putLong("checked", System.currentTimeMillis()).apply()
                    if (newer(info.version, current(a))) offer(a, info, quiet = false)
                    else a.toastShort("У тебя последняя версия (${current(a)})")
                }
            }
        }
    }

    private fun offer(a: Activity, info: Info, quiet: Boolean) {
        val mb = if (info.size > 0) " · ${info.size / 1_048_576} МБ" else ""
        val b = AlertDialog.Builder(a, R.style.DialogTheme)
            .setTitle("Вышла версия ${info.version}")
            .setMessage("Сейчас у тебя ${current(a)}$mb\n\n" + info.notes.ifBlank { "Новая сборка." })
            .setPositiveButton("Обновить") { _, _ -> start(a, info) }
            .setNegativeButton("Позже", null)
        if (quiet) b.setNeutralButton("Пропустить") { _, _ -> p(a).edit().putString("skip", info.version).apply() }
        b.show()
    }

    // ---------------------------------------------------------------- downloading

    private fun dir(ctx: Context) = File(ctx.getExternalFilesDir(null), "update").apply { mkdirs() }
    private fun fileFor(ctx: Context, v: String) = File(dir(ctx), "FrameGrabber-$v.apk")

    /** Downloaded APKs of versions already installed are not needed any more. */
    private fun cleanOld(ctx: Context) {
        val cur = current(ctx)
        dir(ctx).listFiles()?.forEach { f ->
            val v = f.name.removePrefix("FrameGrabber-").removeSuffix(".apk")
            if (!newer(v, cur)) f.delete()
        }
    }

    fun start(a: Activity, info: Info) {
        val f = fileFor(a, info.version)
        if (f.exists() && info.size > 0 && f.length() == info.size) { install(a, f); return }
        val dm = a.getSystemService(DownloadManager::class.java)
        // already downloading this version: just show its progress
        val oldId = p(a).getLong("dl", -1)
        if (oldId >= 0 && p(a).getString("dlv", "") == info.version && status(dm, oldId).first in
            listOf(DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING, DownloadManager.STATUS_PAUSED)) {
            progress(a, dm, oldId, f, info); return
        }
        f.delete()
        val req = DownloadManager.Request(Uri.parse(info.url))
            .setTitle("FrameGrabber ${info.version}")
            .setDescription("Обновление приложения")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(f))
        val id = runCatching { dm.enqueue(req) }.getOrElse { a.toastShort("Не удалось начать загрузку: ${it.message}"); return }
        p(a).edit().putLong("dl", id).putString("dlv", info.version).putLong("dls", info.size).apply()
        progress(a, dm, id, f, info)
    }

    /** status, downloaded bytes, total bytes */
    private fun status(dm: DownloadManager, id: Long): Triple<Int, Long, Long> {
        dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (c.moveToFirst()) return Triple(
                c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)))
        }
        return Triple(-1, 0, 0)
    }

    private fun progress(a: Activity, dm: DownloadManager, id: Long, f: File, info: Info) {
        val d = a.resources.displayMetrics.density
        val bar = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        val txt = TextView(a).apply { textSize = 13f; setTextColor(a.tc(R.color.textDim)); setPadding(0, (8 * d).toInt(), 0, 0) }
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((22 * d).toInt(), (12 * d).toInt(), (22 * d).toInt(), 0)
            addView(bar); addView(txt)
        }
        val dlg = AlertDialog.Builder(a, R.style.DialogTheme)
            .setTitle("Скачиваю ${info.version}")
            .setView(box)
            .setPositiveButton("В фоне", null)
            .setNegativeButton("Отменить") { _, _ -> dm.remove(id); p(a).edit().remove("dl").apply() }
            .setCancelable(false)
            .show()
        val tick = object : Runnable {
            override fun run() {
                if (!dlg.isShowing || a.isDestroyed) return
                val (st, got, total) = status(dm, id)
                val all = if (total > 0) total else info.size
                if (all > 0) bar.progress = (got * 1000 / all).toInt()
                txt.text = "${got / 1_048_576} из ${all / 1_048_576} МБ" + if (st == DownloadManager.STATUS_PAUSED) " — жду сеть" else ""
                when (st) {
                    DownloadManager.STATUS_SUCCESSFUL -> { dlg.dismiss(); p(a).edit().remove("dl").apply(); install(a, f) }
                    DownloadManager.STATUS_FAILED, -1 -> { dlg.dismiss(); p(a).edit().remove("dl").apply(); f.delete(); a.toastShort("Загрузка не удалась — попробуй ещё раз") }
                    else -> ui.postDelayed(this, 500)
                }
            }
        }
        ui.post(tick)
    }

    // ---------------------------------------------------------------- installing

    /** Downloaded in the background meanwhile, or waiting for the «install apps» permission. */
    fun resumePending(a: Activity) {
        val v = p(a).getString("dlv", null) ?: return
        val f = fileFor(a, v)
        val size = p(a).getLong("dls", 0)
        if (!newer(v, current(a)) || !f.exists() || (size > 0 && f.length() != size)) return
        if (p(a).getLong("dl", -1) >= 0) {
            val dm = a.getSystemService(DownloadManager::class.java)
            if (status(dm, p(a).getLong("dl", -1)).first != DownloadManager.STATUS_SUCCESSFUL) return
            p(a).edit().remove("dl").apply()
        }
        if (p(a).getBoolean("askedPerm", false) && !a.packageManager.canRequestPackageInstalls()) return
        AlertDialog.Builder(a, R.style.DialogTheme)
            .setTitle("Версия $v скачана")
            .setMessage("Установить сейчас?")
            .setPositiveButton("Установить") { _, _ -> install(a, f) }
            .setNegativeButton("Позже", null).show()
    }

    /** Called from onResume: back from the «install unknown apps» screen. */
    fun onResume(a: Activity) {
        if (!p(a).getBoolean("askedPerm", false)) return
        if (!a.packageManager.canRequestPackageInstalls()) return
        p(a).edit().putBoolean("askedPerm", false).apply()
        val v = p(a).getString("dlv", null) ?: return
        val f = fileFor(a, v)
        if (f.exists() && newer(v, current(a))) install(a, f)
    }

    fun install(a: Activity, f: File) {
        if (!a.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(a, R.style.DialogTheme)
                .setTitle("Нужно разрешение")
                .setMessage("Один раз: Android попросит разрешить FrameGrabber устанавливать приложения. Включи «Разрешить» и вернись назад — установка продолжится сама.")
                .setPositiveButton("Открыть") { _, _ ->
                    p(a).edit().putBoolean("askedPerm", true).apply()
                    runCatching {
                        a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.packageName)))
                    }.onFailure { a.toastShort("Не открылось: ${it.message}") }
                }
                .setNegativeButton("Отмена", null).show()
            return
        }
        a.toastShort("Готовлю установку…")
        val app = a.applicationContext
        io.execute {
            val r = runCatching {
                val pi = app.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(app.packageName)
                params.setSize(f.length())
                val sid = pi.createSession(params)
                pi.openSession(sid).use { s ->
                    s.openWrite("FrameGrabber.apk", 0, f.length()).use { out ->
                        f.inputStream().use { it.copyTo(out, 1 shl 16) }
                        s.fsync(out)
                    }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val cb = PendingIntent.getBroadcast(app, 7, Intent(app, InstallReceiver::class.java).setPackage(app.packageName), flags)
                    s.commit(cb.intentSender)
                }
            }
            r.onFailure { e -> ui.post { app.toastShort("Установка не удалась: ${e.message}") } }
        }
    }

    /** The installer's answer: show its «Обновить?» screen, or say what went wrong. */
    class InstallReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                                  else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    confirm?.let { runCatching { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
                }
                PackageInstaller.STATUS_SUCCESS -> {}
                PackageInstaller.STATUS_FAILURE_ABORTED -> ctx.toastShort("Установка отменена")
                else -> ctx.toastShort("Установка не удалась: " + (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "ошибка"))
            }
        }
    }

    private fun Context.toastShort(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show()
}
