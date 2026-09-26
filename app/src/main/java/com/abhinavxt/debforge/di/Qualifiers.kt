package com.abhinavxt.debforge.di

import javax.inject.Qualifier

/**
 * Marks the OkHttpClient used for downloading file bytes. It must NOT carry any
 * provider auth interceptor: download URLs point at CDN/hoster domains, and
 * attaching "Authorization: Bearer <token>" would leak the token to third-party
 * hosts. This client also uses streaming-friendly timeouts.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DownloadHttpClient

/**
 * Base client for provider API calls (no auth — each provider adds its own
 * AuthInterceptor on a derived client). See ProviderModule.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApiHttpClient
