package com.abhinavxt.debforge.domain.adblock

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ConcurrentHashMap

/** Host helpers shared by the network and element filters. Hosts are lowercase. */
internal object Hosts {

    private val sites = ConcurrentHashMap<String, String>()

    /** The host of [url], lowercase; "" when it has none (about:blank, data:). */
    fun of(url: String): String {
        val scheme = url.indexOf("://")
        if (scheme < 0) return ""
        var start = scheme + 3
        var end = start
        while (end < url.length && url[end] != '/' && url[end] != '?' && url[end] != '#') end++
        val at = url.lastIndexOf('@', end - 1)
        if (at >= start) start = at + 1
        val port = url.indexOf(':', start)
        if (port in start until end && url[start] != '[') end = port
        return url.substring(start, end).lowercase().trimEnd('.')
    }

    /**
     * "a.b.example.co.uk" → "example.co.uk", by the public suffix list
     * OkHttp ships. The host itself when there is none (IPs, localhost).
     */
    fun site(host: String): String {
        sites[host]?.let { return it }
        val site = "https://$host/".toHttpUrlOrNull()?.topPrivateDomain() ?: host
        // A browsing session sees a few hundred hosts; this only stops it growing forever.
        if (sites.size > 4000) sites.clear()
        sites[host] = site
        return site
    }

    fun thirdParty(host: String, source: String): Boolean = site(host) != site(source)

    /** [host] and each parent domain, most specific first: a.b.com, b.com, com. */
    inline fun forEachSuffix(host: String, action: (String) -> Unit) {
        var h = host
        while (h.isNotEmpty()) {
            action(h)
            h = h.substringAfter('.', "")
        }
    }

    /**
     * Names filters written as "name.*" (any TLD) use for [host]:
     * "www.google.co.uk" → "www.google.*", "google.*".
     */
    fun entities(host: String): List<String> {
        val site = site(host)
        val suffix = site.substringAfter('.', "").ifEmpty { return emptyList() }
        val base = host.removeSuffix(".$suffix")
        val out = ArrayList<String>(2)
        forEachSuffix(base) { out += "$it.*" }
        return out
    }

    /** True when [host] is [name] or under it; "name.*" matches any TLD. */
    fun covers(name: String, host: String): Boolean =
        if (name.endsWith(".*")) name in entities(host)
        else host == name || (host.endsWith(name) && host.length > name.length && host[host.length - name.length - 1] == '.')
}

/** A filter's domain list ("a.com|~b.a.com|google.*"): where it applies, and where not. */
internal class DomainList private constructor(
    private val include: Array<String>,
    private val exclude: Array<String>
) {
    fun matches(host: String): Boolean {
        if (exclude.any { Hosts.covers(it, host) }) return false
        return include.isEmpty() || include.any { Hosts.covers(it, host) }
    }

    companion object {
        fun parse(list: String, separator: Char): DomainList? {
            val include = ArrayList<String>()
            val exclude = ArrayList<String>()
            for (raw in list.split(separator)) {
                val d = raw.trim().lowercase()
                when {
                    d.startsWith("~") -> d.drop(1).takeIf { it.isNotEmpty() }?.let { exclude += it }
                    d.isNotEmpty() -> include += d
                }
            }
            if (include.isEmpty() && exclude.isEmpty()) return null
            return DomainList(include.toTypedArray(), exclude.toTypedArray())
        }
    }
}
