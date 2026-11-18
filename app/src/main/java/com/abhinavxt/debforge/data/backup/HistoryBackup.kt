package com.abhinavxt.debforge.data.backup

import com.abhinavxt.debforge.data.history.HISTORY_LIMIT
import com.abhinavxt.debforge.data.history.HistoryEntity
import com.abhinavxt.debforge.data.history.HistoryKind
import com.abhinavxt.debforge.data.history.HistoryOrigin
import com.abhinavxt.debforge.domain.ProviderId

/**
 * Play history as the "history" section of a backup:
 * `{"v":1,"items":[{"id":…,"k":kind,"o":origin,"p":provider,"s":sourceRef,"pr":parentRef,
 * "sk":showKey,"t":title,"st":subtitle,"a":artworkUrl,"pos":ms,"dur":ms,"at":ms}]}`.
 * Pure; the decoder never throws (broken rows are skipped).
 */
object HistoryBackup {

    fun toJson(rows: List<HistoryEntity>): Map<String, Any?> = linkedMapOf(
        "v" to 1,
        "items" to rows.map {
            linkedMapOf<String, Any?>(
                "id" to it.itemId, "k" to it.kind, "o" to it.origin, "p" to it.provider,
                "s" to it.sourceRef, "pr" to it.parentRef, "sk" to it.showKey,
                "t" to it.title, "st" to it.subtitle, "a" to it.artworkUrl,
                "pos" to it.positionMs, "dur" to it.durationMs, "at" to it.playedAt
            )
        }
    )

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /**
     * LOCAL-origin rows are dropped on restore: their sourceRef is a path or
     * content URI that only means something on the phone that wrote it.
     */
    fun fromJson(value: Any?, now: Long = System.currentTimeMillis()): List<HistoryEntity> {
        val m = value as? Map<*, *> ?: return emptyList()
        return (m["items"] as? List<*>).orEmpty().take(HISTORY_LIMIT * 4).mapNotNull { e ->
            val o = e as? Map<*, *> ?: return@mapNotNull null
            fun str(k: String, max: Int = 2048) = (o[k] as? String)?.takeIf { it.isNotEmpty() && it.length <= max }
            fun num(k: String) = (o[k] as? Number)?.toDouble()?.takeIf { it.isFinite() }?.toLong()
            val kind = str("k")?.takeIf { it == HistoryKind.VIDEO || it == HistoryKind.AUDIO } ?: return@mapNotNull null
            val origin = str("o")?.takeIf { it == HistoryOrigin.CLOUD || it == HistoryOrigin.YOUTUBE } ?: return@mapNotNull null
            val provider = if (o["p"] == null) null else ProviderId.fromName(o["p"] as? String)?.name ?: return@mapNotNull null
            val art = str("a")?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            val at = num("at")?.takeIf { it in 1..(now + DAY_MS) } ?: return@mapNotNull null
            HistoryEntity(
                itemId = str("id", 512) ?: return@mapNotNull null,
                kind = kind,
                origin = origin,
                provider = provider,
                sourceRef = str("s") ?: return@mapNotNull null,
                parentRef = str("pr"),
                showKey = str("sk", 512),
                title = (o["t"] as? String)?.takeIf { it.length <= 500 } ?: return@mapNotNull null,
                subtitle = str("st", 500),
                artworkUrl = art,
                positionMs = (num("pos") ?: 0L).coerceAtLeast(0),
                durationMs = (num("dur") ?: 0L).coerceAtLeast(0),
                playedAt = at
            )
        }.distinctBy { it.itemId }
    }

    /**
     * The rows worth writing: the newest [keep] of [incoming], and per item
     * only those newer (playedAt) than what [existing] (itemId -> playedAt) has.
     * The caller prunes to [keep] overall afterwards.
     */
    fun toWrite(existing: Map<String, Long>, incoming: List<HistoryEntity>, keep: Int = HISTORY_LIMIT): List<HistoryEntity> =
        incoming.sortedByDescending { it.playedAt }.take(keep)
            .filter { (existing[it.itemId] ?: Long.MIN_VALUE) < it.playedAt }
}
