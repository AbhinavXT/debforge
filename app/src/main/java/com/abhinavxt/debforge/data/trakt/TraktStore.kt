package com.abhinavxt.debforge.data.trakt

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.abhinavxt.debforge.data.prefs.TokenCipher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.traktDataStore by preferencesDataStore(name = "debforge_trakt")

/**
 * Trakt sign-in and sync state. The app's Client ID (the user's own Trakt
 * app, like the TMDB key) and the tokens are encrypted with the
 * Keystore key ([TokenCipher]); this file is not part of backups.
 */
@Singleton
class TraktStore @Inject constructor(private val context: Context) {

    private val keyClientId = stringPreferencesKey("client_id")
    /** Only used to delete it: older versions saved the now-unused secret. */
    private val keyClientSecret = stringPreferencesKey("client_secret")
    private val keyAccess = stringPreferencesKey("access_token")
    private val keyRefresh = stringPreferencesKey("refresh_token")
    private val keyExpiresAt = longPreferencesKey("expires_at")
    private val keyUser = stringPreferencesKey("username")
    private val keyScrobble = booleanPreferencesKey("scrobble")
    private val keySyncWatched = booleanPreferencesKey("sync_watched")
    private val keyWatched = stringSetPreferencesKey("watched_keys")
    private val keyLastActivity = stringPreferencesKey("last_activity")
    private val keyLastSync = longPreferencesKey("last_sync_at")

    data class Credentials(val clientId: String)

    data class Session(
        val credentials: Credentials,
        val accessToken: String,
        val refreshToken: String?,
        val expiresAt: Long
    )

    private fun Preferences.secret(key: Preferences.Key<String>): String? =
        this[key]?.let(TokenCipher::decrypt)?.takeIf { it.isNotBlank() }

    private fun Preferences.credentials(): Credentials? = secret(keyClientId)?.let(::Credentials)

    private fun Preferences.session(): Session? {
        val creds = credentials() ?: return null
        val access = secret(keyAccess) ?: return null
        return Session(creds, access, secret(keyRefresh), this[keyExpiresAt] ?: 0L)
    }

    val credentialsFlow: Flow<Credentials?> = context.traktDataStore.data.map { it.credentials() }.distinctUntilChanged()

    /** Trakt username once signed in, else null. */
    val userFlow: Flow<String?> = context.traktDataStore.data
        .map { p -> p.session()?.let { p[keyUser] ?: "" } }
        .distinctUntilChanged()

    /** Send what's playing in DebForge's player to Trakt. On by default. */
    val scrobbleFlow: Flow<Boolean> = context.traktDataStore.data.map { it[keyScrobble] ?: true }

    /** Mark episodes and movies watched on Trakt as ✓ Watched in the Library. On by default. */
    val syncWatchedFlow: Flow<Boolean> = context.traktDataStore.data.map { it[keySyncWatched] ?: true }

    /** [com.abhinavxt.debforge.domain.TraktMatch] keys of everything watched on Trakt. */
    val watchedFlow: Flow<Set<String>> = context.traktDataStore.data
        .map { p -> if (p.session() != null && (p[keySyncWatched] ?: true)) p[keyWatched].orEmpty() else emptySet() }
        .distinctUntilChanged()

    val lastSyncFlow: Flow<Long> = context.traktDataStore.data.map { it[keyLastSync] ?: 0L }

    suspend fun credentials(): Credentials? = context.traktDataStore.data.first().credentials()
    suspend fun session(): Session? = context.traktDataStore.data.first().session()
    suspend fun scrobbleEnabled(): Boolean = scrobbleFlow.first()
    suspend fun syncWatchedEnabled(): Boolean = syncWatchedFlow.first()
    suspend fun lastActivity(): String? = context.traktDataStore.data.first()[keyLastActivity]
    suspend fun lastSync(): Long = lastSyncFlow.first()

    suspend fun setCredentials(clientId: String) {
        context.traktDataStore.edit {
            it[keyClientId] = TokenCipher.encrypt(clientId.trim())
            it.remove(keyClientSecret)
        }
    }

    suspend fun setToken(token: TraktToken, now: Long = System.currentTimeMillis()) {
        // Trakt reports created_at in seconds; fall back to now if it's missing.
        val created = token.createdAt?.times(1000) ?: now
        val expiresAt = created + (token.expiresIn ?: DEFAULT_LIFETIME_S) * 1000
        context.traktDataStore.edit {
            it[keyAccess] = TokenCipher.encrypt(token.accessToken)
            if (token.refreshToken != null) it[keyRefresh] = TokenCipher.encrypt(token.refreshToken)
            it[keyExpiresAt] = expiresAt
        }
    }

    suspend fun setUser(username: String) = context.traktDataStore.edit { it[keyUser] = username }
    suspend fun setScrobble(v: Boolean) = context.traktDataStore.edit { it[keyScrobble] = v }
    suspend fun setSyncWatched(v: Boolean) = context.traktDataStore.edit { it[keySyncWatched] = v }

    suspend fun setWatched(keys: Set<String>, lastActivity: String?, at: Long) = context.traktDataStore.edit {
        it[keyWatched] = keys
        if (lastActivity != null) it[keyLastActivity] = lastActivity else it.remove(keyLastActivity)
        it[keyLastSync] = at
    }

    /** Marks the sync fresh without changing what's watched (nothing new on Trakt). */
    suspend fun touchSync(at: Long) = context.traktDataStore.edit { it[keyLastSync] = at }

    /** Adds one key right away (a scrobble just finished) so the Library shows it before the next sync. */
    suspend fun addWatched(key: String) = context.traktDataStore.edit {
        it[keyWatched] = it[keyWatched].orEmpty() + key
    }

    suspend fun removeWatched(keys: Collection<String>) = context.traktDataStore.edit {
        it[keyWatched] = it[keyWatched].orEmpty() - keys.toSet()
    }

    /** Signs out but keeps the Client ID, so signing in again is one tap. */
    suspend fun signOut() = context.traktDataStore.edit {
        it.remove(keyAccess); it.remove(keyRefresh); it.remove(keyExpiresAt); it.remove(keyUser)
        it.remove(keyWatched); it.remove(keyLastActivity); it.remove(keyLastSync)
    }

    /** Forgets everything, including the Client ID. */
    suspend fun clearAll() = context.traktDataStore.edit { it.clear() }

    private companion object {
        /** Trakt's documented lifetime when the response doesn't say (24 h). */
        const val DEFAULT_LIFETIME_S = 24L * 60 * 60
    }
}
