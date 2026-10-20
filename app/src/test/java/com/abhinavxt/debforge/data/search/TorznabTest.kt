package com.abhinavxt.debforge.data.search

import com.abhinavxt.debforge.domain.SearchQuality
import com.abhinavxt.debforge.domain.SearchResult
import com.abhinavxt.debforge.domain.SearchResults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TorznabTest {

    // Shaped like a Jackett "all indexers" reply.
    private val feed = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0" xmlns:atom="http://www.w3.org/2005/Atom" xmlns:torznab="http://torznab.com/schemas/2015/feed">
          <channel>
            <title>AggregateSearch</title>
            <item>
              <title>The.Bear.S02E05.1080p.WEB.h264-GROUP</title>
              <guid>https://tracker-a.example/details/1</guid>
              <jackettindexer id="tracker-a">Tracker A</jackettindexer>
              <link>http://192.168.1.5:9117/dl/tracker-a/?jackett_apikey=x&amp;path=abc</link>
              <pubDate>Tue, 14 Oct 2026 08:00:00 +0000</pubDate>
              <size>2147483648</size>
              <enclosure url="http://192.168.1.5:9117/dl/tracker-a/?path=abc" length="2147483648" type="application/x-bittorrent" />
              <torznab:attr name="seeders" value="120" />
              <torznab:attr name="peers" value="130" />
              <torznab:attr name="infohash" value="AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" />
              <torznab:attr name="magneturl" value="magnet:?xt=urn:btih:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&amp;dn=The.Bear" />
            </item>
            <item>
              <title>The Bear S02E05 2160p WEB H265</title>
              <link>magnet:?xt=urn:btih:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb&amp;dn=x</link>
              <torznab:attr name="seeders" value="300" />
              <torznab:attr name="size" value="6442450944" />
            </item>
            <item>
              <title>Private tracker, .torrent only</title>
              <link>http://192.168.1.5:9117/dl/private/?path=zzz</link>
              <torznab:attr name="seeders" value="50" />
            </item>
          </channel>
        </rss>
    """.trimIndent()

    @Test fun parsesItems() {
        val now = java.time.Instant.parse("2026-10-20T08:00:00Z").toEpochMilli()
        val results = Torznab.parseResults(feed)
        assertEquals(2, results.size) // the .torrent-only item has no hash: dropped

        val a = results[0]
        assertEquals("a".repeat(40), a.hash)
        assertEquals("The.Bear.S02E05.1080p.WEB.h264-GROUP", a.title)
        assertTrue(a.magnet.startsWith("magnet:?xt=urn:btih:AAAA"))
        assertEquals(2_147_483_648L, a.size)
        assertEquals(120, a.seeders)
        assertEquals("Tracker A", a.tracker)
        assertEquals("6d", Torznab.ageOf("Tue, 14 Oct 2026 08:00:00 +0000", now))
        assertNull(a.cached)

        // Hash taken from the magnet link; size from the attr.
        assertEquals("b".repeat(40), results[1].hash)
        assertEquals(6_442_450_944L, results[1].size)
    }

    @Test fun errorReplyThrows() {
        try {
            Torznab.parseResults("""<?xml version="1.0"?><error code="100" description="Invalid API Key" />""")
            fail("expected an error")
        } catch (e: Torznab.TorznabError) {
            assertEquals(100, e.code)
            assertEquals("Invalid API Key", e.message)
        }
    }

    @Test fun capsCheck() {
        Torznab.checkCaps("""<?xml version="1.0"?><caps><server title="Jackett" /><searching /></caps>""")
        try {
            Torznab.checkCaps("""<?xml version="1.0"?><rss><channel /></rss>""")
            fail("expected an error")
        } catch (_: Torznab.TorznabError) {
        }
    }

    @Test fun refusesDoctypes() {
        val xxe = """<?xml version="1.0"?><!DOCTYPE r [<!ENTITY x SYSTEM "file:///etc/passwd">]><rss><channel><item><title>&x;</title></item></channel></rss>"""
        try {
            Torznab.parseResults(xxe)
            fail("a DOCTYPE should be refused")
        } catch (_: Exception) {
        }
    }

    @Test fun apiUrlForJackettAndProwlarrLinks() {
        val jackett = Torznab.apiUrl(
            "http://192.168.1.5:9117/api/v2.0/indexers/all/results/torznab/?apikey=old", "KEY",
            mapOf("t" to "search", "q" to "the bear")
        )!!
        assertEquals("/api/v2.0/indexers/all/results/torznab/api", jackett.encodedPath)
        assertEquals("KEY", jackett.queryParameter("apikey"))
        assertEquals("the bear", jackett.queryParameter("q"))
        assertEquals(1, jackett.queryParameterValues("apikey").size)

        val prowlarr = Torznab.apiUrl("http://nas:9696/1/api", "K", mapOf("t" to "caps"))!!
        assertEquals("/1/api", prowlarr.encodedPath)

        assertNull(Torznab.apiUrl("nas:9696", "K", emptyMap()))
    }

    @Test fun base32MagnetHash() {
        // Base32 of 20 zero bytes is 32 'A's.
        assertEquals("0".repeat(40), Torznab.hashFromMagnet("magnet:?xt=urn:btih:" + "A".repeat(32)))
        assertNull(Torznab.hashFromMagnet("magnet:?dn=nothing"))
    }

    private fun result(hash: String, seeders: Int?, cached: Boolean?, title: String = "Movie.2023.1080p.WEB") =
        SearchResult(hash, title, "magnet:?xt=urn:btih:$hash", 1, seeders, null, null, cached, null)

    @Test fun cachedFirstThenSeeders() {
        val ranked = SearchResults.rank(listOf(result("a", 500, false), result("b", 10, true), result("c", 900, null), result("d", 50, true)))
        assertEquals(listOf("d", "b", "c", "a"), ranked.map { it.hash })
    }

    @Test fun sameTorrentListedOnceWithMoreSeeders() {
        val ranked = SearchResults.rank(listOf(result("abc", 5, null), result("ABC", 40, null)))
        assertEquals(1, ranked.size)
        assertEquals(40, ranked.single().seeders)
    }

    @Test fun filtersByQualityAndCache() {
        val uhd = result("u", 1, true, "Movie.2023.2160p.WEB")
        val fhd = result("f", 1, false, "Movie.2023.1080p.WEB")
        val unknown = result("x", 1, true, "Movie 2023")
        val all = listOf(uhd, fhd, unknown)
        assertEquals(all, SearchResults.filter(all, SearchQuality.ANY, cachedOnly = false))
        assertEquals(listOf(uhd), SearchResults.filter(all, SearchQuality.UHD, cachedOnly = false))
        assertEquals(listOf(uhd, unknown), SearchResults.filter(all, SearchQuality.ANY, cachedOnly = true))
        assertFalse(unknown in SearchResults.filter(all, SearchQuality.FULL_HD, cachedOnly = false))
    }
}
