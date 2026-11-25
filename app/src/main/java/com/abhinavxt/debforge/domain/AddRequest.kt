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

    /** An .nzb file (Usenet), streamed from [open] when sent: up to [Nzb.MAX_BYTES], never held in memory. */
    class NzbFile(val name: String, val size: Long, val open: () -> java.io.InputStream) : AddRequest {
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

    const val MAX_BYTES = 50L * 1024 * 1024
    /** How much of the start is searched for the root tag (long comments and DOCTYPEs come first). */
    const val SNIFF_BYTES = 64 * 1024

    /**
     * Cheap sniff of the start of a file, no XML parsing of untrusted content:
     * an <nzb root tag, after an optional UTF-8 or UTF-16 byte order mark.
     */
    fun looksLikeNzb(head: ByteArray): Boolean {
        val n = minOf(head.size, SNIFF_BYTES)
        fun at(i: Int) = if (i < n) head[i].toInt() and 0xFF else -1
        val text = when {
            at(0) == 0xFF && at(1) == 0xFE -> String(head, 2, n - 2, Charsets.UTF_16LE)
            at(0) == 0xFE && at(1) == 0xFF -> String(head, 2, n - 2, Charsets.UTF_16BE)
            at(0) == 0xEF && at(1) == 0xBB && at(2) == 0xBF -> String(head, 3, n - 3, Charsets.ISO_8859_1)
            else -> String(head, 0, n, Charsets.ISO_8859_1)
        }
        return text.contains("<nzb", ignoreCase = true)
    }

    /** gzip magic: some indexers hand out .nzb.gz; TorBox wants plain XML. */
    fun isGzip(head: ByteArray): Boolean = head.size >= 2 && head[0] == 0x1F.toByte() && head[1] == 0x8B.toByte()

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

    /**
     * [text] without the magnets and links in [added] (their exact text), so a
     * retry after a partial result can't add them twice. Other lines stay.
     */
    fun withoutAdded(text: String, added: Set<String>): String =
        text.split(SPLIT).filter { raw ->
            val token = raw.trim().trim('<', '>', '"', '\'')
            raw.isNotBlank() && token !in added
        }.joinToString("\n")

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
