package com.abhinavxt.debforge.ui.theme

/**
 * Colour themes the user can pick in Settings > Appearance. Each is a seed
 * for [Palettes]; the enum NAME is persisted, so never rename entries.
 */
enum class AppTheme(val seed: Palettes.Seed) {
    /** The brand theme, from the icon's ember orange (#EA580C). */
    EMBER(Palettes.Seed(hue = 41.0, chroma = 0.16, tertiaryShift = 190.0)),
    OCEAN(Palettes.Seed(hue = 255.0, chroma = 0.13)),
    AURORA(Palettes.Seed(hue = 195.0, chroma = 0.11, tertiaryShift = -60.0)),
    FOREST(Palettes.Seed(hue = 145.0, chroma = 0.12)),
    AMETHYST(Palettes.Seed(hue = 305.0, chroma = 0.14)),
    ROSE(Palettes.Seed(hue = 5.0, chroma = 0.14, tertiaryShift = 45.0)),
    GRAPHITE(Palettes.Seed(hue = 255.0, chroma = 0.02, tertiaryShift = 0.0));

    companion object {
        val DEFAULT = EMBER
        fun fromName(name: String?): AppTheme = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
