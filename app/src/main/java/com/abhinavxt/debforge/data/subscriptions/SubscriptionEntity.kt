package com.abhinavxt.debforge.data.subscriptions

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Persisted by name: add entries, never rename. */
enum class SubscriptionKind { YT_CHANNEL, PODCAST }

/** Persisted by name: add entries, never rename. */
enum class AutoDownload { OFF, AUDIO, VIDEO }

/** A followed YouTube channel or podcast feed. */
@Entity(tableName = "subscriptions", indices = [Index(value = ["url"], unique = true)])
data class SubscriptionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: SubscriptionKind,
    val url: String,
    val title: String,
    val artworkUrl: String? = null,
    @ColumnInfo(defaultValue = "OFF") val autoDownload: AutoDownload = AutoDownload.OFF,
    /** Epoch ms when followed; only items published after it count as new / auto-download. */
    val since: Long,
    val etag: String? = null,
    val lastModified: String? = null,
    @ColumnInfo(defaultValue = "0") val lastChecked: Long = 0
)

/** One video or episode of a subscription. */
@Entity(
    tableName = "feed_items",
    primaryKeys = ["subId", "itemKey"],
    foreignKeys = [
        ForeignKey(
            entity = SubscriptionEntity::class,
            parentColumns = ["id"],
            childColumns = ["subId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["subId"])]
)
data class FeedItemEntity(
    val subId: Long,
    /** YouTube video id or podcast guid. */
    val itemKey: String,
    val title: String,
    val mediaUrl: String? = null,
    val publishedAt: Long,
    val durationSec: Long? = null,
    val thumbUrl: String? = null,
    val isNew: Boolean,
    /**
     * Auto-download has handled this item: it was queued, or it can never be (private, members-only, live,
     * no usable format). Items that failed for a passing reason stay false and are retried on the next check.
     * Independent of [isNew], so a handled item still shows in the New row.
     */
    val queued: Boolean
)

/** A feed item joined with its subscription's title, for the "New" row. */
data class NewFeedItem(
    val subId: Long,
    val itemKey: String,
    val title: String,
    val mediaUrl: String?,
    val publishedAt: Long,
    val durationSec: Long?,
    val thumbUrl: String?,
    val subTitle: String,
    val kind: SubscriptionKind
)
