package com.dave.framegrabber.site

import android.net.Uri

/**
 * Ad blocking for the site: known ad / tracking hosts (as in Brave's lists, for what this site
 * shows) and the site's own banner places. Used to drop ad pictures from the app screens and
 * to stop ad requests and hide banner blocks in the built-in browser.
 */
object Ads {
    /** Hosts (and their subdomains) that only serve ads, banners, counters. */
    private val HOSTS = listOf(
        // seen on the site
        "vpipi.com", "vsexshop.ru", "dynspt.com", "intimlife.net", "intimshop.ru", "onlyall.net", "traffic.ru",
        // ad / tracking networks
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "google-analytics.com", "googletagmanager.com",
        "adriver.ru", "adfox.ru", "an.yandex.ru", "mc.yandex.ru", "yandexadexchange.net", "top-fwz1.mail.ru", "counter.yadro.ru",
        "liveinternet.ru", "exoclick.com", "exosrv.com", "juicyads.com", "trafficstars.com", "tsyndicate.com", "magsrv.com",
        "realsrv.com", "adspyglass.com", "adsterra.com", "propellerads.com", "clickadu.com", "hilltopads.net", "ero-advertising.com",
        "trafficfactory.biz", "twinred.com", "a-ads.com", "popads.net", "popcash.net", "adcash.com", "chaturbate.com", "stripchat.com",
        "bongacams.com", "bngpt.com", "bngprm.com", "camsoda.com", "livejasmin.com", "awempire.com", "crakrevenue.com",
        "go.xlivrdr.com", "xlivrdr.com", "imglnkd.com", "imglnke.com", "tsyndicate.com", "adtng.com", "trafficjunky.net",
    )
    /** Parts of addresses that mean an ad place. */
    private val PATHS = listOf("/banner", "banners/", "show=advert", "promo.php", "/adv/", "/ads/", "adriver", "/popunder", "pornlab-iframe")

    fun isAd(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val host = runCatching { Uri.parse(url).host }.getOrNull()?.lowercase() ?: return false
        if (HOSTS.any { host == it || host.endsWith(".$it") }) return true
        val u = url.lowercase()
        return PATHS.any { it in u }
    }

    /** Hides the banner blocks of the site's pages (added to every page in the built-in browser). */
    const val CSS = ".vsexshop-banner,.forum-promo,.promo-link,.sb2-block.a-rel,[id^='adriver'],[id*='banner'],[class*='banner']," +
        "iframe[src*='promo'],iframe[src*='dynspt'],iframe[src*='banner'],a[href*='show=advert'],a[href*='vsexshop'],a[href*='intimlife'],a[href*='intimshop']," +
        "img[src*='vpipi.com'],ins.adsbygoogle,#bn-top,#bn-bot,.bn-top,.bn-bot,div[id^='bn-']{display:none!important;height:0!important}"

    /** Removes ad blocks from a page's HTML before it is read by the app screens. */
    fun strip(html: String): String = html
        .replace(Regex("""<div class="vsexshop-banner".*?</script>""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""<iframe[^>]*>.*?</iframe>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
        .replace(Regex("""<a [^>]*href="([^"]+)"[^>]*>.*?</a>""", RegexOption.DOT_MATCHES_ALL)) { m -> if (isAd(m.groupValues[1])) "" else m.value }
}
