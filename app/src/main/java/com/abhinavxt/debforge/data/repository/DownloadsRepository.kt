package com.abhinavxt.debforge.data.repository

import com.abhinavxt.debforge.data.provider.AddResult
import com.abhinavxt.debforge.data.provider.ProviderCalls
import com.abhinavxt.debforge.data.provider.RemoteJob
import com.abhinavxt.debforge.data.provider.ProviderRegistry
import com.abhinavxt.debforge.data.provider.ResolvedLink
import com.abhinavxt.debforge.domain.AddKind
import com.abhinavxt.debforge.domain.AddRequest
import com.abhinavxt.debforge.domain.DataResult
import com.abhinavxt.debforge.domain.DownloadItem
import com.abhinavxt.debforge.domain.Page
import com.abhinavxt.debforge.domain.ProviderId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provider-agnostic access to remote files. Callers always say WHICH provider
 * — the browse screen passes the active one, the engine passes the one stored
 * on the queued row — so switching services never mixes data.
 */
@Singleton
class DownloadsRepository @Inject constructor(
    private val registry: ProviderRegistry
) {
    suspend fun getDownloadsPage(
        provider: ProviderId,
        page: Int,
        limit: Int = DEFAULT_PAGE_SIZE
    ): DataResult<Page<DownloadItem>> {
        val p = registry[provider]
        return ProviderCalls.run(p.info, "loading downloads") { p.listFiles(page, limit) }
    }

    /**
     * Mints a fresh direct URL for a queued file. Used by the engine before
     * the first byte (when the listing had no URL) and when a stored URL died.
     */
    suspend fun resolveLink(provider: ProviderId, sourceRef: String): DataResult<ResolvedLink> {
        val p = registry[provider]
        return ProviderCalls.run(p.info, "getting a download link") { p.resolveLink(sourceRef) }
    }

    /** One archive with every file of a torrent/job, if the service can do that. */
    fun zipBundle(provider: ProviderId, parentRef: String, parentName: String, fileCount: Int): DownloadItem? =
        registry[provider].zipBundle(parentRef, parentName, fileCount)

    fun addCapabilities(provider: ProviderId): Set<AddKind> = registry[provider].addCapabilities

    /** Sends a magnet / .torrent / link to [provider]. Success carries a short confirmation. */
    suspend fun add(provider: ProviderId, request: AddRequest): DataResult<AddResult> {
        val p = registry[provider]
        return ProviderCalls.run(p.info, "adding ${request.kind.label}") { p.add(request) }
    }

    suspend fun listProcessing(provider: ProviderId): DataResult<List<RemoteJob>> {
        val p = registry[provider]
        return ProviderCalls.run(p.info, "checking active jobs") { p.listProcessing() }
    }

    /** Success(null) = still processing. */
    suspend fun filesForJob(provider: ProviderId, jobRef: String): DataResult<List<DownloadItem>?> {
        val p = registry[provider]
        return ProviderCalls.run(p.info, "checking a job") { p.filesForJob(jobRef) }
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 50
    }
}
