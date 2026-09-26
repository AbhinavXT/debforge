package com.abhinavxt.debforge.data.remote

import com.abhinavxt.debforge.data.prefs.TokenStore
import com.abhinavxt.debforge.domain.ProviderId
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Attaches "Authorization: Bearer <token>" using the token stored for ONE
 * provider. Each provider's Retrofit instance gets its own interceptor, so a
 * TorBox call can never carry a Real-Debrid token and vice versa.
 *
 * The token lives in DataStore (a suspend/Flow source) but OkHttp interceptors
 * are synchronous, so we read the current value with runBlocking. This is safe
 * here because interceptors already run off the main thread (OkHttp
 * dispatcher) and the read is a single cached DataStore value.
 *
 * If no token is set, the request proceeds without the header and the service
 * returns 401, which the repository surfaces as an auth error.
 */
class AuthInterceptor(
    private val tokenStore: TokenStore,
    private val provider: ProviderId
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        // A pre-set Authorization header (token validation during sign-in)
        // wins — adding a second one would be ambiguous.
        if (chain.request().header("Authorization") != null) {
            return chain.proceed(chain.request())
        }
        val token = runBlocking { tokenStore.token(provider) }
        val request = if (!token.isNullOrBlank()) {
            chain.request().newBuilder()
                .addHeader("Authorization", "Bearer $token")
                .build()
        } else {
            chain.request()
        }
        return chain.proceed(request)
    }
}
