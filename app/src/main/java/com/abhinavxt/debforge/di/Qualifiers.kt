package com.abhinavxt.debforge.di

import javax.inject.Qualifier

/**
 * Marks the OkHttpClient used for downloading file bytes. It must NOT carry the
 * Real-Debrid auth interceptor: download URLs point at CDN/hoster domains, and
 * attaching "Authorization: Bearer <token>" would leak the token to third-party
 * hosts. This client also uses streaming-friendly timeouts.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DownloadHttpClient
