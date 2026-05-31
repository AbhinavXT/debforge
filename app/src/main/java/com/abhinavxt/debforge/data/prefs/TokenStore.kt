package com.abhinavxt.debforge.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.tokenDataStore by preferencesDataStore(name = "debforge_token")

/**
 * Stores the Real-Debrid API token. Plain DataStore (personal device; token is
 * always recoverable from https://real-debrid.com/apitoken).
 */
@Singleton
class TokenStore @Inject constructor(
    private val context: Context
) {
    private val keyToken = stringPreferencesKey("rd_token")

    /** Observable token; emits null when unset. */
    val tokenFlow: Flow<String?> = context.tokenDataStore.data
        .map { it[keyToken] }

    /** One-shot read of the current token (used by the sync AuthInterceptor). */
    suspend fun currentToken(): String? = tokenFlow.first()

    suspend fun setToken(token: String) {
        context.tokenDataStore.edit { it[keyToken] = token.trim() }
    }

    suspend fun clearToken() {
        context.tokenDataStore.edit { it.remove(keyToken) }
    }
}
