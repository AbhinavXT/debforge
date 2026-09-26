package com.abhinavxt.debforge.di

import com.abhinavxt.debforge.data.prefs.TokenStore
import com.abhinavxt.debforge.data.provider.DebridProvider
import com.abhinavxt.debforge.data.provider.alldebrid.AllDebridApi
import com.abhinavxt.debforge.data.provider.alldebrid.AllDebridProvider
import com.abhinavxt.debforge.data.provider.debridlink.DebridLinkApi
import com.abhinavxt.debforge.data.provider.debridlink.DebridLinkProvider
import com.abhinavxt.debforge.data.provider.premiumize.PremiumizeApi
import com.abhinavxt.debforge.data.provider.premiumize.PremiumizeProvider
import com.abhinavxt.debforge.data.remote.QueryAuthInterceptor
import okhttp3.Interceptor
import com.abhinavxt.debforge.data.provider.realdebrid.RealDebridApi
import com.abhinavxt.debforge.data.provider.realdebrid.RealDebridAuthenticator
import com.abhinavxt.debforge.data.provider.realdebrid.RealDebridOAuthApi
import com.abhinavxt.debforge.data.provider.realdebrid.RealDebridProvider
import com.abhinavxt.debforge.data.provider.torbox.TorBoxApi
import com.abhinavxt.debforge.data.provider.torbox.TorBoxProvider
import com.abhinavxt.debforge.data.remote.AuthInterceptor
import com.abhinavxt.debforge.domain.ProviderId
import com.squareup.moshi.Moshi
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import javax.inject.Singleton

/**
 * Wiring for every debrid provider. To add a new service:
 *   1. add an entry to [ProviderId],
 *   2. write `XyzApi` + `XyzProvider : DebridProvider` under data/provider/xyz,
 *   3. add a `provideXyzApi` below and a `@Binds @IntoSet` line in [ProviderBindings].
 * The registry, Setup picker, Settings switcher and download engine pick it
 * up automatically.
 */
@Module
@InstallIn(SingletonComponent::class)
object ProviderModule {

    @Provides
    @Singleton
    fun provideRealDebridApi(
        @ApiHttpClient base: OkHttpClient,
        moshi: Moshi,
        tokenStore: TokenStore,
        oauthApi: RealDebridOAuthApi
    ): RealDebridApi = retrofitFor(
        // Renews device-login tokens on 401 (no-op for pasted API tokens).
        base.newBuilder().authenticator(RealDebridAuthenticator(tokenStore, oauthApi)).build(),
        moshi, tokenStore, ProviderId.REAL_DEBRID, RealDebridApi.BASE_URL
    ).create(RealDebridApi::class.java)

    /** OAuth endpoints authenticate by their parameters: plain client, no auth. */
    @Provides
    @Singleton
    fun provideRealDebridOAuthApi(
        @ApiHttpClient base: OkHttpClient,
        moshi: Moshi
    ): RealDebridOAuthApi = Retrofit.Builder()
        .baseUrl(RealDebridOAuthApi.BASE_URL)
        .client(base)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(RealDebridOAuthApi::class.java)

    @Provides
    @Singleton
    fun provideTorBoxApi(
        @ApiHttpClient base: OkHttpClient,
        moshi: Moshi,
        tokenStore: TokenStore
    ): TorBoxApi = retrofitFor(base, moshi, tokenStore, ProviderId.TORBOX, TorBoxApi.BASE_URL)
        .create(TorBoxApi::class.java)

    @Provides
    @Singleton
    fun provideAllDebridApi(
        @ApiHttpClient base: OkHttpClient,
        moshi: Moshi,
        tokenStore: TokenStore
    ): AllDebridApi = retrofitFor(base, moshi, tokenStore, ProviderId.ALL_DEBRID, AllDebridApi.BASE_URL)
        .create(AllDebridApi::class.java)

    @Provides
    @Singleton
    fun providePremiumizeApi(
        @ApiHttpClient base: OkHttpClient,
        moshi: Moshi,
        tokenStore: TokenStore
    ): PremiumizeApi = retrofitFor(
        base, moshi, tokenStore, ProviderId.PREMIUMIZE, PremiumizeApi.BASE_URL,
        // Premiumize takes the key as ?apikey=, not a Bearer header.
        auth = QueryAuthInterceptor(tokenStore, ProviderId.PREMIUMIZE, "apikey")
    ).create(PremiumizeApi::class.java)

    @Provides
    @Singleton
    fun provideDebridLinkApi(
        @ApiHttpClient base: OkHttpClient,
        moshi: Moshi,
        tokenStore: TokenStore
    ): DebridLinkApi = retrofitFor(base, moshi, tokenStore, ProviderId.DEBRID_LINK, DebridLinkApi.BASE_URL)
        .create(DebridLinkApi::class.java)

    private fun retrofitFor(
        base: OkHttpClient,
        moshi: Moshi,
        tokenStore: TokenStore,
        provider: ProviderId,
        baseUrl: String,
        auth: Interceptor = AuthInterceptor(tokenStore, provider)
    ): Retrofit {
        // Auth goes FIRST so the logging interceptor (already on `base`) sees
        // the final request. BASIC level never prints headers.
        val client = base.newBuilder()
            .apply { interceptors().add(0, auth) }
            .build()
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
    }

}

/** Multibinding: every provider lands in the Set<DebridProvider> the registry consumes. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ProviderBindings {
    @Binds @IntoSet
    abstract fun realDebrid(impl: RealDebridProvider): DebridProvider

    @Binds @IntoSet
    abstract fun torBox(impl: TorBoxProvider): DebridProvider

    @Binds @IntoSet
    abstract fun allDebrid(impl: AllDebridProvider): DebridProvider

    @Binds @IntoSet
    abstract fun premiumize(impl: PremiumizeProvider): DebridProvider

    @Binds @IntoSet
    abstract fun debridLink(impl: DebridLinkProvider): DebridProvider
}
