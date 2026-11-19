package com.abhinavxt.debforge.data.podcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

class RssParserTest {
    private fun resource(name: String) = checkNotNull(javaClass.getResourceAsStream("/podcast/$name")) { name }
    private fun parse(xml: String) = RssParser.parse(xml.byteInputStream())
    private fun feedWithItem(item: String) = parse(
        """<rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"><channel><title>t</title>$item</channel></rss>""",
    )

    private fun millis(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test fun libsynStyleFeed() {
        val feed = RssParser.parse(resource("libsyn.xml"))
        assertEquals("The Sample Show", feed.title)
        assertEquals("Weekly chat about & around sample things.", feed.description)
        assertEquals("Sam Example", feed.author)
        assertEquals("https://example.com/art/show.jpg", feed.imageUrl)
        assertEquals(2, feed.episodes.size)
        val e = feed.episodes[0]
        assertEquals("Episode 2: Second & Better", e.title)
        assertEquals("ep-2-guid", e.guid)
        assertEquals("https://cdn.example.com/ep2.mp3", e.enclosureUrl)
        assertEquals("audio/mpeg", e.enclosureType)
        assertEquals(12345678L, e.enclosureLength)
        assertEquals(3723, e.durationSeconds)
        assertEquals("https://example.com/art/ep2.jpg", e.imageUrl)
        assertEquals(millis("2024-03-05T08:30:00Z"), e.publishedAtMillis)
        assertEquals("Notes with a link & \"quotes\" here.\nSecond para.", e.description)
        assertEquals(2400, feed.episodes[1].durationSeconds)
        assertNull(feed.episodes[1].imageUrl)
    }

    @Test fun anchorStyleFeedFallsBackToChannelImage() {
        val feed = RssParser.parse(resource("anchor.xml"))
        assertEquals("Anchor Style Talks", feed.title)
        assertEquals("Short & sweet", feed.description)
        assertEquals("https://example.com/anchor.jpg", feed.imageUrl)
        assertEquals("Pat Host", feed.author)
        val e = feed.episodes.single()
        assertEquals(754, e.durationSeconds)
        assertEquals(millis("2024-01-05T15:00:00Z"), e.publishedAtMillis)
        assertEquals("First!", e.description)
    }

    @Test fun durationFormats() {
        assertEquals(90, RssParser.parseDuration("90"))
        assertEquals(754, RssParser.parseDuration("12:34"))
        assertEquals(3723, RssParser.parseDuration("1:02:03"))
        assertEquals(3723, RssParser.parseDuration(" 01:02:03 "))
        assertNull(RssParser.parseDuration("abc"))
        assertNull(RssParser.parseDuration("1:2:3:4"))
        assertNull(RssParser.parseDuration(""))
    }

    @Test fun dateVariants() {
        val expected = millis("2024-01-05T15:00:00Z")
        assertEquals(expected, RssParser.parseRfc822("Fri, 05 Jan 2024 15:00:00 GMT"))
        assertEquals(expected, RssParser.parseRfc822("Fri, 5 Jan 2024 15:00:00 +0000"))
        assertEquals(expected, RssParser.parseRfc822("5 Jan 2024 10:00:00 EST"))
        assertEquals(expected, RssParser.parseRfc822("Fri, 05 Jan 2024 07:00:00 PST"))
        assertEquals(expected, RssParser.parseRfc822("Fri, 05 Jan 2024 16:00:00 +0100"))
        assertEquals(expected, RssParser.parseRfc822("Fri, 05 Jan 24 15:00 UT"))
        assertEquals(expected, RssParser.parseRfc822("2024-01-05T15:00:00Z"))
        assertEquals(0L, RssParser.parseRfc822("yesterday-ish"))
        assertEquals(0L, RssParser.parseRfc822("Fri, 32 Jan 2024 15:00:00 GMT"))
        assertEquals(0L, RssParser.parseRfc822(""))
    }

    @Test fun badDateDoesNotCrash() {
        val e = feedWithItem(
            """<item><title>a</title><pubDate>garbage</pubDate><enclosure url="http://x/a.mp3" type="audio/mpeg"/></item>""",
        ).episodes.single()
        assertEquals(0L, e.publishedAtMillis)
    }

    @Test fun guidFallsBackToEnclosureUrl() {
        val e = feedWithItem("""<item><title>a</title><enclosure url="http://x/a.mp3" type="audio/mpeg"/></item>""").episodes.single()
        assertEquals("http://x/a.mp3", e.guid)
    }

    @Test fun missingEnclosureTypeIsAccepted() {
        val e = feedWithItem("""<item><title>a</title><enclosure url="http://x/a.mp3"/></item>""").episodes.single()
        assertNull(e.enclosureType)
        assertNull(e.enclosureLength)
    }

    @Test fun videoEnclosureKeptNonMediaSkipped() {
        val feed = feedWithItem(
            """<item><title>v</title><enclosure url="http://x/v.mp4" type="video/mp4"/></item>
               <item><title>d</title><enclosure url="http://x/d.pdf" type="application/pdf"/></item>
               <item><title>none</title></item>""",
        )
        assertEquals(listOf("v"), feed.episodes.map { it.title })
    }

    @Test fun newestFirstSort() {
        val feed = feedWithItem(
            """<item><title>old</title><pubDate>Mon, 01 Jan 2024 00:00:00 GMT</pubDate><enclosure url="http://x/1.mp3" type="audio/mpeg"/></item>
               <item><title>undated</title><enclosure url="http://x/2.mp3" type="audio/mpeg"/></item>
               <item><title>new</title><pubDate>Mon, 01 Jan 2025 00:00:00 GMT</pubDate><enclosure url="http://x/3.mp3" type="audio/mpeg"/></item>""",
        )
        assertEquals(listOf("old", "undated", "new"), feed.episodes.map { it.title })
        assertEquals(listOf("new", "old", "undated"), feed.episodesNewestFirst().map { it.title })
    }

    @Test fun doctypeRejected() {
        val xml = """<?xml version="1.0"?><!DOCTYPE rss [<!ENTITY x "boom">]><rss version="2.0"><channel><title>&x;</title></channel></rss>"""
        try {
            parse(xml)
            fail("expected MalformedFeedException")
        } catch (_: MalformedFeedException) {
        }
    }

    @Test fun externalEntityRejected() {
        val xml = """<?xml version="1.0"?><!DOCTYPE rss [<!ENTITY x SYSTEM "file:///etc/passwd">]><rss version="2.0"><channel><title>&x;</title></channel></rss>"""
        try {
            parse(xml)
            fail("expected MalformedFeedException")
        } catch (_: MalformedFeedException) {
        }
    }

    @Test fun atomIsUnsupported() {
        try {
            parse("""<feed xmlns="http://www.w3.org/2005/Atom"><title>x</title></feed>""")
            fail("expected UnsupportedFeedException")
        } catch (e: UnsupportedFeedException) {
            assertEquals("Not a podcast RSS feed", e.message)
        }
    }

    @Test fun nonRssXmlIsUnsupported() {
        try {
            parse("<html><body>hi</body></html>")
            fail("expected UnsupportedFeedException")
        } catch (_: UnsupportedFeedException) {
        }
    }

    @Test fun malformedXml() {
        for (bad in listOf("<rss><channel>", "not xml at all", "")) {
            try {
                parse(bad)
                fail("expected MalformedFeedException for '$bad'")
            } catch (_: MalformedFeedException) {
            }
        }
    }

    @Test fun doctypeWithExternalEntityIsRejected() {
        try {
            RssParser.parse(resource("doctype-entity.xml"))
            fail("expected MalformedFeedException")
        } catch (e: MalformedFeedException) {
            assertTrue(e.cause != null)
        }
    }

    @Test fun plainTextDecodesEntities() {
        assertEquals("a & b < c ’ A", RssParser.plainText("a &amp; b &lt; c &rsquo; &#65;"))
        assertTrue(RssParser.plainText("<b>x</b>&unknown;").contains("&unknown;"))
    }
}
