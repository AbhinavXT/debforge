package com.abhinavxt.debforge.data.provider.torbox.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Every TorBox endpoint wraps its payload in the same envelope:
 *   { "success": true, "error": null, "detail": "...", "data": <payload> }
 * API-level failures can come back as HTTP 200 with success=false, so callers
 * must check [success], not just the status code.
 */
@JsonClass(generateAdapter = true)
data class TbEnvelope<T>(
    @Json(name = "success") val success: Boolean,
    @Json(name = "error") val error: String?,
    @Json(name = "detail") val detail: String?,
    @Json(name = "data") val data: T?
)

/** GET /user/me. We only read what the Setup/Settings screens show. */
@JsonClass(generateAdapter = true)
data class TbUserDto(
    @Json(name = "id") val id: Long,
    @Json(name = "email") val email: String?,
    /** 0 = free, 1 = essential, 2 = pro, 3 = standard. */
    @Json(name = "plan") val plan: Int?,
    @Json(name = "premium_expires_at") val premiumExpiresAt: String?
)

/**
 * One torrent / usenet job / web download from the `mylist` endpoints. The
 * three kinds share this shape closely enough to use one DTO; Moshi ignores
 * the fields we don't declare (seeds, peers, hash, ...).
 *
 *  - [downloadPresent]  files are on TorBox's servers and can be fetched now.
 *  - [downloadFinished] the job itself is done (torrent fully fetched, etc.).
 */
@JsonClass(generateAdapter = true)
data class TbItemDto(
    @Json(name = "id") val id: Long,
    @Json(name = "name") val name: String?,
    @Json(name = "size") val size: Long?,
    @Json(name = "created_at") val createdAt: String?,
    @Json(name = "download_present") val downloadPresent: Boolean?,
    @Json(name = "download_finished") val downloadFinished: Boolean?,
    @Json(name = "files") val files: List<TbFileDto>?,
    // Live status for items still being fetched. Numbers as Double: the API
    // sends some of these as floats.
    @Json(name = "progress") val progress: Double? = null,
    @Json(name = "download_state") val downloadState: String? = null,
    @Json(name = "eta") val eta: Double? = null,
    @Json(name = "download_speed") val downloadSpeed: Double? = null
)

/**
 * One file inside an item. [name] is the full relative path
 * ("Torrent Name/Season 1/file.mkv"); [shortName] is just the file name.
 */
@JsonClass(generateAdapter = true)
data class TbFileDto(
    @Json(name = "id") val id: Long,
    @Json(name = "name") val name: String?,
    @Json(name = "short_name") val shortName: String?,
    @Json(name = "size") val size: Long?,
    @Json(name = "mimetype") val mimetype: String?
)
