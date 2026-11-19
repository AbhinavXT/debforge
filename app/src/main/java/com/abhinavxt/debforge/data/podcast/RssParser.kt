package com.abhinavxt.debforge.data.podcast

import com.abhinavxt.debforge.data.net.SecureXml
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import java.io.IOException
import java.io.InputStream
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class PodcastFeed(
    val title: String,
    val description: String,
    val imageUrl: String?,
    val author: String?,
    val episodes: List<PodcastEpisode>,
) {
    /** Episodes newest first; undated (0) ones sink to the end. Stable for equal dates. */
    fun episodesNewestFirst(): List<PodcastEpisode> = episodes.sortedByDescending { it.publishedAtMillis }
}

data class PodcastEpisode(
    val guid: String,
    val title: String,
    val description: String,
    val enclosureUrl: String,
    val enclosureType: String?,
    val enclosureLength: Long?,
    /** Epoch millis, 0 when missing or unparseable. */
    val publishedAtMillis: Long,
    val durationSeconds: Int?,
    val imageUrl: String?,
)

/** The input parsed as XML but is not an RSS feed (for example Atom). Atom is not supported. */
class UnsupportedFeedException(message: String) : Exception(message)

/** The input is not well-formed XML, or uses a forbidden construct such as a DOCTYPE. */
class MalformedFeedException(message: String, cause: Throwable? = null) : Exception(message, cause)

object RssParser {
    private const val ITUNES = "http://www.itunes.com/dtds/podcast-1.0.dtd"

    fun parse(input: InputStream): PodcastFeed {
        val root = try {
            SecureXml.documentBuilderFactory().newDocumentBuilder().parse(InputSource(input)).documentElement
        } catch (e: SAXException) {
            throw MalformedFeedException("Malformed feed XML", e)
        } catch (e: IOException) {
            throw MalformedFeedException("Could not read feed", e)
        }
        if (root.localName != "rss") throw UnsupportedFeedException("Not a podcast RSS feed")
        val channel = root.children().firstOrNull { it.localName == "channel" }
            ?: throw UnsupportedFeedException("Not a podcast RSS feed")

        val image = channel.children().firstOrNull { it.isItunes("image") }?.getAttribute("href")?.trim()?.ifEmpty { null }
            ?: channel.children().firstOrNull { it.localName == "image" && it.namespaceURI == null }
                ?.child("url")?.text()?.ifEmpty { null }
        val episodes = channel.children().filter { it.localName == "item" && it.namespaceURI == null }.mapNotNull(::episode)
        return PodcastFeed(
            title = channel.child("title")?.text().orEmpty(),
            description = plainText(channel.child("description")?.text() ?: channel.itunes("summary").orEmpty()),
            imageUrl = image,
            author = channel.itunes("author")?.ifEmpty { null },
            episodes = episodes,
        )
    }

    private fun episode(item: Element): PodcastEpisode? {
        val enclosure = item.children().filter { it.localName == "enclosure" && it.namespaceURI == null }
            .firstOrNull { e ->
                val url = e.getAttribute("url").trim()
                val type = e.getAttribute("type").trim().lowercase(Locale.ROOT)
                url.isNotEmpty() && (type.isEmpty() || type.startsWith("audio/") || type.startsWith("video/") ||
                    type == "application/ogg")
            } ?: return null
        val url = enclosure.getAttribute("url").trim()
        val title = item.child("title")?.text().orEmpty()
        val link = item.child("link")?.text().orEmpty()
        val guid = item.child("guid")?.text()?.ifEmpty { null } ?: url
        val desc = item.child("description")?.text()?.ifEmpty { null }
            ?: item.child("encoded")?.text()?.ifEmpty { null }
            ?: item.itunes("summary").orEmpty()
        return PodcastEpisode(
            guid = guid.ifEmpty { link + title },
            title = title,
            description = plainText(desc),
            enclosureUrl = url,
            enclosureType = enclosure.getAttribute("type").trim().ifEmpty { null },
            enclosureLength = enclosure.getAttribute("length").trim().toLongOrNull(),
            publishedAtMillis = item.child("pubDate")?.text()?.let(::parseRfc822) ?: 0L,
            durationSeconds = item.itunes("duration")?.let(::parseDuration),
            imageUrl = item.children().firstOrNull { it.isItunes("image") }?.getAttribute("href")?.trim()?.ifEmpty { null },
        )
    }

    /** "90", "12:34", "1:02:03" to seconds; null when unusable. */
    fun parseDuration(raw: String): Int? {
        val parts = raw.trim().split(':')
        if (parts.size > 3) return null
        var total = 0.0
        for (p in parts) total = total * 60 + (p.trim().toDoubleOrNull()?.takeIf { it >= 0 } ?: return null)
        return total.toInt()
    }

    private val ZONES = mapOf(
        "GMT" to "+0000", "UT" to "+0000", "UTC" to "+0000", "Z" to "+0000",
        "EST" to "-0500", "EDT" to "-0400", "CST" to "-0600", "CDT" to "-0500",
        "MST" to "-0700", "MDT" to "-0600", "PST" to "-0800", "PDT" to "-0700",
    )
    private val DATE = Regex(
        """^(?:[A-Za-z]{3,9},?\s+)?(\d{1,2})\s+([A-Za-z]{3,9})\s+(\d{2,4})\s+(\d{1,2}):(\d{2})(?::(\d{2}))?(?:\s*(\S+))?$""",
    )
    private val OFFSET = Regex("""^([+-])(\d{2}):?(\d{2})$""")
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    /** RFC 822 date with common real-world variants; 0 when unparseable. */
    fun parseRfc822(raw: String): Long {
        val s = raw.trim()
        val m = DATE.matchEntire(s)
        if (m != null) {
            val (d, mon, y, h, mi, sec, zone) = m.destructured
            val month = MONTHS.indexOf(mon.take(3).lowercase(Locale.ROOT)) + 1
            if (month > 0) {
                var year = y.toInt()
                if (y.length == 2) year += if (year < 50) 2000 else 1900
                val millis = runCatching {
                    ZonedDateTime.of(year, month, d.toInt(), h.toInt(), mi.toInt(), sec.ifEmpty { "0" }.toInt(), 0, zoneOffset(zone))
                        .toInstant().toEpochMilli()
                }.getOrNull()
                if (millis != null) return millis
            }
        }
        // ISO 8601 as a courtesy: some hosts emit it.
        return runCatching { ZonedDateTime.parse(s, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli() }
            .getOrDefault(0L)
    }

    private fun zoneOffset(zone: String): ZoneOffset {
        val z = ZONES[zone.uppercase(Locale.ROOT)] ?: zone.ifEmpty { "+0000" }
        val m = OFFSET.matchEntire(z) ?: return ZoneOffset.UTC
        val sign = if (m.groupValues[1] == "-") -1 else 1
        return runCatching { ZoneOffset.ofHoursMinutes(sign * m.groupValues[2].toInt(), sign * m.groupValues[3].toInt()) }
            .getOrDefault(ZoneOffset.UTC)
    }

    private val TAGS = Regex("<[^>]*>")
    private val BLOCK = Regex("(?i)<\\s*(br\\s*/?|/p|/div|/li)\\s*>")
    private val SPACES = Regex("[ \\t\\u00a0]+")
    private val NEWLINES = Regex("\\s*\\n\\s*")

    /** HTML-ish description to plain text with entities decoded. */
    fun plainText(raw: String): String {
        val noTags = TAGS.replace(BLOCK.replace(raw, "\n"), "")
        return decodeEntities(noTags).replace(SPACES, " ").replace(NEWLINES, "\n").trim()
    }

    private val ENTITY = Regex("&(#x[0-9a-fA-F]+|#\\d+|[a-zA-Z]+);")
    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“",
        "ndash" to "–", "mdash" to "—", "hellip" to "…",
    )

    private fun decodeEntities(s: String): String = ENTITY.replace(s) { m ->
        val e = m.groupValues[1]
        val decoded = runCatching {
            when {
                e.startsWith("#x") -> String(Character.toChars(e.drop(2).toInt(16)))
                e.startsWith("#") -> String(Character.toChars(e.drop(1).toInt()))
                else -> NAMED[e]
            }
        }.getOrNull()
        Regex.escapeReplacement(decoded ?: m.value)
    }

    private fun Element.children(): List<Element> {
        val out = ArrayList<Element>()
        var n: Node? = firstChild
        while (n != null) {
            if (n is Element) out.add(n)
            n = n.nextSibling
        }
        return out
    }

    private fun Element.child(local: String): Element? = children().firstOrNull { it.localName == local }
    private fun Element.isItunes(local: String) = localName == local && namespaceURI == ITUNES
    private fun Element.itunes(local: String): String? = children().firstOrNull { it.isItunes(local) }?.text()
    private fun Element.text(): String = (textContent ?: "").trim()
}
