package com.abhinavxt.debforge.domain

/**
 * A downloadable file as the UI and engine consume it. Every provider maps its
 * own API shape into this, so nothing above the provider layer knows whether a
 * file came from Real-Debrid, TorBox, or anything added later.
 *
 *  - [id]             globally unique + stable across refreshes. Providers
 *                     namespace their ids (e.g. "tb:torrent:12:3") so two
 *                     services can never collide in the local DB.
 *  - [sourceRef]      opaque handle the SAME provider can turn back into a
 *                     fresh direct URL via [com.abhinavxt.debforge.data.provider.DebridProvider.resolveLink].
 *                     RD: the original hoster link. TorBox: "kind:itemId:fileId".
 *  - [downloadUrl]    direct URL if the listing already carries one (RD does),
 *                     null if it must be resolved lazily at download time
 *                     (TorBox mints links on demand via /requestdl).
 *  - [host]           short origin label for the subtitle and search
 *                     (RD: hoster domain, TorBox: "Torrent · <torrent name>").
 *  - [maxConnections] parallel-connection cap for the chunked downloader.
 *  - [addedAt]        ISO-8601 timestamp used for date sorting.
 */
data class DownloadItem(
    val id: String,
    val provider: ProviderId,
    val filename: String,
    val sourceRef: String,
    val downloadUrl: String?,
    val host: String,
    val filesize: Long,
    val maxConnections: Int,
    val addedAt: String?
)

/** One page of a provider listing. [hasMore] drives infinite scroll. */
data class Page<T>(val items: List<T>, val hasMore: Boolean)

/**
 * Lightweight result wrapper for one-shot repository calls so the ViewModel can
 * branch on success/failure without exceptions leaking into UI code.
 */
sealed interface DataResult<out T> {
    data class Success<T>(val data: T) : DataResult<T>
    data class Error(val message: String, val cause: Throwable? = null) : DataResult<Nothing>
}
