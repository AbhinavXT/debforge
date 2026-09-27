package com.abhinavxt.debforge.data.repository

import com.abhinavxt.debforge.data.prefs.TokenStore
import com.abhinavxt.debforge.data.provider.AccountInfo
import com.abhinavxt.debforge.data.provider.DeviceCode
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
        if (result is DataResult.Success) {
            tokenStore.setToken(provider, trimmed)
            // A pasted token doesn't expire; drop any old device-login refresh creds.
            tokenStore.setOAuth(provider, null)
        }
        return result
    }

    suspend fun account(provider: ProviderId): DataResult<AccountInfo> {
        val p = registry[provider]
        return ProviderCalls.run(p.info, "loading account") { p.account() }
    }

    fun supportsDeviceLogin(provider: ProviderId): Boolean = registry[provider].deviceLogin != null

    /** Step 1 of "Sign in with <service>": get a code to show the user. */
    suspend fun startDeviceLogin(provider: ProviderId): DataResult<DeviceCode> {
        val p = registry[provider]
        val login = p.deviceLogin ?: return DataResult.Error("${p.info.displayName} doesn't support this sign-in")
        return ProviderCalls.run(p.info, "starting sign-in") { login.start() }
    }

    /**
     * Step 2, called every [DeviceCode.intervalSeconds]. Success(null) = not
     * approved yet. On approval the token (and refresh credentials) are
     * validated, stored, and the provider becomes active.
     */
    suspend fun pollDeviceLogin(provider: ProviderId, code: DeviceCode): DataResult<AccountInfo?> {
        val p = registry[provider]
        val login = p.deviceLogin ?: return DataResult.Error("${p.info.displayName} doesn't support this sign-in")
        return ProviderCalls.run(p.info, "signing in") {
            val grant = login.poll(code) ?: return@run null
            val account = p.validateToken(grant.accessToken)
            // Refresh creds first: setToken flips the app to Home, which
            // immediately starts making (possibly refreshing) calls.
            tokenStore.setOAuth(provider, grant.refresh)
            tokenStore.setToken(provider, grant.accessToken)
            account
        }
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
