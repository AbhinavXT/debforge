package com.abhinavxt.debforge.data.remote

import com.abhinavxt.debforge.data.prefs.TokenStore
import com.abhinavxt.debforge.domain.ProviderId
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

/**
 * For services that take the API key as a query parameter (Premiumize:
 * `?apikey=`). The debug logger redacts `apikey=` values. A request that
 * already carries the parameter (sign-in validation) is left alone.
 */
class QueryAuthInterceptor(
    private val tokenStore: TokenStore,
    private val provider: ProviderId,
    private val param: String
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        if (req.url.queryParameter(param) != null) return chain.proceed(req)
        val token = runBlocking { tokenStore.token(provider) }
        if (token.isNullOrBlank()) return chain.proceed(req)
        val url = req.url.newBuilder().addQueryParameter(param, token).build()
        return chain.proceed(req.newBuilder().url(url).build())
    }
}
