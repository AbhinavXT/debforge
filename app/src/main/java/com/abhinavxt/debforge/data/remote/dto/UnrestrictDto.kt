package com.abhinavxt.debforge.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Response from POST /unrestrict/link. We call this when a stored [DownloadDto.download]
 * URL has gone dead, passing the original hoster [DownloadDto.link] to mint a fresh
 * direct [download] URL.
 *
 * [chunks] here is authoritative for the freshly generated link and should override
 * any previously stored chunk count.
 */
@JsonClass(generateAdapter = true)
data class UnrestrictDto(
    @Json(name = "id") val id: String,
    @Json(name = "filename") val filename: String,
    @Json(name = "filesize") val filesize: Long,
    @Json(name = "link") val link: String,
    @Json(name = "host") val host: String,
    @Json(name = "chunks") val chunks: Int,
    @Json(name = "crc") val crc: Int?,
    @Json(name = "download") val download: String,
    @Json(name = "streamable") val streamable: Int?
)
