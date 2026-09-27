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
 *      "secrets":{"kdf":"PBKDF2WithHmacSHA256","iterations":310000,
 *                 "salt":"…","iv":"…","data":"…"}}      <- only if opted in
 *
 * Sign-ins (service tokens, refresh credentials) and the TMDB key are written
 * ONLY inside "secrets", encrypted with a password the user picks
 * ([BackupCrypto]); never in plain text.
 */
@Singleton
class BackupManager @Inject constructor(
    private val settings: SettingsStore,
    private val tokens: TokenStore,
    private val follows: com.abhinavxt.debforge.data.follow.FollowStore,
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
    data class Preview(val createdAt: String?, val settingsCount: Int, val hasSecrets: Boolean)

    fun preview(json: String): Preview {
        val root = parse(json)
        return Preview(
            createdAt = root["created"] as? String,
            settingsCount = (root["settings"] as? Map<*, *>)?.size ?: 0,
            hasSecrets = root["secrets"] is Map<*, *>
        )
    }

    data class Result(
        val settingsApplied: Int,
        val services: List<ProviderId>,
        val skippedFolder: Boolean,
        /** Shows newly followed from the file (already-followed ones are merged, not counted). */
        val followsAdded: Int = 0,
        /** Anything followed after the restore: the episode check must be scheduled. */
        val anyFollows: Boolean = false
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
        }
        return Result(outcome.applied, services, outcome.skippedFolder, followsAdded, follows.all().isNotEmpty())
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
