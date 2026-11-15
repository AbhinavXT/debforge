package com.abhinavxt.debforge.ui.media

import org.junit.Assert.assertEquals
import org.junit.Test

class PruneSelectionTest {
    @Test fun keepsOnlyVisibleRows() {
        assertEquals(setOf("a"), pruneSelection(setOf("a", "gone", "filtered"), listOf("a", "b")))
        assertEquals(emptySet<String>(), pruneSelection(setOf("x"), emptyList()))
        assertEquals(emptySet<String>(), pruneSelection(emptySet(), listOf("a")))
    }
}
