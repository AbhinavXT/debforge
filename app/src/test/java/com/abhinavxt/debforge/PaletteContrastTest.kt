package com.abhinavxt.debforge

import com.abhinavxt.debforge.ui.theme.AppTheme
import com.abhinavxt.debforge.ui.theme.Palettes
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** Guard rails for the generated colour schemes: all palettes, light and dark. */
class PaletteContrastTest {

    private fun lum(c: Int): Double {
        fun lin(v: Int): Double { val x = v / 255.0; return if (x <= 0.04045) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * lin((c shr 16) and 0xFF) + 0.7152 * lin((c shr 8) and 0xFF) + 0.0722 * lin(c and 0xFF)
    }

    private fun check(tag: String, name: String, fg: Int, bg: Int, min: Double) {
        val c = Palettes.contrast(fg, bg)
        assertTrue("$tag $name contrast %.2f < $min".format(c), c >= min)
    }

    @Test fun textRolesReachAa() {
        for (theme in AppTheme.entries) for (dark in listOf(false, true)) {
            val s = Palettes.scheme(theme.seed, dark)
            val tag = "$theme dark=$dark"
            check(tag, "onBackground/background", s.onBackground, s.background, 4.5)
            check(tag, "onSurface/surface", s.onSurface, s.surface, 4.5)
            check(tag, "onSurface/containerHighest", s.onSurface, s.surfaceContainerHighest, 4.5)
            check(tag, "onSurfaceVariant/surface", s.onSurfaceVariant, s.surface, 4.5)
            check(tag, "onSurfaceVariant/containerHigh", s.onSurfaceVariant, s.surfaceContainerHigh, 4.5)
            check(tag, "onPrimary/primary", s.onPrimary, s.primary, 4.5)
            check(tag, "onPrimaryContainer/primaryContainer", s.onPrimaryContainer, s.primaryContainer, 4.5)
            check(tag, "onSecondaryContainer/secondaryContainer", s.onSecondaryContainer, s.secondaryContainer, 4.5)
            check(tag, "onTertiaryContainer/tertiaryContainer", s.onTertiaryContainer, s.tertiaryContainer, 4.5)
            check(tag, "onError/error", s.onError, s.error, 4.5)
            check(tag, "onErrorContainer/errorContainer", s.onErrorContainer, s.errorContainer, 4.5)
        }
    }

    @Test fun graphicsReach3to1() {
        for (theme in AppTheme.entries) for (dark in listOf(false, true)) {
            val s = Palettes.scheme(theme.seed, dark)
            val tag = "$theme dark=$dark"
            check(tag, "outline/surface", s.outline, s.surface, 3.0)
            check(tag, "outline/containerHigh", s.outline, s.surfaceContainerHigh, 3.0)
            check(tag, "primary/surface", s.primary, s.surface, 3.0)
            check(tag, "error/surface", s.error, s.surface, 3.0)
        }
    }

    @Test fun surfaceTonesStepStrictly() {
        for (theme in AppTheme.entries) for (dark in listOf(false, true)) {
            val s = Palettes.scheme(theme.seed, dark)
            val l = listOf(s.background, s.surfaceContainerLowest, s.surfaceContainerLow, s.surfaceContainer,
                s.surfaceContainerHigh, s.surfaceContainerHighest).map(::lum)
            // Dark steps get lighter from lowest to highest; light steps get darker.
            val sign = if (dark) 1.0 else -1.0
            val seq = l.drop(1).map { it * sign }
            assertTrue("$theme dark=$dark background vs lowest", l[0] <= l[1])
            for (i in 0 until 4) assertTrue("$theme dark=$dark step $i not strictly ordered: $l", seq[i] < seq[i + 1])
        }
    }
}
