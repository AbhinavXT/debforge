package com.abhinavxt.debforge.data.follow

import com.abhinavxt.debforge.domain.Follow
import com.abhinavxt.debforge.domain.ProviderId

/**
 * Followed shows in DebForge's own backup file (the "follows" list), and how
 * a restore merges them into what the phone already follows. Pure: unit-
 * tested in FollowBackupTest.
 *
 * Nothing secret in here, so it sits next to "settings", not in the
 * encrypted part. Older DebForge versions ignore the key.
 */
object FollowBackup {

    fun toJson(follows: List<Follow>): List<Map<String, Any?>> = follows.map { f ->
        linkedMapOf(
            "provider" to f.provider.name,
            "showKey" to f.showKey,
            "title" to f.title,
            "since" to f.since,
            "resolution" to f.resolution,
            "seen" to f.seen.sorted()
        )
    }

    /** Entries from a backup; unknown services or broken entries are skipped. */
    fun fromJson(value: Any?): List<Follow> = (value as? List<*>).orEmpty().mapNotNull { e ->
        val m = e as? Map<*, *> ?: return@mapNotNull null
        Follow(
            provider = ProviderId.fromName(m["provider"] as? String) ?: return@mapNotNull null,
            showKey = (m["showKey"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null,
            title = m["title"] as? String ?: return@mapNotNull null,
            // JSON numbers arrive as Double from Moshi.
            since = (m["since"] as? Number)?.toLong() ?: return@mapNotNull null,
            resolution = (m["resolution"] as? String)?.takeIf { it.isNotBlank() },
            seen = (m["seen"] as? List<*>).orEmpty().filterIsInstance<String>().toSet()
        )
    }

    /**
     * [current] plus [incoming]. A show followed on both keeps the phone's
     * own entry, with both "already handled" sets combined, so nothing is
     * queued twice. Returns the merged list and how many shows were new.
     */
    fun merge(current: List<Follow>, incoming: List<Follow>): Pair<List<Follow>, Int> {
        val byKey = LinkedHashMap<Pair<ProviderId, String>, Follow>()
        current.forEach { byKey[it.provider to it.showKey] = it }
        var added = 0
        incoming.forEach { f ->
            val key = f.provider to f.showKey
            val existing = byKey[key]
            if (existing == null) {
                byKey[key] = f
                added++
            } else {
                byKey[key] = existing.copy(seen = existing.seen + f.seen)
            }
        }
        return byKey.values.toList() to added
    }
}
