package com.abhinavxt.debforge.data.backup

import com.abhinavxt.debforge.data.search.SearchHistoryStore

/**
 * Recent searches as the "searches" section of a backup:
 * `{"v":1,"search":["q"],"youtube":["q"]}`. Pure; the decoder never throws.
 */
object SearchesBackup {
    data class Model(val search: List<String> = emptyList(), val youtube: List<String> = emptyList())

    fun toJson(m: Model): Map<String, Any?> = linkedMapOf("v" to 1, "search" to m.search, "youtube" to m.youtube)

    fun fromJson(value: Any?): Model {
        val m = value as? Map<*, *> ?: return Model()
        fun list(k: String) = (m[k] as? List<*>).orEmpty().filterIsInstance<String>()
            .map { it.trim() }.filter { it.isNotEmpty() && it.length <= 500 }.take(SearchHistoryStore.MAX * 4)
        return Model(list("search"), list("youtube"))
    }

    /** The existing entries first, then the file's new ones, case-insensitively de-duplicated, at most MAX. */
    fun merge(existing: List<String>, incoming: List<String>): List<String> {
        val seen = HashSet<String>()
        return (existing + incoming).filter { seen.add(it.lowercase()) }.take(SearchHistoryStore.MAX)
    }

    /** How many of [merged] were not in [existing]. */
    fun added(existing: List<String>, merged: List<String>): Int {
        val had = existing.map { it.lowercase() }.toSet()
        return merged.count { it.lowercase() !in had }
    }
}
