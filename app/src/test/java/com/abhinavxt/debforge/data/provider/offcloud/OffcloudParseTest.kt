package com.abhinavxt.debforge.data.provider.offcloud

import com.abhinavxt.debforge.data.provider.ProviderException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class OffcloudParseTest {

    @Test fun detailedExplore() {
        val files = OffcloudParse.exploreFiles(
            """{"files":[{"path":"/Show.S01/Show.S01E01.mkv","size":1234,"url":"https://a1.offcloud.com/cloud/download/abc/Show.S01/Show.S01E01.mkv"}]}"""
        )
        assertEquals(listOf(OcFile("Show.S01/Show.S01E01.mkv", 1234L, "https://a1.offcloud.com/cloud/download/abc/Show.S01/Show.S01E01.mkv")), files)
    }

    @Test fun legacyExploreIsAListOfUrls() {
        val files = OffcloudParse.exploreFiles(
            """["https://a1.offcloud.com/cloud/download/abc/My%20Show/Ep%201.mkv"]"""
        )
        assertEquals("My Show/Ep 1.mkv", files.single().path)
        assertNull(files.single().size)
    }

    @Test fun exploreErrorThrows() {
        val e = assertThrows(ProviderException::class.java) { OffcloudParse.exploreFiles("""{"error":"Bad archive"}""") }
        assertEquals("Offcloud: Bad archive", e.message)
    }

    @Test fun singleFileUrlEncodesTheName() = assertEquals(
        "https://a7.offcloud.com/cloud/download/r1/The%20Bear%20S02E05.mkv",
        OffcloudParse.singleFileUrl("a7", "r1", "The Bear S02E05.mkv")
    )

    @Test fun dates() {
        assertEquals("2026-10-01T12:00:00Z", OffcloudParse.isoDate("2026-10-01T12:00:00.000Z"))
        assertEquals("2026-10-01T12:00:00Z", OffcloudParse.isoDate("1790856000000"))
        assertEquals("2026-10-01T12:00:00Z", OffcloudParse.isoDate("1790856000"))
        assertNull(OffcloudParse.isoDate("soon"))
    }

    @Test fun errors() {
        assertEquals("NOAUTH", OffcloudParse.errorOf("""{"error":"NOAUTH"}"""))
        assertEquals("request failed", OffcloudParse.errorOf("""{"success":false}"""))
        assertNull(OffcloudParse.errorOf("""{"success":true}"""))
    }

    @Test fun removeOutcomeClassifies() {
        assertEquals(OffcloudParse.RemoveOutcome(true, null), OffcloudParse.removeOutcome("""{"success":true}"""))
        assertEquals(OffcloudParse.RemoveOutcome(true, null), OffcloudParse.removeOutcome("[]"))
        assertEquals(OffcloudParse.RemoveOutcome(true, "boom"), OffcloudParse.removeOutcome("""{"error":"boom"}"""))
        assertEquals(OffcloudParse.RemoveOutcome(true, "request failed"), OffcloudParse.removeOutcome("""{"success":false}"""))
        assertEquals(OffcloudParse.RemoveOutcome(false, null), OffcloudParse.removeOutcome(""))
        assertEquals(OffcloudParse.RemoveOutcome(false, null), OffcloudParse.removeOutcome("<html>ok</html>"))
        assertEquals(OffcloudParse.RemoveOutcome(false, null), OffcloudParse.removeOutcome("\"ok\""))
    }

    @Test fun instantParsesAllShapes() {
        val t = java.time.Instant.parse("2024-05-01T10:00:00Z")
        assertEquals(t, OffcloudParse.instant("2024-05-01T10:00:00Z"))
        assertEquals(t, OffcloudParse.instant("2024-05-01T12:00:00+02:00"))
        assertEquals(t, OffcloudParse.instant(t.toEpochMilli().toString()))
        assertEquals(t, OffcloudParse.instant(t.epochSecond.toString()))
        assertNull(OffcloudParse.instant("yesterday"))
        assertNull(OffcloudParse.instant(null))
        assertEquals("2024-05-01T10:00:00Z", OffcloudParse.isoDate("2024-05-01T10:00:00Z"))
    }

    // "...:00Z" vs "...:00.5Z": the string order is wrong, the instant order is right.
    @Test fun instantOrdersWhereStringsDoNot() {
        val raw = listOf(null, "2024-05-01T10:00:00Z", "2024-05-01T10:00:00.5Z", "bad", "2024-05-02T00:00:00Z")
        val sorted = raw.sortedWith(compareByDescending<String?> { OffcloudParse.instant(it) })
        assertEquals(
            listOf("2024-05-02T00:00:00Z", "2024-05-01T10:00:00.5Z", "2024-05-01T10:00:00Z"),
            sorted.take(3)
        )
        assertNull(OffcloudParse.instant(sorted[3]))
        assertNull(OffcloudParse.instant(sorted[4]))
    }
}
