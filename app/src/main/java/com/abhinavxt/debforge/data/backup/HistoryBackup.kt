package com.abhinavxt.debforge.data.backup

import com.abhinavxt.debforge.data.history.HISTORY_LIMIT
import com.abhinavxt.debforge.data.history.HistoryEntity

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

    fun fromJson(value: Any?): List<HistoryEntity> {
        val m = value as? Map<*, *> ?: return emptyList()
        return (m["items"] as? List<*>).orEmpty().take(HISTORY_LIMIT * 4).mapNotNull { e ->
            val o = e as? Map<*, *> ?: return@mapNotNull null
            fun str(k: String) = (o[k] as? String)?.takeIf { it.isNotEmpty() }
            fun num(k: String) = (o[k] as? Number)?.toDouble()?.takeIf { it.isFinite() }?.toLong()
            HistoryEntity(
                itemId = str("id") ?: return@mapNotNull null,
                kind = str("k") ?: return@mapNotNull null,
                origin = str("o") ?: return@mapNotNull null,
                provider = str("p"),
                sourceRef = str("s") ?: return@mapNotNull null,
                parentRef = str("pr"),
                showKey = str("sk"),
                title = o["t"] as? String ?: return@mapNotNull null,
                subtitle = str("st"),
                artworkUrl = str("a"),
                positionMs = (num("pos") ?: 0L).coerceAtLeast(0),
                durationMs = (num("dur") ?: 0L).coerceAtLeast(0),
                playedAt = num("at")?.takeIf { it > 0 } ?: return@mapNotNull null
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
