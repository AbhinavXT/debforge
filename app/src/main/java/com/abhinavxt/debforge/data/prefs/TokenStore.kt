package com.abhinavxt.debforge.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.abhinavxt.debforge.data.provider.OAuthCredentials
import com.abhinavxt.debforge.domain.ProviderId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.tokenDataStore by preferencesDataStore(name = "debforge_token")

/**
 * Per-provider API tokens plus which provider is currently active.
 *
 * Tokens are kept for every provider the user has signed in to, so switching
 * services in Settings is instant and queued downloads from a non-active
 * provider can still refresh their links.
 *
 * Migration: app versions before multi-provider support stored a single
 * Real-Debrid token under "rd_token" with no active-provider key. Reads fall
 * back to that key, so existing users stay signed in to Real-Debrid after the
 * update; the legacy key is dropped the next time the RD token is written or
 * cleared.
 *
 * Encryption: every token and OAuth secret is stored encrypted with a
 * Keystore key (see [TokenCipher]). Older plaintext values are still read,
 * and [encryptLegacyValues] rewrites them encrypted at app start.
 */
@Singleton
class TokenStore @Inject constructor(
    private val context: Context
) {
    private val keyActive = stringPreferencesKey("active_provider")
    private val keyLegacyRd = stringPreferencesKey("rd_token")
    private fun keyFor(provider: ProviderId) = stringPreferencesKey("token_${provider.name.lowercase()}")

    private fun Preferences.tokenOf(provider: ProviderId): String? =
        (this[keyFor(provider)] ?: if (provider == ProviderId.REAL_DEBRID) this[keyLegacyRd] else null)
            ?.let(TokenCipher::decrypt)
            ?.takeIf { it.isNotBlank() }

    private fun enc(value: String): String = TokenCipher.encrypt(value)

    private fun Preferences.activeOf(): ProviderId? =
        ProviderId.fromName(this[keyActive])
            ?: if (this[keyLegacyRd] != null) ProviderId.REAL_DEBRID else null

    /** The provider the user chose (may not have a token yet). Null on first launch. */
    val activeProviderFlow: Flow<ProviderId?> = context.tokenDataStore.data
        .map { it.activeOf() }
        .distinctUntilChanged()

    /** Active provider IF it has a token, else null. Drives the auth gate. */
    val signedInProviderFlow: Flow<ProviderId?> = context.tokenDataStore.data
        .map { prefs -> prefs.activeOf()?.takeIf { prefs.tokenOf(it) != null } }
        .distinctUntilChanged()

    /** Providers that currently have a stored token. */
    val signedInSetFlow: Flow<Set<ProviderId>> = context.tokenDataStore.data
        .map { prefs -> ProviderId.entries.filter { prefs.tokenOf(it) != null }.toSet() }
        .distinctUntilChanged()

    /** One-shot read (used by the synchronous AuthInterceptor). */
    suspend fun token(provider: ProviderId): String? =
        context.tokenDataStore.data.first().tokenOf(provider)

    /** Stores [token] for [provider] and makes it the active provider. */
    suspend fun setToken(provider: ProviderId, token: String) {
        context.tokenDataStore.edit {
            it[keyFor(provider)] = enc(token.trim())
            it[keyActive] = provider.name
            if (provider == ProviderId.REAL_DEBRID) it.remove(keyLegacyRd)
        }
    }

    /** Replace a token WITHOUT changing the active provider (token refresh). */
    suspend fun updateToken(provider: ProviderId, token: String) {
        context.tokenDataStore.edit { it[keyFor(provider)] = enc(token.trim()) }
    }

    // --- OAuth refresh credentials (device-code sign-in) -------------------

    private fun oauthKeys(p: ProviderId): Triple<Preferences.Key<String>, Preferences.Key<String>, Preferences.Key<String>> {
        val base = "oauth_${p.name.lowercase()}"
        return Triple(
            stringPreferencesKey("${base}_client_id"),
            stringPreferencesKey("${base}_client_secret"),
            stringPreferencesKey("${base}_refresh")
        )
    }

    suspend fun oauth(provider: ProviderId): OAuthCredentials? {
        val prefs = context.tokenDataStore.data.first()
        val (id, secret, refresh) = oauthKeys(provider)
        return OAuthCredentials(
            clientId = prefs[id]?.let(TokenCipher::decrypt) ?: return null,
            clientSecret = prefs[secret]?.let(TokenCipher::decrypt) ?: return null,
            refreshToken = prefs[refresh]?.let(TokenCipher::decrypt) ?: return null
        )
    }

    /** Null clears them (e.g. switching to a pasted, non-expiring token). */
    suspend fun setOAuth(provider: ProviderId, creds: OAuthCredentials?) {
        val (id, secret, refresh) = oauthKeys(provider)
        context.tokenDataStore.edit {
            if (creds == null) {
                it.remove(id); it.remove(secret); it.remove(refresh)
            } else {
                it[id] = enc(creds.clientId)
                it[secret] = enc(creds.clientSecret)
                it[refresh] = enc(creds.refreshToken)
            }
        }
    }

    /**
     * One-time upgrade: rewrite any plaintext token / OAuth value (from
     * versions before encryption) in encrypted form. Safe to call on every
     * start; it only touches values that aren't encrypted yet.
     */
    suspend fun encryptLegacyValues() {
        context.tokenDataStore.edit { prefs ->
            // Snapshot first: writing while iterating the live map would throw.
            prefs.asMap().entries.toList().forEach { (key, value) ->
                val name = key.name
                val isSecret = name.startsWith("token_") || name.startsWith("oauth_") || name == keyLegacyRd.name
                if (isSecret && value is String && value.isNotEmpty() && !TokenCipher.isEncrypted(value)) {
                    @Suppress("UNCHECKED_CAST")
                    prefs[key as Preferences.Key<String>] = enc(value)
                }
            }
        }
    }

    suspend fun setActive(provider: ProviderId) {
        context.tokenDataStore.edit { it[keyActive] = provider.name }
    }

    suspend fun clearToken(provider: ProviderId) {
        val (oid, osecret, orefresh) = oauthKeys(provider)
        context.tokenDataStore.edit {
            it.remove(keyFor(provider))
            it.remove(oid); it.remove(osecret); it.remove(orefresh)
            if (provider == ProviderId.REAL_DEBRID) it.remove(keyLegacyRd)
            // Pin the active provider explicitly so clearing the legacy RD key
            // doesn't make activeOf() fall back to null and lose the choice.
            if (it[keyActive] == null) it[keyActive] = provider.name
        }
    }
}
