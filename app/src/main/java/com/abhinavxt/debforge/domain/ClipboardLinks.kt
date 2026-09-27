package com.abhinavxt.debforge.domain

/**
 * Picks what's worth offering from copied text: magnet links and links to
 * .torrent files. Ordinary web links are ignored on purpose. People copy
 * those all day, and asking "Add this?" about every article would be noise.
 *
 * Pure: unit-tested in ClipboardLinksTest.
 */
object ClipboardLinks {

    /** Anything longer is not a few copied links (a whole document, a log). */
    const val MAX_TEXT = 20_000

    private val MAGNET = Regex("""magnet:\?\S*xt=urn:bt[im]h:[A-Za-z0-9]+\S*""", RegexOption.IGNORE_CASE)
    private val HTTP = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
    private val TRAILING = charArrayOf('.', ',', ';', ')', ']', '>', '"', '\'')

    /** The links to offer, one per line; null = nothing worth offering. */
    fun pick(text: String?): String? {
        if (text.isNullOrBlank() || text.length > MAX_TEXT) return null
        val magnets = MAGNET.findAll(text).map { it.value.trimEnd(*TRAILING) }
        val torrents = HTTP.findAll(text).map { it.value.trimEnd(*TRAILING) }.filter(::isTorrentUrl)
        val links = (magnets + torrents).distinct().toList()
        return links.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    private fun isTorrentUrl(url: String): Boolean =
        url.substringBefore('#').substringBefore('?').endsWith(".torrent", ignoreCase = true)
}
