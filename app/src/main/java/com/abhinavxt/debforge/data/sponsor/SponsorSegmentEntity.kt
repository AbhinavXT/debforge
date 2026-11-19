package com.abhinavxt.debforge.data.sponsor

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Cached SponsorBlock segments of one YouTube video, as the API's JSON. */
@Entity(tableName = "sponsor_segments")
data class SponsorSegmentEntity(
    @PrimaryKey val videoId: String,
    val json: String,
    val fetchedAt: Long
)
