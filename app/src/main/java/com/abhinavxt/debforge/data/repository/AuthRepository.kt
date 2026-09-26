package com.abhinavxt.debforge.data.repository

import com.abhinavxt.debforge.data.prefs.TokenStore
import com.abhinavxt.debforge.data.provider.AccountInfo
import com.abhinavxt.debforge.data.provider.ProviderCalls
import com.abhinavxt.debforge.data.provider.ProviderInfo
import com.abhinavxt.debforge.data.provider.ProviderRegistry
import com.abhinavxt.debforge.domain.DataResult
import com.abhinavxt.debforge.domain.ProviderId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val registry: ProviderRegistry,
    private val tokenStore: TokenStore
) {
    /** Every provider the app supports, in display order. */
    val providers: List<ProviderInfo> get() = registry.all.map { it.info }

    fun info(id: ProviderId): ProviderInfo = registry[id].info

    /** Provider the user picked (may be signed out). */
    val activeProvider: Flow<ProviderId?> = tokenStore.activeProviderFlow

    /** Active provider if it has a token; null means "show Setup". */
    val signedInProvider: Flow<ProviderId?> = tokenStore.signedInProviderFlow

    val hasToken: Flow<Boolean> = signedInProvider.map { it != null }

    val signedInProviders: Flow<Set<ProviderId>> = tokenStore.signedInSetFlow

    /**
     * Validates a candidate token WITHOUT persisting it, then stores it and
     * makes [provider] active. Persisting only after the service confirms
     * avoids the auth gate briefly flipping to Home on a bad token.
     */
    suspend fun saveAndValidate(provider: ProviderId, token: String): DataResult<AccountInfo> {
        val p = registry[provider]
        val trimmed = token.trim()
        val result = ProviderCalls.run(p.info, "sign-in") { p.validateToken(trimmed) }
        if (result is DataResult.Success) tokenStore.setToken(provider, trimmed)
        return result
    }

    /**
     * Switch services. If the target has no stored token the auth gate falls
     * back to Setup with that provider preselected.
     */
    suspend fun setActiveProvider(provider: ProviderId) = tokenStore.setActive(provider)

    /** Signs out of the ACTIVE provider only; other providers keep their tokens. */
    suspend fun signOut() {
        val active = tokenStore.activeProviderFlow.first() ?: return
        tokenStore.clearToken(active)
    }
}
