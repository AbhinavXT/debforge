package com.abhinavxt.debforge.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PalettesTest {

    private val modes = listOf(false to false, true to false, true to true) // light, dark, pure black

    private fun check(name: String, fg: Int, bg: Int, min: Double) {
        val c = Palettes.contrast(fg, bg)
        assertTrue("$name contrast %.2f < $min".format(c), c >= min)
    }

    /** WCAG AA (4.5:1) for text on its colour role; 7:1 for body text on surfaces. */
    @Test fun everyThemeIsReadable() {
        for (theme in AppTheme.entries) for ((dark, black) in modes) {
            val s = Palettes.scheme(theme.seed, dark, black)
            val tag = "$theme dark=$dark black=$black"
            check("$tag onPrimary", s.onPrimary, s.primary, 4.5)
            check("$tag onPrimaryContainer", s.onPrimaryContainer, s.primaryContainer, 4.5)
            check("$tag onSecondaryContainer", s.onSecondaryContainer, s.secondaryContainer, 4.5)
            check("$tag onTertiary", s.onTertiary, s.tertiary, 4.5)
            check("$tag onTertiaryContainer", s.onTertiaryContainer, s.tertiaryContainer, 4.5)
            check("$tag onError", s.onError, s.error, 4.5)
            check("$tag onSurface", s.onSurface, s.surface, 7.0)
            check("$tag onSurface/containerHighest", s.onSurface, s.surfaceContainerHighest, 7.0)
            check("$tag onSurfaceVariant", s.onSurfaceVariant, s.surfaceContainer, 4.5)
            check("$tag primary on surface", s.primary, s.surface, 3.0) // icons, progress bars
        }
    }

    @Test fun pureBlackIsBlack() {
        val s = Palettes.scheme(AppTheme.OCEAN.seed, dark = true, pureBlack = true)
        assertEquals(0xFF000000.toInt(), s.background)
        assertEquals(0xFF000000.toInt(), s.surface)
    }

    @Test fun surfaceContainersStepInOrder() {
        for (theme in AppTheme.entries) for ((dark, black) in modes) {
            val s = Palettes.scheme(theme.seed, dark, black)
            val steps = listOf(s.surfaceContainerLow, s.surfaceContainer, s.surfaceContainerHigh, s.surfaceContainerHighest)
            val contrastToBase = steps.map { Palettes.contrast(it, s.surface) }
            assertEquals("$theme dark=$dark", contrastToBase.sorted(), contrastToBase)
        }
    }

    @Test fun brandHue() {
        val (hue, _) = Palettes.hueChromaOf(0xFFEA580C.toInt()) // icon orange
        assertEquals(AppTheme.EMBER.seed.hue, hue, 1.0)
    }

    @Test fun unknownNameFallsBack() = assertEquals(AppTheme.EMBER, AppTheme.fromName("NOPE"))
}
