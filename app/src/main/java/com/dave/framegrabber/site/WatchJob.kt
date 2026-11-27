package com.dave.framegrabber.site

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.dave.framegrabber.MainActivity
import com.dave.framegrabber.R

/**
 * Once a day (with internet): for every followed model, the tracker's newest torrents with her
 * name; anything newer than last time → a notification («Новая раздача: …»). Uses the site
 * login of the app (cookies of the built-in browser).
 */
class WatchJob : JobService() {

    companion object {
        private const val JOB_ID = 4711
        private const val CHANNEL = "tracker"
        const val EXTRA_SITE_SEARCH = "siteSearch"
        const val EXTRA_TORRENT = "siteTorrent"

        /** On when someone is followed, off otherwise. */
        fun schedule(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java)
            if (SiteStore(ctx).followed.isEmpty()) { js.cancel(JOB_ID); return }
            if (js.getPendingJob(JOB_ID) != null) return
            js.schedule(JobInfo.Builder(JOB_ID, ComponentName(ctx, WatchJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(24L * 60 * 60 * 1000)
                .setPersisted(true)
                .build())
        }

        /**
         * Checks every followed model now (blocking, background thread). Returns the models with
         * new torrents → (name, their titles).
         */
        fun check(ctx: Context): List<Pair<String, List<String>>> {
            val store = SiteStore(ctx)
            val list = store.followed
            val news = ArrayList<Pair<String, List<String>>>()
            for (f in list) {
                val rows = runCatching { Lab.search(ctx, f.name, Lab.Sort.DATE, true, 0).rows }.getOrElse { e ->
                    if (e is Lab.NeedLogin) throw e else emptyList()
                }
                val newer = if (f.lastId == 0L) emptyList() else rows.filter { it.id > f.lastId }
                rows.maxOfOrNull { it.id }?.let { if (it > f.lastId) f.lastId = it }
                if (newer.isNotEmpty()) {
                    f.fresh = (newer.map { it.id } + f.fresh).distinct().take(30)
                    f.freshTitles = (newer.map { it.title } + f.freshTitles).distinct().take(30)
                    news += f.name to newer.map { it.title }
                }
                f.checked = System.currentTimeMillis()
            }
            store.saveFollowed(list)
            return news
        }

        fun notify(ctx: Context, news: List<Pair<String, List<String>>>) {
            if (news.isEmpty()) return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Новые раздачи моделей", NotificationManager.IMPORTANCE_DEFAULT))
            news.forEachIndexed { i, (name, titles) ->
                val open = Intent(ctx, MainActivity::class.java).putExtra(EXTRA_SITE_SEARCH, name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                nm.notify(9000 + name.lowercase().hashCode() % 1000, Notification.Builder(ctx, CHANNEL)
                    .setSmallIcon(R.drawable.ic_web)
                    .setContentTitle(if (titles.size == 1) "Новая раздача: $name" else "$name: новых раздач ${titles.size}")
                    .setContentText(titles.first())
                    .setStyle(Notification.BigTextStyle().bigText(titles.take(5).joinToString("\n") { "• $it" }))
                    .setContentIntent(PendingIntent.getActivity(ctx, i + 50, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                    .setAutoCancel(true)
                    .build())
            }
        }
    }

    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            val r = runCatching { notify(applicationContext, check(applicationContext)) }
            jobFinished(params, r.isFailure && r.exceptionOrNull() !is Lab.NeedLogin)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters) = true
}
