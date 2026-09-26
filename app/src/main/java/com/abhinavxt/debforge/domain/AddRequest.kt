package com.abhinavxt.debforge.domain

/** Something the user wants their debrid service to fetch. */
sealed interface AddRequest {
    val kind: AddKind

    data class Magnet(val uri: String) : AddRequest {
        override val kind get() = AddKind.MAGNET
    }

    data class Link(val url: String) : AddRequest {
        override val kind get() = AddKind.LINK
    }

    /** Raw .torrent contents. Not a data class: ByteArray equality is by reference. */
    class TorrentFile(val name: String, val bytes: ByteArray) : AddRequest {
        override val kind get() = AddKind.TORRENT_FILE
    }
}

enum class AddKind(val label: String) {
    MAGNET("magnet links"),
    TORRENT_FILE(".torrent files"),
    LINK("web links")
}

/**
 * Splits pasted/shared text into requests. Accepts several per paste (one per
 * line or separated by spaces). Anything that isn't a magnet or http(s) URL is
 * returned in [Parsed.ignored] so the UI can say so.
 */
object AddInputParser {

    data class Parsed(val requests: List<AddRequest>, val ignored: List<String>)

    private val SPLIT = Regex("""\s+""")

    fun parse(text: String): Parsed {
        val requests = mutableListOf<AddRequest>()
        val ignored = mutableListOf<String>()
        text.split(SPLIT)
            .map { it.trim().trim('<', '>', '"', '\'') }
            .filter { it.isNotEmpty() }
            .distinct()
            .forEach { token ->
                when {
                    token.startsWith("magnet:?", ignoreCase = true) -> requests += AddRequest.Magnet(token)
                    token.startsWith("http://", ignoreCase = true) ||
                        token.startsWith("https://", ignoreCase = true) -> requests += AddRequest.Link(token)
                    else -> ignored += token
                }
            }
        return Parsed(requests, ignored)
    }
}
