package com.abhinavxt.debforge.ui.history

import com.abhinavxt.debforge.data.history.HistoryEntity
import com.abhinavxt.debforge.data.history.HistoryKind
import com.abhinavxt.debforge.data.history.HistoryOrigin

const val CONTINUE_MAX = 10

/**
 * The "Continue watching" row from history rows: videos 5% to 95% through, one per show (the newest
 * episode) or item, newest first, at most [max]. [hidden] maps itemId to the playedAt it was hidden at;
 * a row stays hidden only until it is played again (a newer playedAt).
 */
fun continueWatching(rows: List<HistoryEntity>, hidden: Map<String, Long>, max: Int = CONTINUE_MAX): List<HistoryEntity> =
    rows.asSequence()
        .filter { it.kind == HistoryKind.VIDEO && it.origin != HistoryOrigin.YOUTUBE && it.durationMs > 0 } // YouTube replays open the watch page, not a resume
        .filter { it.positionMs * 20 >= it.durationMs && it.positionMs * 20 <= it.durationMs * 19 }
        .filter { (hidden[it.itemId] ?: Long.MIN_VALUE) < it.playedAt }
        .sortedByDescending { it.playedAt }
        .distinctBy { it.showKey?.takeIf { k -> k.isNotBlank() } ?: it.itemId }
        .take(max)
        .toList()

/** A hidden entry as stored in a string set: `playedAt|itemId`. */
fun encodeHidden(itemId: String, playedAt: Long) = "$playedAt|$itemId"

fun decodeHidden(set: Set<String>): Map<String, Long> {
    val out = HashMap<String, Long>()
    for (s in set) {
        val at = s.substringBefore('|').toLongOrNull() ?: continue
        val id = s.substringAfter('|')
        out[id] = maxOf(at, out[id] ?: at)
    }
    return out
}
