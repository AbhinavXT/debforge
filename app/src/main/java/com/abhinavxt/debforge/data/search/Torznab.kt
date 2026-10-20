package com.abhinavxt.debforge.data.search

import com.abhinavxt.debforge.domain.SearchResult
import com.abhinavxt.debforge.domain.SearchResults
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.w3c.dom.Element
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/**
 * The Torznab API that Jackett and Prowlarr serve: `<feed>/api?t=search&q=…`
 * answers an RSS feed whose items carry `torznab:attr` extras (seeders,
 * infohash, magneturl). Errors come back as `<error code="…" description="…"/>`.
 *
 * The feed URL is what Jackett's "Copy Torznab Feed" and Prowlarr's per-indexer
 * link give, e.g. `http://192.168.1.5:9117/api/v2.0/indexers/all/results/torznab/`
 * or `http://192.168.1.5:9696/1/`. A URL already ending in `/api` works too.
 */
object Torznab {

    /** Thrown for an `<error>` reply. 100 = wrong API key (Torznab spec). */
    class TorznabError(val code: Int, description: String) : Exception(description)

    /** The `api` endpoint for [feedUrl] with [params] and the key; null if it isn't an http(s) URL. */
    fun apiUrl(feedUrl: String, apiKey: String, params: Map<String, String>): HttpUrl? {
        val base = feedUrl.trim().toHttpUrlOrNull() ?: return null
        val api = if (base.pathSegments.lastOrNull { it.isNotEmpty() } == "api") base
        else base.newBuilder().addPathSegment("api").build()
        return api.newBuilder()
            .apply {
                // The feed link may already carry its own apikey (Jackett includes it): ours wins.
                removeAllQueryParameters("apikey")
                params.forEach { (k, v) -> setQueryParameter(k, v) }
                if (apiKey.isNotBlank()) setQueryParameter("apikey", apiKey.trim())
            }
            .build()
    }

    /** The results of a `t=search` reply. Items without an info hash (nothing to add or check) are dropped. */
    fun parseResults(xml: String): List<SearchResult> {
        val root = parse(xml)
        throwIfError(root)
        val items = root.getElementsByTagName("item")
        return (0 until items.length).mapNotNull { i -> toResult(items.item(i) as Element) }
    }

    /** A `t=caps` reply: fine when it's `<caps>`, a [TorznabError] when it's `<error>`. */
    fun checkCaps(xml: String) {
        val root = parse(xml)
        throwIfError(root)
        if (root.tagName != "caps") throw TorznabError(0, "not a Torznab feed")
    }

    private fun toResult(item: Element): SearchResult? {
        val attrs = HashMap<String, String>()
        val attrNodes = item.getElementsByTagNameNS("*", "attr")
        for (j in 0 until attrNodes.length) {
            val a = attrNodes.item(j) as Element
            attrs[a.getAttribute("name").lowercase()] = a.getAttribute("value")
        }
        val title = item.childText("title")?.takeIf { it.isNotBlank() } ?: return null
        val link = item.childText("link")
        val enclosure = item.getElementsByTagName("enclosure").let { if (it.length > 0) it.item(0) as Element else null }
        val magnet = listOfNotNull(attrs["magneturl"], link, item.childText("guid"), enclosure?.getAttribute("url"))
            .firstOrNull { it.startsWith("magnet:", ignoreCase = true) }
        val hash = (attrs["infohash"]?.takeIf { it.isNotBlank() } ?: magnet?.let(::hashFromMagnet))
            ?.lowercase() ?: return null
        val size = item.childText("size")?.toLongOrNull()
            ?: attrs["size"]?.toLongOrNull()
            ?: enclosure?.getAttribute("length")?.toLongOrNull()
            ?: 0L
        return SearchResult(
            hash = hash,
            title = title.trim(),
            magnet = magnet ?: SearchResults.magnetFor(hash, title.trim()),
            size = size,
            seeders = attrs["seeders"]?.toIntOrNull(),
            age = item.childText("pubDate")?.let(::ageOf),
            tracker = item.childText("jackettindexer") ?: item.childText("prowlarrindexer") ?: attrs["indexer"],
            cached = null,
            owned = null
        )
    }

    /** "magnet:?xt=urn:btih:<hash>&…" → the hex hash. Base32 hashes are converted. */
    fun hashFromMagnet(magnet: String): String? {
        val raw = Regex("""xt=urn:btih:([A-Za-z0-9]+)""").find(magnet)?.groupValues?.get(1) ?: return null
        return when (raw.length) {
            40 -> raw.lowercase()
            32 -> base32ToHex(raw)
            else -> null
        }
    }

    private fun base32ToHex(s: String): String? {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val bits = StringBuilder()
        for (c in s.uppercase()) {
            val v = alphabet.indexOf(c).takeIf { it >= 0 } ?: return null
            bits.append(Integer.toBinaryString(v).padStart(5, '0'))
        }
        return bits.chunked(4).joinToString("") { Integer.toHexString(it.toInt(2)) }
    }

    /** "Tue, 14 Oct 2026 08:00:00 +0000" → "6d" / "3mo" / "2y"; null if unreadable. */
    fun ageOf(pubDate: String, now: Long = System.currentTimeMillis()): String? {
        // The weekday is dropped: some trackers get it wrong, and the strict parser then refuses the date.
        val millis = runCatching {
            java.time.ZonedDateTime.parse(pubDate.trim().substringAfter(", "), PUB_DATE).toInstant().toEpochMilli()
        }.getOrNull() ?: return null
        val days = ((now - millis) / 86_400_000L).coerceAtLeast(0)
        return when {
            days < 1 -> "<1d"
            days < 60 -> "${days}d"
            days < 730 -> "${days / 30}mo"
            else -> "${days / 365}y"
        }
    }

    private val PUB_DATE = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy HH:mm:ss Z", java.util.Locale.ENGLISH)

    private fun parse(xml: String): Element {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            // No DTDs or external entities from a server we don't control.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        }
        return factory.newDocumentBuilder().parse(InputSource(StringReader(xml))).documentElement
    }

    private fun throwIfError(root: Element) {
        if (root.tagName == "error") {
            throw TorznabError(root.getAttribute("code").toIntOrNull() ?: 0, root.getAttribute("description").ifBlank { "error" })
        }
    }

    private fun Element.childText(name: String): String? {
        var n = firstChild
        while (n != null) {
            if (n is Element && (n.localName ?: n.tagName) == name) return n.textContent?.trim()
            n = n.nextSibling
        }
        return null
    }
}
