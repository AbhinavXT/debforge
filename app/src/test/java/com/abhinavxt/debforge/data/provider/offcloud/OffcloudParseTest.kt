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
}
