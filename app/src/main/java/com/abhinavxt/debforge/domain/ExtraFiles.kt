package com.abhinavxt.debforge.domain

/**
 * Spots the filler that release groups pack into torrents: "Downloaded from
 * X.txt", .nfo and .url files, cover/screenshot images and sample clips.
 *
 * A file only counts as an extra when its torrent (same provider and
 * [DownloadItem.parentRef]) also holds something worth keeping. A torrent that
 * is nothing but a PDF, a text file or some pictures keeps all of it, and
 * files with no parent (hoster links added one by one) are never hidden.
 *
 * Pure Kotlin, no Android types: unit-tested in ExtraFilesTest.
 */
object ExtraFiles {

    /** Text/link clutter. Only counts as an extra up to [TEXT_MAX_BYTES]. */
    private val TEXT_EXTS = setOf(
        "txt", "nfo", "url", "lnk", "website", "webloc", "sfv", "md5", "sha1", "sha256",
        "htm", "html", "ini", "db"
    )
    private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
    private val VIDEO_EXTS = setOf(
        "mkv", "mp4", "avi", "mov", "m4v", "wmv", "ts", "m2ts", "webm", "flv", "mpg", "mpeg"
    )

    /** A real readme or subtitle-like text is rarely tiny; promo notes always are. */
    const val TEXT_MAX_BYTES = 64L * 1024
    /** Covers and screenshots. Bigger images are probably wanted (artwork packs). */
    const val IMAGE_MAX_BYTES = 2L * 1024 * 1024
    /** Sample clips are short; a 2 GB file with "sample" in the name is not one. */
    const val SAMPLE_MAX_BYTES = 300L * 1024 * 1024

    /** "sample" as its own word: "Sample.mkv", "movie-sample.mkv", "Sample/x.mkv" — not "Samples of Love". */
    private val SAMPLE_WORD = Regex("""(^|[^a-z0-9])sample([^a-z0-9]|$)""", RegexOption.IGNORE_CASE)

    fun extensionOf(filename: String): String {
        val base = filename.substringAfterLast('/')
        return if ('.' in base) base.substringAfterLast('.').lowercase() else ""
    }

    fun isVideo(item: DownloadItem): Boolean = extensionOf(item.filename) in VIDEO_EXTS

    private fun isSample(item: DownloadItem): Boolean =
        isVideo(item) && item.filesize in 0..SAMPLE_MAX_BYTES && SAMPLE_WORD.containsMatchIn(item.filename)

    private enum class Kind { TEXT, IMAGE, SAMPLE }

    /** What [item] would be if it turned out to be filler; null = a real file. */
    private fun candidateKind(item: DownloadItem): Kind? {
        val ext = extensionOf(item.filename)
        return when {
            ext in TEXT_EXTS && item.filesize in 0..TEXT_MAX_BYTES -> Kind.TEXT
            ext in IMAGE_EXTS && item.filesize in 0..IMAGE_MAX_BYTES -> Kind.IMAGE
            isSample(item) -> Kind.SAMPLE
            else -> null
        }
    }

    /**
     * Ids of the extras among [siblings], which must all be files of ONE
     * torrent. Used where the files of a single torrent are already in hand
     * (auto-download when ready, auto-remove after download).
     */
    fun extrasInTorrent(siblings: List<DownloadItem>): Set<String> {
        val kinds = siblings.associateWith(::candidateKind)
        val keepers = siblings.filter { kinds[it] == null }
        if (keepers.isEmpty()) return emptySet() // nothing else in it: keep everything
        val hasVideo = keepers.any(::isVideo)
        return kinds.filter { (_, kind) ->
            when (kind) {
                null -> false
                Kind.TEXT -> true
                // Covers and screenshots next to a video. Images next to, say,
                // a comic or an ebook are left alone.
                Kind.IMAGE, Kind.SAMPLE -> hasVideo
            }
        }.keys.mapTo(HashSet()) { it.id }
    }

    /** Ids of the extras in a mixed list, judged per torrent. */
    fun extraIds(items: List<DownloadItem>): Set<String> {
        val out = HashSet<String>()
        items.filter { it.parentRef != null }
            .groupBy { it.provider to it.parentRef }
            .values
            .forEach { out += extrasInTorrent(it) }
        return out
    }

    /** [items] without the extras, order preserved. */
    fun hide(items: List<DownloadItem>): List<DownloadItem> {
        val extras = extraIds(items)
        return if (extras.isEmpty()) items else items.filter { it.id !in extras }
    }

    /** One torrent's files without its extras, order preserved. */
    fun hideInTorrent(siblings: List<DownloadItem>): List<DownloadItem> {
        val extras = extrasInTorrent(siblings)
        return if (extras.isEmpty()) siblings else siblings.filter { it.id !in extras }
    }
}
