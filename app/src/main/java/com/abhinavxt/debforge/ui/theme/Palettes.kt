package com.abhinavxt.debforge.ui.theme

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Builds complete Material 3 colour schemes from a single seed hue, so every
 * theme gets all ~40 roles (including the surfaceContainer levels) with
 * consistent contrast in light and dark.
 *
 * Colours are chosen in OKLCH (perceptually even hue and chroma) at a given
 * "tone" = CIELAB L* (0 black .. 100 white), the same lightness scale Material
 * uses, then clipped into sRGB by lowering chroma. Pure Kotlin, no Android
 * types, so it's unit-tested on the JVM.
 */
object Palettes {

    /** One tonal palette: fixed hue and chroma, any tone. */
    class Tonal(val hue: Double, val chroma: Double) {
        /**
         * Very light tones are kept pastel: at full chroma, cyan and green
         * containers (tone 90) turn neon, because sRGB allows huge chroma there.
         */
        fun tone(t: Int): Int {
            val c = when {
                t >= 85 -> chroma * 0.5
                t >= 70 -> chroma * 0.8
                else -> chroma
            }
            return argbAt(hue, c, t.toDouble())
        }
    }

    /** Every role, as ARGB ints (mapped to Compose colours in Theme.kt). */
    data class Scheme(
        val primary: Int, val onPrimary: Int, val primaryContainer: Int, val onPrimaryContainer: Int,
        val inversePrimary: Int,
        val secondary: Int, val onSecondary: Int, val secondaryContainer: Int, val onSecondaryContainer: Int,
        val tertiary: Int, val onTertiary: Int, val tertiaryContainer: Int, val onTertiaryContainer: Int,
        val error: Int, val onError: Int, val errorContainer: Int, val onErrorContainer: Int,
        val background: Int, val onBackground: Int,
        val surface: Int, val onSurface: Int, val surfaceVariant: Int, val onSurfaceVariant: Int,
        val surfaceTint: Int, val inverseSurface: Int, val inverseOnSurface: Int,
        val outline: Int, val outlineVariant: Int, val scrim: Int,
        val surfaceBright: Int, val surfaceDim: Int,
        val surfaceContainerLowest: Int, val surfaceContainerLow: Int, val surfaceContainer: Int,
        val surfaceContainerHigh: Int, val surfaceContainerHighest: Int
    )

    /**
     * A theme's seed: [hue] in OKLCH degrees, [chroma] of the primary colour
     * (OKLCH units, ~0.02 grey .. ~0.18 vivid), [tertiaryShift] degrees to
     * the accent hue.
     */
    data class Seed(val hue: Double, val chroma: Double, val tertiaryShift: Double = 60.0)

    fun scheme(seed: Seed, dark: Boolean, pureBlack: Boolean = false): Scheme {
        val p = Tonal(seed.hue, seed.chroma)
        val s = Tonal(seed.hue, (seed.chroma * 0.33).coerceAtMost(0.05))
        val t = Tonal((seed.hue + seed.tertiaryShift).mod(360.0), (seed.chroma * 0.6).coerceAtLeast(0.03))
        val n = Tonal(seed.hue, (seed.chroma * 0.06).coerceAtMost(0.01))
        val nv = Tonal(seed.hue, (seed.chroma * 0.16).coerceAtMost(0.025))
        val e = Tonal(27.0, 0.17)
        return if (!dark) Scheme(
            primary = p.tone(40), onPrimary = p.tone(100), primaryContainer = p.tone(90), onPrimaryContainer = p.tone(10),
            inversePrimary = p.tone(80),
            secondary = s.tone(40), onSecondary = s.tone(100), secondaryContainer = s.tone(90), onSecondaryContainer = s.tone(10),
            tertiary = t.tone(40), onTertiary = t.tone(100), tertiaryContainer = t.tone(90), onTertiaryContainer = t.tone(10),
            error = e.tone(40), onError = e.tone(100), errorContainer = e.tone(90), onErrorContainer = e.tone(10),
            background = n.tone(98), onBackground = n.tone(10),
            surface = n.tone(98), onSurface = n.tone(10), surfaceVariant = nv.tone(90), onSurfaceVariant = nv.tone(30),
            surfaceTint = p.tone(40), inverseSurface = n.tone(20), inverseOnSurface = n.tone(95),
            outline = nv.tone(50), outlineVariant = nv.tone(80), scrim = BLACK,
            surfaceBright = n.tone(98), surfaceDim = n.tone(87),
            surfaceContainerLowest = n.tone(100), surfaceContainerLow = n.tone(96), surfaceContainer = n.tone(94),
            surfaceContainerHigh = n.tone(92), surfaceContainerHighest = n.tone(90)
        ) else {
            // Pure black: true #000 base for OLED screens, containers lifted a
            // little so cards and sheets still separate from the background.
            val base = if (pureBlack) BLACK else n.tone(6)
            Scheme(
                primary = p.tone(80), onPrimary = p.tone(20), primaryContainer = p.tone(30), onPrimaryContainer = p.tone(90),
                inversePrimary = p.tone(40),
                secondary = s.tone(80), onSecondary = s.tone(20), secondaryContainer = s.tone(30), onSecondaryContainer = s.tone(90),
                tertiary = t.tone(80), onTertiary = t.tone(20), tertiaryContainer = t.tone(30), onTertiaryContainer = t.tone(90),
                error = e.tone(80), onError = e.tone(20), errorContainer = e.tone(30), onErrorContainer = e.tone(90),
                background = base, onBackground = n.tone(90),
                surface = base, onSurface = n.tone(90), surfaceVariant = nv.tone(30), onSurfaceVariant = nv.tone(80),
                surfaceTint = p.tone(80), inverseSurface = n.tone(90), inverseOnSurface = n.tone(20),
                outline = nv.tone(60), outlineVariant = nv.tone(30), scrim = BLACK,
                surfaceBright = n.tone(24), surfaceDim = base,
                surfaceContainerLowest = if (pureBlack) BLACK else n.tone(4),
                surfaceContainerLow = n.tone(if (pureBlack) 6 else 10),
                surfaceContainer = n.tone(if (pureBlack) 9 else 12),
                surfaceContainerHigh = n.tone(if (pureBlack) 13 else 17),
                surfaceContainerHighest = n.tone(if (pureBlack) 18 else 22)
            )
        }
    }

    // --- colour maths ---------------------------------------------------------

    private const val BLACK = 0xFF000000.toInt()

    /**
     * sRGB colour with OKLCH [hue]/[chroma] whose CIELAB L* equals [tone].
     * OKLab lightness is found by bisection; chroma is lowered until the
     * colour fits in sRGB.
     */
    fun argbAt(hue: Double, chroma: Double, tone: Double): Int {
        if (tone <= 0.0) return BLACK
        if (tone >= 100.0) return 0xFFFFFFFF.toInt()
        var c = chroma
        repeat(40) {
            val rgb = solveLightness(hue, c, tone)
            if (rgb != null) return pack(rgb)
            c *= 0.9
        }
        return pack(solveLightness(hue, 0.0, tone)!!)
    }

    /** Linear-sRGB triple at the OKLab L giving L* = [tone], or null if out of gamut. */
    private fun solveLightness(hue: Double, chroma: Double, tone: Double): DoubleArray? {
        val h = hue * PI / 180.0
        val a = chroma * cos(h)
        val b = chroma * sin(h)
        var lo = 0.0
        var hi = 1.0
        var rgb = oklabToLinear(0.5, a, b)
        repeat(30) {
            val mid = (lo + hi) / 2
            rgb = oklabToLinear(mid, a, b)
            if (lstar(rgb) < tone) lo = mid else hi = mid
        }
        val eps = 1e-4
        return if (rgb.all { it in -eps..1.0 + eps }) rgb else null
    }

    private fun oklabToLinear(l: Double, a: Double, b: Double): DoubleArray {
        val l1 = (l + 0.3963377774 * a + 0.2158037573 * b).pow(3)
        val m1 = (l - 0.1055613458 * a - 0.0638541728 * b).pow(3)
        val s1 = (l - 0.0894841775 * a - 1.2914855480 * b).pow(3)
        return doubleArrayOf(
            4.0767416621 * l1 - 3.3077115913 * m1 + 0.2309699292 * s1,
            -1.2684380046 * l1 + 2.6097574011 * m1 - 0.3413193965 * s1,
            -0.0041960863 * l1 - 0.7034186147 * m1 + 1.7076147010 * s1
        )
    }

    /** CIELAB L* of a linear-sRGB colour (via relative luminance Y). */
    private fun lstar(rgb: DoubleArray): Double {
        val y = (0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2]).coerceIn(0.0, 1.0)
        return if (y > 216.0 / 24389.0) 116.0 * cbrt(y) - 16.0 else y * 24389.0 / 27.0
    }

    private fun pack(rgb: DoubleArray): Int {
        fun ch(v: Double): Int {
            val x = v.coerceIn(0.0, 1.0)
            val s = if (x <= 0.0031308) 12.92 * x else 1.055 * x.pow(1 / 2.4) - 0.055
            return (s * 255).roundToInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(rgb[0]) shl 16) or (ch(rgb[1]) shl 8) or ch(rgb[2])
    }

    /** OKLCH hue (degrees) and chroma of an sRGB colour: to derive seeds from brand colours. */
    fun hueChromaOf(argb: Int): Pair<Double, Double> {
        fun lin(c: Int): Double {
            val v = c / 255.0
            return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        val r = lin((argb shr 16) and 0xFF); val g = lin((argb shr 8) and 0xFF); val bl = lin(argb and 0xFF)
        val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * bl)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * bl)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * bl)
        val a = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s
        val b = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
        return (atan2(b, a) * 180 / PI).mod(360.0) to sqrt(a * a + b * b)
    }

    /** WCAG contrast ratio of two colours (used by tests). */
    fun contrast(c1: Int, c2: Int): Double {
        fun lum(c: Int): Double {
            fun lin(v: Int): Double { val x = v / 255.0; return if (x <= 0.04045) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4) }
            return 0.2126 * lin((c shr 16) and 0xFF) + 0.7152 * lin((c shr 8) and 0xFF) + 0.0722 * lin(c and 0xFF)
        }
        val (hi, lo) = listOf(lum(c1), lum(c2)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }
}
