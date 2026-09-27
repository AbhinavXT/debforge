package com.abhinavxt.debforge.data.metadata

/**
 * Tidies what people paste as a TMDB credential and tells the two kinds apart.
 * TMDB issues both on the same page (themoviedb.org → Settings → API):
 *
 *  - v3 "API Key": 32 hex characters, sent as `api_key=`.
 *  - v4 "API Read Access Token": a long JWT starting "eyJ", sent as a Bearer
 *    header.
 *
 * Pure Kotlin: unit-tested in TmdbKeyTest.
 */
object TmdbKey {

    enum class Kind { V3_KEY, V4_TOKEN, UNKNOWN }

    private val V3 = Regex("^[0-9a-fA-F]{32}$")
    private val JWT = Regex("^eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$")

    /**
     * Strips what comes along when copying from the TMDB page or a note:
     * surrounding quotes, a "Bearer " prefix, and whitespace or line breaks
     * (the token wraps across lines on narrow screens).
     */
    fun normalize(raw: String): String {
        var s = raw.trim().trim('"', '\'', '`').trim()
        if (s.startsWith("Bearer ", ignoreCase = true)) s = s.substring(7)
        return s.filterNot { it.isWhitespace() }
    }

    fun kindOf(key: String): Kind = when {
        V3.matches(key) -> Kind.V3_KEY
        JWT.matches(key) -> Kind.V4_TOKEN
        else -> Kind.UNKNOWN
    }
}
