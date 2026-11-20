package com.abhinavxt.debforge.data.backup

import com.abhinavxt.debforge.data.search.SearchSource
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretsBackupTest {
    private val adapter = Moshi.Builder().build().adapter<Map<String, Any?>>(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    )

    private fun viaJson(m: Map<String, Any?>) = adapter.fromJson(adapter.toJson(m))!!

    @Test fun roundTripThroughEncryptedPayload() {
        val model = SecretsBackup.Model(
            openSubtitles = SecretsBackup.OpenSubtitles("osKey", "me", "pw"),
            searchSources = listOf(SearchSource("1", "Jackett", "http://h:9117", "k1"), SearchSource("2", "Prowlarr", "http://p", "k2")),
            trakt = SecretsBackup.Trakt("cid", "acc", "ref", 1_800_000_000_000L, "user")
        )
        val payload = linkedMapOf<String, Any?>("active" to "TORBOX", "tmdbKey" to "t")
        SecretsBackup.addTo(payload, model)
        val sealed = BackupCrypto.seal(adapter.toJson(payload).toByteArray(), "password1".toCharArray())
        val opened = adapter.fromJson(String(BackupCrypto.open(sealed, "password1".toCharArray())))!!
        assertEquals(model, SecretsBackup.fromMap(opened))
        assertEquals("TORBOX", opened["active"])
    }

    @Test fun oldPayloadWithoutNewFieldsDecodesToEmpty() {
        val old = viaJson(mapOf("active" to "TORBOX", "signIns" to mapOf<String, Any?>(), "tmdbKey" to "t"))
        val m = SecretsBackup.fromMap(old)
        assertNull(m.openSubtitles)
        assertNull(m.trakt)
        assertTrue(m.searchSources.isEmpty())
    }

    @Test fun keyOnlyOpenSubtitlesAndMalformedEntriesAreHandled() {
        val m = SecretsBackup.fromMap(
            viaJson(
                mapOf(
                    "openSubtitles" to mapOf("apiKey" to "k", "username" to null, "password" to ""),
                    "searchSources" to listOf("junk", mapOf("id" to "1")),
                    "trakt" to mapOf("clientId" to "")
                )
            )
        )
        assertEquals(SecretsBackup.OpenSubtitles("k", null, null), m.openSubtitles)
        assertTrue(m.searchSources.isEmpty())
        assertNull(m.trakt)
    }

    @Test fun openSubtitlesKeepsLocalAccountUnlessBackupHasBoth() {
        val local = SecretsBackup.OpenSubtitles("old", "me", "pw")
        assertEquals(
            SecretsBackup.OpenSubtitles("new", "me", "pw"),
            SecretsBackup.mergeOpenSubtitles(local, SecretsBackup.OpenSubtitles("new", null, null))
        )
        assertEquals(
            SecretsBackup.OpenSubtitles("new", "me", "pw"),
            SecretsBackup.mergeOpenSubtitles(local, SecretsBackup.OpenSubtitles("new", "me", null))
        )
        assertEquals(
            SecretsBackup.OpenSubtitles("new", "u2", "p2"),
            SecretsBackup.mergeOpenSubtitles(local, SecretsBackup.OpenSubtitles("new", "u2", "p2"))
        )
    }

    @Test fun blankKeySourcesSurviveParsing() {
        val m = SecretsBackup.fromMap(viaJson(mapOf("searchSources" to listOf(mapOf("id" to "1", "name" to "J", "url" to "http://h", "apiKey" to "")))))
        assertEquals(listOf(SearchSource("1", "J", "http://h", "")), m.searchSources)
    }

    @Test fun searchSourcesMergeByIdAndUrl() {
        val local = listOf(SearchSource("1", "A", "http://Host:9117/", ""), SearchSource("2", "B", "http://b", "kb"))
        val backup = listOf(
            SearchSource("x", "A2", " http://host:9117 ", "filled"),
            SearchSource("2", "B2", "http://other", "zzz"),
            SearchSource("3", "C", "http://c", "kc")
        )
        val out = SecretsBackup.mergeSearchSources(local, backup)
        assertEquals(listOf(SearchSource("1", "A", "http://Host:9117/", "filled"), SearchSource("2", "B", "http://b", "kb"), SearchSource("3", "C", "http://c", "kc")), out)
    }

    @Test fun traktPlanIsAUnit() {
        val local = SecretsBackup.Trakt("c", "a", "r", 5L, "u1")
        val full = SecretsBackup.Trakt("c2", "a2", null, null, "u2")
        val p = SecretsBackup.planTrakt(local, full)!!
        assertEquals(full, p.value)
        assertTrue(p.resetWatched)
        assertTrue(!SecretsBackup.planTrakt(local, full.copy(username = "u1"))!!.resetWatched)
        val idOnly = SecretsBackup.Trakt("c3", null, null, null, null)
        assertNull(SecretsBackup.planTrakt(local, idOnly))
        assertEquals(idOnly, SecretsBackup.planTrakt(null, idOnly)!!.value)
        assertEquals(idOnly, SecretsBackup.planTrakt(SecretsBackup.Trakt("c", null, null, null, null), idOnly)!!.value)
    }
}
