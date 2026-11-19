package com.abhinavxt.debforge.domain

/** Recognises YouTube channel links. Handles (@name) and /c/ or /user/ names need the extractor to resolve. */
object YouTubeChannelLinks {
    private val id = Regex("UC[A-Za-z0-9_-]{22}")
    private val channelPath = Regex(
        "^(?:https?://)?(?:[\\w-]+\\.)*youtube\\.com/channel/(UC[A-Za-z0-9_-]{22})(?![A-Za-z0-9_-])",
        RegexOption.IGNORE_CASE,
    )
    private val namedPath = Regex(
        "^(?:https?://)?(?:[\\w-]+\\.)*youtube\\.com/(?:@[^/?#\\s]+|c/[^/?#\\s]+|user/[^/?#\\s]+)",
        RegexOption.IGNORE_CASE,
    )

    fun canonical(channelId: String) = "https://www.youtube.com/channel/$channelId"

    /** The canonical `https://www.youtube.com/channel/UC…` for a link or bare channel id; null when it needs resolving. */
    fun canonicalUrl(text: String): String? {
        val t = text.trim()
        if (id.matches(t)) return canonical(t)
        return channelPath.find(t)?.let { canonical(it.groupValues[1]) }
    }

    /** True for a handle / custom-name link (or a bare @handle) that the extractor must resolve to a channel id. */
    fun needsResolving(text: String): Boolean {
        val t = text.trim()
        return canonicalUrl(t) == null && (namedPath.containsMatchIn(t) || Regex("^@[\\w.-]+$").matches(t))
    }

    /** A URL the extractor can open for [text] (a handle becomes its youtube.com/@ page); null when it is not a channel reference. */
    fun resolvable(text: String): String? {
        val t = text.trim()
        canonicalUrl(t)?.let { return it }
        if (Regex("^@[\\w.-]+$").matches(t)) return "https://www.youtube.com/$t"
        if (!needsResolving(t)) return null
        return if (t.startsWith("http", ignoreCase = true)) t else "https://$t"
    }
}
