package com.abhinavxt.debforge.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GridSizeTest {
    @Test fun mediumIsTheOriginalWidth() = assertEquals(112, GridSize.MEDIUM.minDp)

    @Test fun sizesGrowAndKeepColumnsOnAPhone() {
        assertTrue(GridSize.SMALL.minDp < GridSize.MEDIUM.minDp && GridSize.MEDIUM.minDp < GridSize.LARGE.minDp)
        // A 412dp phone: 16dp gutters, 12dp gaps; Adaptive fits (width + gap) / (min + gap) columns.
        fun columns(g: GridSize) = (412 - 32 + 12) / (g.minDp + 12)
        assertTrue(columns(GridSize.SMALL) >= 3)
        assertEquals(2, columns(GridSize.LARGE))
    }

    @Test fun unknownOrMissingNameIsMedium() {
        assertEquals(GridSize.MEDIUM, GridSize.fromName(null))
        assertEquals(GridSize.MEDIUM, GridSize.fromName("HUGE"))
        GridSize.entries.forEach { assertEquals(it, GridSize.fromName(it.name)) }
    }
}
