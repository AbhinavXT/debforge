package com.abhinavxt.debforge

import com.abhinavxt.debforge.ui.components.releaseLabels
import com.abhinavxt.debforge.ui.components.splitForMiddleEllipsis
import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseTextTest {
    @Test fun splitKeepsExtension() {
        val (head, tail) = splitForMiddleEllipsis("Sintel.2010.2160p.BluRay.REMUX.HEVC.DTS-HD.MA.5.1-DEMO.mkv")
        assertEquals("Sintel.2010.2160p.BluRay.REMUX.HEVC.DTS-HD.MA.5.1-DEMO.mkv", head + tail)
        assertEquals(true, tail.endsWith("DEMO.mkv"))
    }
    @Test fun splitShortName() {
        assertEquals("" to "a.mkv", splitForMiddleEllipsis("a.mkv"))
    }
    @Test fun labelsMovie() {
        val l = releaseLabels("Sintel.2010.2160p.BluRay.REMUX.HEVC.mkv")
        assertEquals("Sintel (2010)", l.title)
        assertEquals(true, l.tags.startsWith("2160p"))
    }
}
