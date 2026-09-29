package com.abhinavxt.debforge.player.tracks

/**
 * How subtitles look: size, colours, height on screen, and whether a file's
 * own styling (ASS/SSA colours and positions) is kept. Stored as one short
 * string in the settings. Pure: unit-tested in SubtitleStyleTest.
 */
data class SubtitleStyle(
    val size: Size = Size.M,
    val look: Look = Look.OUTLINE,
    val position: Position = Position.MID,
    val fileStyling: Boolean = true
) {
    /** Text height as a fraction of the video's height (Media3's default is 0.0533). */
    enum class Size(val fraction: Float) { S(0.045f), M(0.0533f), L(0.065f), XL(0.08f) }

    /** White with a black outline, white on a dark box, or yellow with an outline. */
    enum class Look { OUTLINE, BOX, YELLOW }

    /** Space under the lines, as a fraction of the video's height. */
    enum class Position(val bottomFraction: Float) { LOW(0.03f), MID(0.08f), HIGH(0.18f) }

    fun encode(): String = listOf(size.name, look.name, position.name, if (fileStyling) "1" else "0").joinToString("|")

    companion object {
        /** Anything unreadable falls back to the defaults, part by part. */
        fun decode(raw: String?): SubtitleStyle {
            val p = raw?.split('|').orEmpty()
            val d = SubtitleStyle()
            return SubtitleStyle(
                size = Size.entries.firstOrNull { it.name == p.getOrNull(0) } ?: d.size,
                look = Look.entries.firstOrNull { it.name == p.getOrNull(1) } ?: d.look,
                position = Position.entries.firstOrNull { it.name == p.getOrNull(2) } ?: d.position,
                fileStyling = p.getOrNull(3)?.let { it == "1" } ?: d.fileStyling
            )
        }
    }
}
