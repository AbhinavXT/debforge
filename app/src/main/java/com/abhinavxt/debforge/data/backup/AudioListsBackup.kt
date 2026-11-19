package com.abhinavxt.debforge.data.backup

import com.abhinavxt.debforge.data.playback.AudioListsState
import com.abhinavxt.debforge.data.playback.Playlist
import com.abhinavxt.debforge.data.playback.pruneMeta
import com.abhinavxt.debforge.data.playback.PlaylistEntry
import com.abhinavxt.debforge.data.playback.YouTubeMeta

/**
 * Audio favourites and playlists as the "audioLists" section of a backup:
 * `{"v":1,"fav":["id"],"lists":[{"i":"uuid","n":"name","t":["id"]}],"ym":{"videoId":{"t":"title","c":"channel","d":sec}}}`.
 * Pure; the decoder never throws. Older app versions ignore the section.
 */
object AudioListsBackup {
    private const val MAX_ITEMS = 20_000

    data class Merged(val state: AudioListsState, val favouritesAdded: Int, val playlistsAdded: Int)

    fun toJson(s: AudioListsState): Map<String, Any?> = linkedMapOf(
        "v" to 1,
        "fav" to s.favourites.toList(),
        "lists" to s.playlists.map { linkedMapOf("i" to it.id, "n" to it.name, "t" to it.trackIds) },
        "ym" to s.ytMeta.mapValues { (_, m) ->
            linkedMapOf<String, Any?>("t" to m.title, "c" to m.channel, "d" to m.durationSec).filterValues { it != null }
        }
    )

    fun fromJson(value: Any?): AudioListsState {
        val m = value as? Map<*, *> ?: return AudioListsState()
        val fav = (m["fav"] as? List<*>).orEmpty().asSequence()
            .filterIsInstance<String>().filter { it.isNotBlank() }.take(MAX_ITEMS).toCollection(LinkedHashSet())
        val lists = (m["lists"] as? List<*>).orEmpty().take(MAX_ITEMS).mapNotNull { e ->
            val o = e as? Map<*, *> ?: return@mapNotNull null
            val id = (o["i"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = (o["n"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val ids = (o["t"] as? List<*>).orEmpty().filterIsInstance<String>().filter { it.isNotBlank() }.take(MAX_ITEMS)
            Playlist(id, name, ids.distinct())
        }.distinctBy { it.id }
        // Favourites are download-only; drop any YouTube token a hand-edited backup might carry.
        val ym = (m["ym"] as? Map<*, *>).orEmpty().entries.asSequence().take(MAX_ITEMS).mapNotNull { (k, v) ->
            val key = (k as? String)?.takeIf { PlaylistEntry.isValidVideoId(it) } ?: return@mapNotNull null
            val meta = (v as? Map<*, *>)?.let { YouTubeMeta.fromMap(it) } ?: return@mapNotNull null
            key to meta
        }.toMap()
        return AudioListsState(fav.filterNotTo(LinkedHashSet()) { PlaylistEntry.isYouTubeToken(it) }, lists, ym)
    }

    /**
     * Union, never a deletion. Favourites: existing order, then new ids.
     * Playlists by id: an unknown id is added (its name made unique if another
     * list already has it); a known id keeps the local name and gains the track
     * ids it lacks.
     */
    fun merge(current: AudioListsState, incoming: AudioListsState): Merged {
        val fav = LinkedHashSet(current.favourites)
        val favAdded = incoming.favourites.count { fav.add(it) }

        val lists = current.playlists.toMutableList()
        var added = 0
        incoming.playlists.forEach { p ->
            val at = lists.indexOfFirst { it.id == p.id }
            if (at >= 0) {
                val have = lists[at]
                lists[at] = have.copy(trackIds = (have.trackIds + p.trackIds).distinct())
            } else {
                val taken = lists.map { it.name.trim().lowercase() }.toSet()
                var name = p.name.trim()
                var k = 2
                while (name.lowercase() in taken) name = "${p.name.trim()} (${k++})"
                lists += p.copy(name = name, trackIds = p.trackIds.distinct())
                added++
            }
        }
        // Union; the local entry wins, incoming fills only missing ids.
        val ym = incoming.ytMeta + current.ytMeta
        return Merged(AudioListsState(fav, lists, ym).pruneMeta(), favAdded, added)
    }
}
