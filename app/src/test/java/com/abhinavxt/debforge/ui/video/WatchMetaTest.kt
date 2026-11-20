package com.abhinavxt.debforge.ui.video

import com.abhinavxt.debforge.domain.DefaultQuality
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchMetaTest {
    private val views: (Long, String) -> String = { _, c -> "$c views" }

    @Test fun viewsPluralQuantity() {
        assertEquals(0, viewsQuantity(0))
        assertEquals(1, viewsQuantity(1))
        assertEquals(999, viewsQuantity(999))
        assertEquals(1_000, viewsQuantity(1_234))
        assertEquals(1_000, viewsQuantity(5_000_000_000))
    }

    @Test fun compactCounts() {
        assertEquals("999", compactCount(999))
        assertEquals("1K", compactCount(1_000))
        assertEquals("1.2K", compactCount(1_234))
        assertEquals("3.4M", compactCount(3_400_000))
        assertEquals("2.1B", compactCount(2_100_000_000))
    }

    @Test fun metaLineWithBoth() {
        assertEquals("1.2M views · Jan 5, 2024", watchMeta(1_200_000, "2024-01-05T10:00:00-07:00", views, Locale.US))
    }

    @Test fun metaLineLeavesOutWhatIsUnknown() {
        assertEquals("5 views", watchMeta(5, null, views, Locale.US))
        assertEquals("Jan 5, 2024", watchMeta(-1, "2024-01-05", views, Locale.US))
        assertEquals("", watchMeta(-1, "3 years ago", views, Locale.US)) // relative English text is not shown
        assertEquals("0 views", watchMeta(0, "garbage-date", views, Locale.US))
    }

    @Test fun descriptionOverflow() {
        assertFalse(descriptionOverflows("short"))
        assertTrue(descriptionOverflows("a\nb\nc\nd"))
        assertTrue(descriptionOverflows("x".repeat(200)))
    }

    @Test fun tapFlow() {
        assertEquals(ResultTap.PLAY, resultTap(DefaultQuality.P720, isTv = false))
        assertEquals(ResultTap.PLAY, resultTap(DefaultQuality.BEST, isTv = false))
        assertEquals(ResultTap.ASK, resultTap(DefaultQuality.ASK, isTv = false))
        assertEquals(ResultTap.ASK, resultTap(DefaultQuality.ASK, isTv = false, viaPlayIcon = true))
        assertEquals(ResultTap.PREVIEW, resultTap(DefaultQuality.P720, isTv = true))
        assertEquals(ResultTap.PREVIEW, resultTap(DefaultQuality.ASK, isTv = true, viaPlayIcon = true))
        assertEquals(ResultTap.PLAY, resultTap(DefaultQuality.P720, isTv = true, viaPlayIcon = true))
    }
}
