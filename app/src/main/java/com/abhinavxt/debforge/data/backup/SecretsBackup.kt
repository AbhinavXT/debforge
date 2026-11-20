package com.abhinavxt.debforge.data.backup

import com.abhinavxt.debforge.data.search.SearchSource

/**
 * The extra credentials inside the encrypted "secrets" blob, next to the debrid
 * sign-ins and the TMDB key: OpenSubtitles, search sources and Trakt. Every key is
 * optional, so older backups restore and older versions ignore what they don't know.
 */
object SecretsBackup {
    data class OpenSubtitles(val apiKey: String, val username: String?, val password: String?)

    data class Trakt(
        val clientId: String,
        val accessToken: String?,
        val refreshToken: String?,
        val expiresAt: Long?,
        val username: String?
    )

    data class Model(
        val openSubtitles: OpenSubtitles? = null,
        val searchSources: List<SearchSource> = emptyList(),
        val trakt: Trakt? = null
    )

    /** Adds the non-empty parts to [out]. */
    fun addTo(out: MutableMap<String, Any?>, m: Model) {
        m.openSubtitles?.let {
            out["openSubtitles"] = linkedMapOf("apiKey" to it.apiKey, "username" to it.username, "password" to it.password)
        }
        if (m.searchSources.isNotEmpty()) {
            out["searchSources"] = m.searchSources.map {
                linkedMapOf("id" to it.id, "name" to it.name, "url" to it.url, "apiKey" to it.apiKey)
            }
        }
        m.trakt?.let {
            out["trakt"] = linkedMapOf(
                "clientId" to it.clientId, "accessToken" to it.accessToken, "refreshToken" to it.refreshToken,
                "expiresAt" to it.expiresAt, "username" to it.username
            )
        }
    }

    /** Reads what is there from the decrypted [secrets]; anything missing or malformed is skipped. */
    fun fromMap(secrets: Map<String, Any?>): Model {
        fun str(m: Map<*, *>, k: String) = (m[k] as? String)?.takeIf { it.isNotBlank() }
        val os = (secrets["openSubtitles"] as? Map<*, *>)?.let { m ->
            str(m, "apiKey")?.let { OpenSubtitles(it, str(m, "username"), (m["password"] as? String)?.takeIf { p -> p.isNotEmpty() }) }
        }
        val sources = (secrets["searchSources"] as? List<*>).orEmpty().mapNotNull { e ->
            val m = e as? Map<*, *> ?: return@mapNotNull null
            SearchSource(str(m, "id") ?: return@mapNotNull null, str(m, "name") ?: "", str(m, "url") ?: return@mapNotNull null, str(m, "apiKey") ?: return@mapNotNull null)
        }
        val trakt = (secrets["trakt"] as? Map<*, *>)?.let { m ->
            str(m, "clientId")?.let {
                Trakt(it, str(m, "accessToken"), str(m, "refreshToken"), (m["expiresAt"] as? Number)?.toLong(), str(m, "username"))
            }
        }
        return Model(os, sources, trakt)
    }
}
