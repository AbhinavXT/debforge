package com.abhinavxt.debforge.data.provider.realdebrid

import com.abhinavxt.debforge.data.provider.AccountInfo
import com.abhinavxt.debforge.data.provider.DebridProvider
import com.abhinavxt.debforge.data.provider.ProviderInfo
import com.abhinavxt.debforge.data.provider.ResolvedLink
import com.abhinavxt.debforge.data.provider.realdebrid.dto.RdDownloadDto
import com.abhinavxt.debforge.domain.AddKind
import com.abhinavxt.debforge.domain.AddRequest
import com.abhinavxt.debforge.domain.DownloadItem
import com.abhinavxt.debforge.domain.Page
import com.abhinavxt.debforge.domain.ProviderId
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real-Debrid behind the generic [DebridProvider] seam. Behaviour is unchanged
 * from the original single-provider app: list /downloads, fall back to
 * /unrestrict/link when a stored direct URL dies.
 */
@Singleton
class RealDebridProvider @Inject constructor(
    private val api: RealDebridApi
) : DebridProvider {

    override val info = ProviderInfo(
        id = ProviderId.REAL_DEBRID,
        displayName = "Real-Debrid",
        tokenUrl = "https://real-debrid.com/apitoken",
        tokenUrlLabel = "real-debrid.com/apitoken",
        tokenLabel = "API token",
        emptyListHint = "Add a magnet or link on real-debrid.com to populate this list."
    )

    override suspend fun validateToken(token: String): AccountInfo {
        val user = api.validateUser("Bearer $token")
        return AccountInfo(displayName = user.username, premiumUntil = user.expiration)
    }

    override suspend fun listFiles(page: Int, pageSize: Int): Page<DownloadItem> {
        // RD answers 204 (null body) for an empty account and 404 past the
        // last page on some accounts — both mean "no more".
        val dtos = try {
            api.getDownloads(page = page, limit = pageSize).orEmpty()
        } catch (e: HttpException) {
            if (e.code() == 404) emptyList() else throw e
        }
        // Keep the original "empty page = end" semantics: RD may return fewer
        // than `limit` rows mid-list, so a short page is not proof of the end.
        return Page(items = dtos.map { it.toDomain() }, hasMore = dtos.isNotEmpty())
    }

    override suspend fun resolveLink(sourceRef: String): ResolvedLink {
        val r = api.unrestrictLink(sourceRef)
        return ResolvedLink(
            url = r.download,
            maxConnections = r.chunks.coerceAtLeast(1),
            filesize = r.filesize.takeIf { it > 0 }
        )
    }

    /**
     * Links only. Unrestricting a hoster link adds it to /downloads, which is
     * exactly what Browse lists. Magnets/torrents are NOT offered: on RD they
     * land in /torrents and only reach /downloads after each file is
     * unrestricted, so they'd never show up here without a /torrents listing.
     */
    override val addCapabilities = setOf(AddKind.LINK)

    override suspend fun add(request: AddRequest): String = when (request) {
        is AddRequest.Link -> "Added ${api.unrestrictLink(request.url).filename}"
        else -> super.add(request)
    }
}

// RD ids are stored un-prefixed so rows queued by older app versions keep
// matching the browse list after the upgrade.
private fun RdDownloadDto.toDomain() = DownloadItem(
    id = id,
    provider = ProviderId.REAL_DEBRID,
    filename = filename,
    sourceRef = link,
    downloadUrl = download,
    host = host,
    filesize = filesize,
    maxConnections = chunks.coerceAtLeast(1),
    addedAt = generated
)
