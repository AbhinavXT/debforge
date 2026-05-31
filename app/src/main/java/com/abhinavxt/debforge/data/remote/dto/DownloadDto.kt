package com.abhinavxt.debforge.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * One item from GET /downloads. The endpoint returns a JSON array of these.
 *
 * Field notes (from the RD REST 1.0 contract):
 *  - [link]      original hoster link; used to RE-UNRESTRICT when [download] is dead.
 *  - [download]  generated direct URL; this is what we actually fetch bytes from,
 *                but it can expire — hence the [link] fallback.
 *  - [chunks]    max parallel connections RD's server allows for this file.
 *                This is our parallelism cap for the chunked downloader.
 *  - [filesize]  exact size in bytes; used for preallocation + completeness check.
 *  - [streamable] 1 if RD can stream it; informational only for now (v1 downloads).
 *  - [generated] ISO-8601 timestamp the [download] link was generated.
 */
@JsonClass(generateAdapter = true)
data class DownloadDto(
    @Json(name = "id") val id: String,
    @Json(name = "filename") val filename: String,
    @Json(name = "mimeType") val mimeType: String?,
    @Json(name = "filesize") val filesize: Long,
    @Json(name = "link") val link: String,
    @Json(name = "host") val host: String,
    @Json(name = "chunks") val chunks: Int,
    @Json(name = "download") val download: String,
    @Json(name = "streamable") val streamable: Int?,
    @Json(name = "generated") val generated: String?
)
