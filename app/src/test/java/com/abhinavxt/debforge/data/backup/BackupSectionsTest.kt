package com.abhinavxt.debforge.data.backup

import com.abhinavxt.debforge.data.history.HistoryEntity
import com.abhinavxt.debforge.data.playback.AudioListsState
import com.abhinavxt.debforge.data.playback.Playlist
import com.abhinavxt.debforge.data.subscriptions.AutoDownload
import com.abhinavxt.debforge.data.subscriptions.SubscriptionEntity
import com.abhinavxt.debforge.data.subscriptions.SubscriptionKind
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupSectionsTest {
    private val adapter = Moshi.Builder().build().adapter<Map<String, Any?>>(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    )

    /** Through real JSON, so numbers arrive as Double like in a restore. */
    private fun viaJson(section: Map<String, Any?>): Any? = adapter.fromJson(adapter.toJson(mapOf("x" to section)))!!["x"]

    private fun row(id: String, at: Long, title: String = id) = HistoryEntity(
        id, "AUDIO", "CLOUD", "TORBOX", "src-$id", null, null, title, null, null, 1000, 5000, at
    )

    // ---- audio lists

    @Test fun audioListsRoundTrip() {
        val s = AudioListsState(linkedSetOf("a", "b"), listOf(Playlist("p1", "Mix", listOf("a", "c"))))
        assertEquals(s, AudioListsBackup.fromJson(viaJson(AudioListsBackup.toJson(s))))
    }

    @Test fun audioListsGarbageGivesEmptyOrSkips() {
        assertEquals(AudioListsState(), AudioListsBackup.fromJson("nope"))
        assertEquals(AudioListsState(), AudioListsBackup.fromJson(null))
        assertEquals(AudioListsState(), AudioListsBackup.fromJson(mapOf("fav" to "x", "lists" to 5)))
        val s = AudioListsBackup.fromJson(
            mapOf(
                "fav" to listOf("a", 3, null, "", "a"),
                "lists" to listOf(null, "x", mapOf("i" to "p"), mapOf("i" to "p", "n" to "Ok", "t" to listOf("z", 1, "z")))
            )
        )
        assertEquals(listOf("a"), s.favourites.toList())
        assertEquals(listOf(Playlist("p", "Ok", listOf("z"))), s.playlists)
    }

    @Test fun mergeFavouritesKeepsExistingOrderThenNew() {
        val cur = AudioListsState(linkedSetOf("b", "a"))
        val m = AudioListsBackup.merge(cur, AudioListsState(linkedSetOf("a", "c", "d")))
        assertEquals(listOf("b", "a", "c", "d"), m.state.favourites.toList())
        assertEquals(2, m.favouritesAdded)
    }

    @Test fun mergePlaylistsBySameIdKeepsLocalNameAndAppends() {
        val cur = AudioListsState(playlists = listOf(Playlist("p1", "Local", listOf("a"))))
        val inc = AudioListsState(
            playlists = listOf(Playlist("p1", "Remote", listOf("a", "b")), Playlist("p2", "local", listOf("x")))
        )
        val m = AudioListsBackup.merge(cur, inc)
        assertEquals(Playlist("p1", "Local", listOf("a", "b")), m.state.playlists[0])
        assertEquals(Playlist("p2", "local (2)", listOf("x")), m.state.playlists[1])
        assertEquals(1, m.playlistsAdded)
    }

    @Test fun mergeIsIdempotent() {
        val cur = AudioListsState(linkedSetOf("a"), listOf(Playlist("p", "P", listOf("a"))))
        val once = AudioListsBackup.merge(cur, cur)
        assertEquals(cur, once.state)
        assertEquals(0, once.favouritesAdded + once.playlistsAdded)
    }

    // ---- history

    @Test fun historyRoundTrip() {
        val rows = listOf(
            row("a", 10).copy(subtitle = "s", artworkUrl = "https://u/a.jpg", showKey = "k", parentRef = "pr"),
            row("b", 5).copy(provider = null)
        )
        assertEquals(rows, HistoryBackup.fromJson(viaJson(HistoryBackup.toJson(rows))))
    }

    @Test fun historyGarbageSkipped() {
        assertTrue(HistoryBackup.fromJson(listOf(1)).isEmpty())
        assertTrue(HistoryBackup.fromJson(mapOf("items" to "x")).isEmpty())
        val ok = HistoryBackup.toJson(listOf(row("a", 10)))["items"] as List<*>
        val parsed = HistoryBackup.fromJson(
            mapOf(
                "items" to listOf(
                    null, "x", mapOf("id" to "z"), // missing fields
                    mapOf("id" to "n", "k" to "AUDIO", "o" to "CLOUD", "s" to "s", "t" to "t", "at" to 1e300),
                    mapOf("id" to "neg", "k" to "AUDIO", "o" to "CLOUD", "s" to "s", "t" to "t", "at" to 5, "pos" to -4)
                ) + ok
            )
        )
        // an impossible date is rejected
        assertEquals(setOf("neg", "a"), parsed.map { it.itemId }.toSet())
        assertEquals(0L, parsed.first { it.itemId == "neg" }.positionMs)
    }

    @Test fun historyRejectsFutureDatesUnknownTypesAndBadFields() {
        fun item(vararg over: Pair<String, Any?>) = HistoryBackup.toJson(listOf(row("a", 1000)))
            .let { (it["items"] as List<*>)[0] as Map<*, *> }
            .let { m -> m.entries.associate { it.key as String to it.value }.toMutableMap().also { it.putAll(over) } }
        fun parse(vararg over: Pair<String, Any?>) =
            HistoryBackup.fromJson(mapOf("items" to listOf(item(*over))), now = 5000)
        assertEquals(1, parse().size)
        assertTrue(parse("at" to 5000L + 2 * 24 * 3600 * 1000).isEmpty())
        assertEquals(1, parse("at" to 5000L).size)
        assertTrue(parse("k" to "PHOTO").isEmpty())
        assertTrue(parse("o" to "MARS").isEmpty())
        assertTrue(parse("o" to "LOCAL").isEmpty())
        assertTrue(parse("p" to "NOPE").isEmpty())
        assertEquals(null, parse("p" to null).single().provider)
        assertTrue(parse("t" to "x".repeat(501)).isEmpty())
        assertTrue(parse("s" to "x".repeat(2049)).isEmpty())
        assertEquals(null, parse("a" to "file:///x").single().artworkUrl)
        assertEquals("https://x/y.png", parse("a" to "https://x/y.png").single().artworkUrl)
    }

    @Test fun historyNewestWinsAndOldIsIgnored() {
        val existing = mapOf("a" to 100L, "b" to 100L)
        val todo = HistoryBackup.toWrite(existing, listOf(row("a", 50), row("b", 200), row("c", 10)))
        assertEquals(setOf("b", "c"), todo.map { it.itemId }.toSet())
        assertTrue(HistoryBackup.toWrite(existing, listOf(row("a", 100))).isEmpty()) // equal is not newer
    }

    @Test fun historyOnlyNewestKeptWhenOverLimit() {
        val rows = (1..10L).map { row("r$it", it) }
        assertEquals(listOf("r10", "r9", "r8"), HistoryBackup.toWrite(emptyMap(), rows, keep = 3).map { it.itemId })
    }

    // ---- searches

    @Test fun searchesRoundTripAndGarbage() {
        val m = SearchesBackup.Model(listOf("a", "b"), listOf("y"))
        assertEquals(m, SearchesBackup.fromJson(viaJson(SearchesBackup.toJson(m))))
        assertEquals(SearchesBackup.Model(), SearchesBackup.fromJson("x"))
        assertEquals(listOf("q"), SearchesBackup.fromJson(mapOf("search" to listOf(1, null, " ", " q "))).search)
    }

    @Test fun searchesMergeFileFirstDedupedAndCapped() {
        val existing = listOf("one", "Two", "three")
        val merged = SearchesBackup.merge(existing, listOf("TWO", "four"))
        assertEquals(listOf("one", "Two", "three", "four"), merged)
        assertEquals(1, SearchesBackup.added(existing, merged))
        val many = SearchesBackup.merge((1..8).map { "e$it" }, (1..8).map { "i$it" })
        assertEquals(10, many.size)
        assertEquals("e1", many.first())
        assertEquals(listOf("e1", "e2", "e3", "e4", "e5", "e6", "e7", "e8", "i1", "i2"), many)
    }

    // ---- file level

    @Test fun oldFormatWithoutNewSectionsDecodesToEmpty() {
        val root = adapter.fromJson("""{"app":"DebForge","format":1,"settings":{"wifi_only":true}}""")!!
        assertEquals(AudioListsState(), AudioListsBackup.fromJson(root["audioLists"]))
        assertTrue(HistoryBackup.fromJson(root["history"]).isEmpty())
        assertEquals(SearchesBackup.Model(), SearchesBackup.fromJson(root["searches"]))
    }

    // ---- subscriptions

    private fun sub(url: String, kind: SubscriptionKind = SubscriptionKind.YT_CHANNEL, auto: AutoDownload = AutoDownload.OFF) =
        SubscriptionEntity(id = 7, kind = kind, url = url, title = "T $url", artworkUrl = "https://x/a.jpg", autoDownload = auto, since = 5, etag = "e", lastChecked = 9)

    @Test fun subscriptionsRoundTrip() {
        val ch = "https://www.youtube.com/channel/UCabcdefghijklmnopqrstuv"
        val subs = listOf(sub(ch, auto = AutoDownload.AUDIO), sub("https://pod.example/feed.xml", SubscriptionKind.PODCAST, AutoDownload.VIDEO))
        val back = SubscriptionsBackup.fromJson(viaJson(SubscriptionsBackup.toJson(subs)))
        assertEquals(2, back.size)
        assertEquals(SubscriptionsBackup.Item(SubscriptionKind.YT_CHANNEL, ch, "T $ch", "https://x/a.jpg", AutoDownload.AUDIO), back.first { it.url == ch })
        assertEquals(AutoDownload.VIDEO, back.first { it.kind == SubscriptionKind.PODCAST }.autoDownload)
    }

    @Test fun subscriptionsIgnoreFeedStateAndGarbage() {
        val json = SubscriptionsBackup.toJson(listOf(sub("https://pod.example/f", SubscriptionKind.PODCAST))).toString()
        assertTrue("etag" !in json && "since" !in json)
        assertTrue(SubscriptionsBackup.fromJson("x").isEmpty())
        val bad = mapOf("items" to listOf(1, mapOf("k" to "NOPE", "u" to "https://a"), mapOf("k" to "PODCAST", "u" to "ftp://a"), mapOf("k" to "PODCAST", "u" to "https://ok/f")))
        assertEquals(listOf("https://ok/f"), SubscriptionsBackup.fromJson(bad).map { it.url })
    }

    @Test fun oldBackupWithoutSectionRestoresNothing() {
        val root = adapter.fromJson("""{"app":"DebForge","format":1,"settings":{}}""")!!
        assertTrue(SubscriptionsBackup.fromJson(root["subscriptions"]).isEmpty())
    }

    @Test fun subscriptionsMergeSkipsExistingAndStartsFresh() {
        val incoming = listOf(
            SubscriptionsBackup.Item(SubscriptionKind.PODCAST, "https://have/f", "Have"),
            SubscriptionsBackup.Item(SubscriptionKind.PODCAST, "https://new/f", "New", null, AutoDownload.AUDIO)
        )
        val ins = SubscriptionsBackup.toInsert(incoming, setOf("https://have/f"), now = 1234)
        assertEquals(1, ins.size)
        assertEquals("https://new/f", ins[0].url)
        assertEquals(1234L, ins[0].since)
        assertEquals(0L, ins[0].lastChecked)
        assertEquals(0L, ins[0].id)
        assertTrue(SubscriptionsBackup.toInsert(incoming, setOf("https://have/f", "https://new/f"), 1).isEmpty())
    }

    @Test fun subscriptionsExportIsCapped() {
        val many = (1..SubscriptionsBackup.MAX_EXPORT + 50).map { sub("https://p/$it", SubscriptionKind.PODCAST) }
        assertEquals(SubscriptionsBackup.MAX_EXPORT, (SubscriptionsBackup.toJson(many)["items"] as List<*>).size)
    }
}
