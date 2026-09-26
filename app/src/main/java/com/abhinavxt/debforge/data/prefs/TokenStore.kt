package com.abhinavxt.debforge.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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
            ?.takeIf { it.isNotBlank() }

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
            it[keyFor(provider)] = token.trim()
            it[keyActive] = provider.name
            if (provider == ProviderId.REAL_DEBRID) it.remove(keyLegacyRd)
        }
    }

    suspend fun setActive(provider: ProviderId) {
        context.tokenDataStore.edit { it[keyActive] = provider.name }
    }

    suspend fun clearToken(provider: ProviderId) {
        context.tokenDataStore.edit {
            it.remove(keyFor(provider))
            if (provider == ProviderId.REAL_DEBRID) it.remove(keyLegacyRd)
            // Pin the active provider explicitly so clearing the legacy RD key
            // doesn't make activeOf() fall back to null and lose the choice.
            if (it[keyActive] == null) it[keyActive] = provider.name
        }
    }
}
