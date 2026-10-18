package com.abhinavxt.debforge.domain

/**
 * Ad and pop-under networks the Browser tab blocks: their scripts, their
 * video ads (which would otherwise fill the video list) and redirects to
 * them. A domain covers its subdomains.
 *
 * shortcut: a short hand-picked list, not EasyList; switch to a downloadable
 * list if users report ads getting through on sites they use.
 */
object AdHosts {

    private val DOMAINS = setOf(
        // Display / video ad networks
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "imasdk.googleapis.com", "amazon-adsystem.com", "adnxs.com", "criteo.com", "criteo.net",
        "pubmatic.com", "rubiconproject.com", "openx.net", "casalemedia.com", "smartadserver.com",
        "adform.net", "yieldmo.com", "media.net", "taboola.com", "outbrain.com", "mgid.com",
        "revcontent.com", "spotxchange.com", "springserve.com", "teads.tv", "vidoomy.com",
        "moatads.com", "scorecardresearch.com", "zedo.com",
        // Pop-under / redirect networks common on file and streaming sites
        "popads.net", "popcash.net", "propellerads.com", "onclickads.net", "adsterra.com",
        "hilltopads.net", "exoclick.com", "exosrv.com", "juicyads.com", "trafficjunky.net",
        "trafficstars.com", "clickadu.com", "adcash.com", "a-ads.com", "admaven.com",
        "ad-maven.com", "richpush.co", "pushground.com", "galaksion.com", "monetag.com"
    )

    /** True for [host] ("ads.example.com") on a blocked domain or under one. */
    fun blocks(host: String?): Boolean {
        var h = host?.lowercase()?.trimEnd('.') ?: return false
        while (true) {
            if (h in DOMAINS) return true
            h = h.substringAfter('.', "").ifEmpty { return false }
        }
    }
}
