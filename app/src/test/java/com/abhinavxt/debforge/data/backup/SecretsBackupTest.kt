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
}
