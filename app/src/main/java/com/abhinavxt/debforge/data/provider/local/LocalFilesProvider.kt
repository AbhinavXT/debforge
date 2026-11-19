package com.abhinavxt.debforge.data.provider.local

import com.abhinavxt.debforge.data.provider.AccountInfo
import com.abhinavxt.debforge.data.provider.DebridProvider
import com.abhinavxt.debforge.data.provider.ProviderException
import com.abhinavxt.debforge.data.provider.ProviderInfo
import com.abhinavxt.debforge.data.provider.ResolvedLink
import com.abhinavxt.debforge.domain.DownloadItem
import com.abhinavxt.debforge.domain.Page
import com.abhinavxt.debforge.domain.ProviderId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owner of the files imported from the download folder (ProviderId.LOCAL).
 * There is no service behind it: every call fails with a plain message, which
 * ProviderCalls turns into an error result instead of a crash. Not bound into
 * the provider set, so it never shows up as a service to sign in to.
 */
@Singleton
class LocalFilesProvider @Inject constructor() : DebridProvider {

    override val info = ProviderInfo(
        id = ProviderId.LOCAL,
        displayName = "This device",
        tokenUrl = "",
        tokenUrlLabel = "",
        tokenLabel = "",
        emptyListHint = "",
        websiteUrl = ""
    )

    override suspend fun validateToken(token: String): AccountInfo = throw unsupported()

    override suspend fun account(): AccountInfo = throw unsupported()

    override suspend fun listFiles(page: Int, pageSize: Int): Page<DownloadItem> = Page(emptyList(), false)

    override suspend fun resolveLink(sourceRef: String): ResolvedLink = throw unsupported()

    private fun unsupported() = ProviderException("This file is stored on this device")
}
