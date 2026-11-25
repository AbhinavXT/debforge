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

    /** Raw .nzb contents (Usenet). */
    class NzbFile(val name: String, val bytes: ByteArray) : AddRequest {
        override val kind get() = AddKind.NZB_FILE
    }

    /** A link to an .nzb file (Usenet). */
    data class NzbLink(val url: String) : AddRequest {
        override val kind get() = AddKind.NZB_LINK
    }
}

enum class AddKind(val label: String) {
    MAGNET("magnet links"),
    TORRENT_FILE(".torrent files"),
    LINK("web links"),
    NZB_FILE(".nzb files"),
    NZB_LINK("Usenet links")
}

/** .nzb handling shared by the parser, the router and file validation. */
object Nzb {
    /** Path (before any query or fragment) ends in .nzb. */
    fun isNzbUrl(url: String): Boolean =
        url.substringBefore('#').substringBefore('?').endsWith(".nzb", ignoreCase = true)

    fun isNzbName(name: String?): Boolean = name?.endsWith(".nzb", ignoreCase = true) == true

    /** Cheap sniff, no XML parsing of untrusted content: an <nzb root tag in the first bytes. */
    fun looksLikeNzb(bytes: ByteArray): Boolean =
        String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1).contains("<nzb", ignoreCase = true)

    /** Only a service that takes Usenet sends .nzb links there; for others a link stays a link. */
    fun route(request: AddRequest, provider: ProviderId): AddRequest =
        if (request is AddRequest.Link && provider == ProviderId.TORBOX && isNzbUrl(request.url))
            AddRequest.NzbLink(request.url) else request
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
