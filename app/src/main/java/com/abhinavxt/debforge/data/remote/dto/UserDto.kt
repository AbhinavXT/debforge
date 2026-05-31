package com.abhinavxt.debforge.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Response from GET /user. Used only to validate the token and show basic
 * account info. We keep the fields we care about; Moshi ignores the rest.
 */
@JsonClass(generateAdapter = true)
data class UserDto(
    @Json(name = "id") val id: Long,
    @Json(name = "username") val username: String,
    @Json(name = "email") val email: String?,
    @Json(name = "premium") val premiumSeconds: Long?,
    @Json(name = "expiration") val expiration: String?,
    @Json(name = "type") val type: String?
)
