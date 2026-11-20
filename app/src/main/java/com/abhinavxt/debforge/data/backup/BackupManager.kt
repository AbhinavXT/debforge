package com.abhinavxt.debforge.data.backup

import com.abhinavxt.debforge.BuildConfig
import com.abhinavxt.debforge.data.prefs.SettingsStore
import com.abhinavxt.debforge.data.prefs.TokenStore
import com.abhinavxt.debforge.data.provider.OAuthCredentials
import com.abhinavxt.debforge.domain.ProviderId
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Settings backup as a small JSON file the user saves wherever they like.
 *
 *     {"app":"DebForge","format":1,"created":"…","version":"1.1.2",
 *      "settings":{"wifi_only":true,…},
 *      "follows":[{"provider":"TORBOX","showKey":"show:the bear",…}],
 *      "audioLists":{…},"history":{…},"searches":{…},   <- optional sections, each "v":1
 *      "secrets":{"kdf":"PBKDF2WithHmacSHA256","iterations":310000,
 *                 "salt":"…","iv":"…","data":"…"}}      <- only if opted in
 *
 * Every credential (service sign-ins, TMDB key, OpenSubtitles key and account,
 * search source keys, Trakt Client ID and tokens) is written
 * ONLY inside "secrets", encrypted with a password the user picks
 * ([BackupCrypto]); never in plain text.
 */
@Singleton
class BackupManager @Inject constructor(
    private val settings: SettingsStore,
    private val tokens: TokenStore,
    private val follows: com.abhinavxt.debforge.data.follow.FollowStore,
    private val audioLists: com.abhinavxt.debforge.data.playback.AudioLists,
    private val history: com.abhinavxt.debforge.data.history.PlayHistory,
    private val searchStore: com.abhinavxt.debforge.data.search.SearchHistoryStore,
    private val youtubeSearchStore: com.abhinavxt.debforge.data.search.YouTubeSearchHistoryStore,
    private val subscriptions: com.abhinavxt.debforge.data.subscriptions.SubscriptionDao,
    private val openSubtitles: com.abhinavxt.debforge.data.subtitles.OpenSubtitlesRepository,
    private val searchSources: com.abhinavxt.debforge.data.search.SearchSourceStore,
    private val trakt: com.abhinavxt.debforge.data.trakt.TraktStore,
    moshi: Moshi
) {
    private val mapAdapter: JsonAdapter<Map<String, Any?>> = moshi.adapter<Map<String, Any?>>(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    )

    /** Builds the backup file contents. [password] non-null = include sign-ins. */
    suspend fun export(password: CharArray?): String {
        val root = linkedMapOf<String, Any?>(
            "app" to APP,
            "format" to FORMAT,
            "created" to Instant.now().toString(),
            "version" to BuildConfig.VERSION_NAME,
            "settings" to settings.backupValues(),
            // Followed shows. Not secret; older versions ignore the key.
            "follows" to com.abhinavxt.debforge.data.follow.FollowBackup.toJson(follows.all())
        )
        // Optional sections (each "v":1); older versions ignore them. A failing one is left out.
        runCatching { AudioListsBackup.toJson(audioLists.snapshot()) }.getOrNull()?.let { root["audioLists"] = it }
        runCatching { HistoryBackup.toJson(history.all()) }.getOrNull()?.let { root["history"] = it }
        runCatching {
            SearchesBackup.toJson(SearchesBackup.Model(searchStore.all(), youtubeSearchStore.all()))
        }.getOrNull()?.let { root["searches"] = it }
        runCatching { SubscriptionsBackup.toJson(subscriptions.all()) }.getOrNull()?.let { root["subscriptions"] = it }
        if (password != null) {
            val secrets = linkedMapOf<String, Any?>(
                "active" to tokens.activeProvider()?.name,
                "signIns" to tokens.exportSignIns().map { (p, s) ->
                    p.name to linkedMapOf<String, Any?>(
                        "token" to s.token,
                        "clientId" to s.oauth?.clientId,
                        "clientSecret" to s.oauth?.clientSecret,
                        "refreshToken" to s.oauth?.refreshToken
                    )
                }.toMap(),
                "tmdbKey" to settings.tmdbKeyForBackup()
            )
            // Each extra part on its own, so a failing store cannot lose the rest. Older versions ignore these keys.
            SecretsBackup.addTo(
                secrets,
                SecretsBackup.Model(
                    openSubtitles = runCatching { openSubtitles.backupValues() }.getOrNull(),
                    searchSources = runCatching { searchSources.all() }.getOrDefault(emptyList()),
                    trakt = runCatching { trakt.backupValues() }.getOrNull()
                )
            )
            val sealed = BackupCrypto.seal(mapAdapter.toJson(secrets).toByteArray(), password)
            root["secrets"] = linkedMapOf(
                "kdf" to BackupCrypto.KDF,
                "iterations" to sealed.iterations,
                "salt" to sealed.salt,
                "iv" to sealed.iv,
                "data" to sealed.data
            )
        }
        return mapAdapter.indent("  ").toJson(root)
    }

    /** What a file holds, read before restoring (to know whether to ask for a password). */
    data class Preview(
        val createdAt: String?,
        val settingsCount: Int,
        val hasSecrets: Boolean,
        val hasLists: Boolean = false,
        val hasHistory: Boolean = false,
        val hasSearches: Boolean = false,
        val hasSubscriptions: Boolean = false
    )

    fun preview(json: String): Preview {
        val root = parse(json)
        return Preview(
            createdAt = root["created"] as? String,
            settingsCount = (root["settings"] as? Map<*, *>)?.size ?: 0,
            hasSecrets = root["secrets"] is Map<*, *>,
            hasLists = root["audioLists"] is Map<*, *>,
            hasHistory = root["history"] is Map<*, *>,
            hasSearches = root["searches"] is Map<*, *>,
            hasSubscriptions = root["subscriptions"] is Map<*, *>
        )
    }

    data class Result(
        val settingsApplied: Int,
        val services: List<ProviderId>,
        val skippedFolder: Boolean,
        /** Shows newly followed from the file (already-followed ones are merged, not counted). */
        val followsAdded: Int = 0,
        /** Anything followed after the restore: the episode check must be scheduled. */
        val anyFollows: Boolean = false,
        val favouritesAdded: Int = 0,
        val playlistsAdded: Int = 0,
        val historyAdded: Int = 0,
        val searchesAdded: Int = 0,
        /** Channels and podcasts newly followed from the file. */
        val subscriptionsAdded: Int = 0
    )

    /**
     * Restores [json]. With a [password], the sign-ins are restored too
     * (throws [BackupCrypto.WrongPasswordException] if it's wrong: nothing is
     * changed in that case, because secrets are decrypted before anything is written).
     */
    suspend fun restore(json: String, password: CharArray?): Result {
        val root = parse(json)
        val secrets = root["secrets"] as? Map<*, *>
        val opened: Map<String, Any?>? = if (secrets != null && password != null) {
            val sealed = BackupCrypto.Sealed(
                salt = secrets["salt"] as? String ?: throw BackupFormatException(),
                iv = secrets["iv"] as? String ?: throw BackupFormatException(),
                iterations = (secrets["iterations"] as? Number)?.toInt() ?: BackupCrypto.ITERATIONS,
                data = secrets["data"] as? String ?: throw BackupFormatException()
            )
            mapAdapter.fromJson(String(BackupCrypto.open(sealed, password))) ?: throw BackupFormatException()
        } else null

        @Suppress("UNCHECKED_CAST")
        val outcome = settings.restoreValues((root["settings"] as? Map<String, Any?>).orEmpty())
        val followsAdded = follows.mergeFromBackup(com.abhinavxt.debforge.data.follow.FollowBackup.fromJson(root["follows"]))

        // Each section on its own, so a broken one cannot stop the others. Merge only, never delete.
        val lists = runCatching { audioLists.mergeFromBackup(AudioListsBackup.fromJson(root["audioLists"])) }.getOrNull()
        val historyAdded = runCatching { history.mergeFromBackup(HistoryBackup.fromJson(root["history"])) }.getOrDefault(0)
        val searches = SearchesBackup.fromJson(root["searches"])
        val searchesAdded = runCatching { searchStore.mergeFromBackup(searches.search) }.getOrDefault(0) +
            runCatching { youtubeSearchStore.mergeFromBackup(searches.youtube) }.getOrDefault(0)
        val subscriptionsAdded = runCatching {
            val existing = subscriptions.all().mapTo(HashSet()) { it.url }
            val fresh = SubscriptionsBackup.toInsert(SubscriptionsBackup.fromJson(root["subscriptions"]), existing, System.currentTimeMillis())
            fresh.count { subscriptions.insert(it) > 0 }
        }.getOrDefault(0)

        var services = emptyList<ProviderId>()
        if (opened != null) {
            val signIns = (opened["signIns"] as? Map<*, *>).orEmpty().mapNotNull { (name, v) ->
                val p = ProviderId.fromName(name as? String) ?: return@mapNotNull null
                val m = v as? Map<*, *> ?: return@mapNotNull null
                val token = (m["token"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val id = m["clientId"] as? String
                val secret = m["clientSecret"] as? String
                val refresh = m["refreshToken"] as? String
                val oauth = if (id != null && secret != null && refresh != null) OAuthCredentials(id, secret, refresh) else null
                p to TokenStore.SignIn(token, oauth)
            }.toMap()
            tokens.importSignIns(signIns, ProviderId.fromName(opened["active"] as? String))
            (opened["tmdbKey"] as? String)?.takeIf { it.isNotBlank() }?.let { settings.setTmdbKey(it) }
            services = signIns.keys.toList()
            // Extras: only present values are written; a missing or empty one never wipes a local value.
            val extra = SecretsBackup.fromMap(opened)
            extra.openSubtitles?.let { b ->
                runCatching {
                    SecretsBackup.mergeOpenSubtitles(openSubtitles.backupValues(), b)
                        ?.let { openSubtitles.save(it.apiKey, it.username.orEmpty(), it.password.orEmpty()) }
                }
            }
            if (extra.searchSources.isNotEmpty()) runCatching {
                searchSources.replaceAll(SecretsBackup.mergeSearchSources(searchSources.all(), extra.searchSources))
            }
            extra.trakt?.let { runCatching { trakt.restoreFromBackup(it) } }
        }
        return Result(
            outcome.applied, services, outcome.skippedFolder, followsAdded, follows.all().isNotEmpty(),
            lists?.favouritesAdded ?: 0, lists?.playlistsAdded ?: 0, historyAdded, searchesAdded, subscriptionsAdded
        )
    }

    private fun parse(json: String): Map<String, Any?> {
        val root = runCatching { mapAdapter.fromJson(json) }.getOrNull() ?: throw BackupFormatException()
        if (root["app"] != APP) throw BackupFormatException()
        val format = (root["format"] as? Number)?.toInt() ?: throw BackupFormatException()
        if (format > FORMAT) throw BackupFormatException(newer = true)
        return root
    }

    class BackupFormatException(val newer: Boolean = false) :
        Exception(if (newer) "Backup made by a newer DebForge" else "Not a DebForge backup file")

    private companion object {
        const val APP = "DebForge"
        const val FORMAT = 1
    }
}
