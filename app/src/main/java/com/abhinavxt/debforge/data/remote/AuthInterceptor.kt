package com.abhinavxt.debforge.data.remote

import com.abhinavxt.debforge.data.prefs.TokenStore
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Attaches "Authorization: Bearer <token>" to every request.
 *
 * The token lives in DataStore (a suspend/Flow source) but OkHttp interceptors
 * are synchronous, so we read the current value with runBlocking. This is safe
 * here because:
 *  - interceptors already run off the main thread (OkHttp dispatcher),
 *  - TokenStore.currentToken() reads a single cached value, not a long operation.
 *
 * If no token is set, the request proceeds without the header and RD will
 * return 401, which the repository surfaces as an auth error.
 */
class AuthInterceptor(
    private val tokenStore: TokenStore
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        // If the request already carries an Authorization header (e.g. the
        // pre-persist validation call below), leave it alone — adding a second
        // one would produce ambiguous semantics and some servers reject it.
        if (chain.request().header("Authorization") != null) {
            return chain.proceed(chain.request())
        }
        val token = runBlocking { tokenStore.currentToken() }
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
