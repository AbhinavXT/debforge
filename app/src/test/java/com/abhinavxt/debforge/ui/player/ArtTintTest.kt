package com.abhinavxt.debforge.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtTintTest {
    private val darkSurface = 0xFF1C1B1F.toInt()
    private val darkText = 0xFFE6E1E5.toInt()
    private val lightSurface = 0xFFFFFBFE.toInt()
    private val lightText = 0xFF1C1B1F.toInt()

    @Test fun contrastExtremes() {
        assertEquals(21.0, contrastRatio(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.01)
        assertEquals(1.0, contrastRatio(0xFF123456.toInt(), 0xFF123456.toInt()), 0.001)
    }

    @Test fun darkThemeStaysReadableForEveryBrightArt() {
        for (art in listOf(0xFFFFFFFF, 0xFFFFFF00, 0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFF888888).map { it.toInt() }) {
            assertTrue(contrastRatio(darkText, artBackground(art, darkSurface, darkText)) >= 4.5)
        }
    }

    @Test fun lightThemeStaysReadableForEveryDarkArt() {
        for (art in listOf(0xFF000000, 0xFF800000, 0xFF0000FF, 0xFF004400, 0xFF888888).map { it.toInt() }) {
            assertTrue(contrastRatio(lightText, artBackground(art, lightSurface, lightText)) >= 4.5)
        }
    }

    @Test fun mutedArtIsActuallyTinted() {
        assertNotEquals(darkSurface, artBackground(0xFF2244AA.toInt(), darkSurface, darkText))
    }

    @Test fun artEqualToSurfaceChangesNothing() {
        assertEquals(darkSurface, artBackground(darkSurface, darkSurface, darkText))
    }
}
