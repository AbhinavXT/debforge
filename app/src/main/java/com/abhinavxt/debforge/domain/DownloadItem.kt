package com.abhinavxt.debforge.domain

/**
 * A downloads-list item as the UI consumes it. Mapped from DownloadDto, decoupled
 * from the network DTO so the API shape can change without touching the UI.
 */
data class DownloadItem(
    val id: String,
    val filename: String,
    val originalLink: String,
    val downloadUrl: String,
    val host: String,
    val filesize: Long,
    val chunks: Int,
    val streamable: Boolean,
    val generated: String?
)

/**
 * Lightweight result wrapper for one-shot repository calls so the ViewModel can
 * branch on success/failure without exceptions leaking into UI code.
 */
sealed interface DataResult<out T> {
    data class Success<T>(val data: T) : DataResult<T>
    data class Error(val message: String, val cause: Throwable? = null) : DataResult<Nothing>
}
