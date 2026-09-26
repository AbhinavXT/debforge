package com.abhinavxt.debforge.data.provider.torbox

import com.abhinavxt.debforge.data.prefs.TokenStore
import com.abhinavxt.debforge.data.provider.AccountInfo
import com.abhinavxt.debforge.data.provider.DebridProvider
import com.abhinavxt.debforge.data.provider.ProviderException
import com.abhinavxt.debforge.data.provider.ProviderInfo
import com.abhinavxt.debforge.data.provider.ResolvedLink
import com.abhinavxt.debforge.data.provider.torbox.dto.TbEnvelope
import com.abhinavxt.debforge.data.provider.torbox.dto.TbItemDto
import com.abhinavxt.debforge.domain.AddKind
import com.abhinavxt.debforge.domain.AddRequest
import com.abhinavxt.debforge.domain.DownloadItem
import com.abhinavxt.debforge.domain.Page
import com.abhinavxt.debforge.domain.ProviderId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TorBox behind the generic [DebridProvider] seam.
 *
 * Differences from Real-Debrid that this class absorbs:
 *  - The listing is per TORRENT / USENET JOB / WEB DOWNLOAD, each holding many
 *    files. We flatten to one [DownloadItem] per file and only include items
 *    whose files are actually present on TorBox's servers.
 *  - Three separate lists. Each browse page fetches the same offset window from
 *    all three in parallel; there are more pages while ANY list returned a
 *    full window.
 *  - Listings carry no direct URL. Links are minted on demand by `requestdl`
 *    at download time ([resolveLink]), so [DownloadItem.downloadUrl] is null
 *    and [DownloadItem.sourceRef] encodes "kind:itemId:fileId".
 *  - No per-file connection limit is advertised; we use [DEFAULT_CONNECTIONS].
 */
@Singleton
class TorBoxProvider @Inject constructor(
    private val api: TorBoxApi,
    private val tokenStore: TokenStore
) : DebridProvider {

    override val info = ProviderInfo(
        id = ProviderId.TORBOX,
        displayName = "TorBox",
        tokenUrl = "https://torbox.app/settings",
        tokenUrlLabel = "torbox.app/settings",
        tokenLabel = "API key",
        emptyListHint = "Add a torrent, NZB or link on torbox.app to populate this list."
    )

    override suspend fun validateToken(token: String): AccountInfo {
        val user = api.validateUser("Bearer $token").unwrap()
        return AccountInfo(
            displayName = user.email ?: "TorBox user ${user.id}",
            premiumUntil = user.premiumExpiresAt
        )
    }

    override suspend fun listFiles(page: Int, pageSize: Int): Page<DownloadItem> = coroutineScope {
        val offset = (page - 1).coerceAtLeast(0) * pageSize
        val fresh = page == 1
        val torrents = async { api.torrents(offset, pageSize, fresh).unwrapList() }
        // Usenet / web downloads are secondary: a failure there (plan limits,
        // transient 5xx) shouldn't hide the user's torrents. Auth failures
        // still propagate so a revoked key is reported properly.
        val usenet = async { secondary { api.usenet(offset, pageSize, fresh).unwrapList() } }
        val web = async { secondary { api.webDownloads(offset, pageSize, fresh).unwrapList() } }

        val lists = listOf(
            Kind.TORRENT to torrents.await(),
            Kind.USENET to usenet.await(),
            Kind.WEB to web.await()
        )
        Page(
            items = lists.flatMap { (kind, items) -> items.flatMap { it.toFiles(kind) } },
            hasMore = lists.any { (_, items) -> items.size >= pageSize }
        )
    }

    override suspend fun resolveLink(sourceRef: String): ResolvedLink {
        val ref = SourceRef.parse(sourceRef)
            ?: throw ProviderException("Corrupt TorBox reference: $sourceRef")
        val token = tokenStore.token(ProviderId.TORBOX)
            ?: throw ProviderException("Not signed in to TorBox")
        val envelope = when (ref.kind) {
            Kind.TORRENT -> api.torrentLink(token, ref.itemId, ref.fileId)
            Kind.USENET -> api.usenetLink(token, ref.itemId, ref.fileId)
            Kind.WEB -> api.webDownloadLink(token, ref.itemId, ref.fileId)
        }
        val url = envelope.unwrap()
        if (url.isBlank()) throw ProviderException("TorBox returned an empty download link")
        return ResolvedLink(url = url, maxConnections = null, filesize = null)
    }

    // --- adding ------------------------------------------------------------

    override val addCapabilities = setOf(AddKind.MAGNET, AddKind.TORRENT_FILE, AddKind.LINK)

    override suspend fun add(request: AddRequest): String {
        val text = "text/plain".toMediaType()
        val envelope = when (request) {
            is AddRequest.Magnet ->
                api.createTorrentFromMagnet(request.uri.toRequestBody(text))
            is AddRequest.TorrentFile -> api.createTorrentFromFile(
                MultipartBody.Part.createFormData(
                    "file",
                    request.name,
                    request.bytes.toRequestBody("application/x-bittorrent".toMediaType())
                )
            )
            is AddRequest.Link ->
                api.createWebDownload(request.url.toRequestBody(text))
        }
        if (!envelope.success) {
            throw ProviderException("TorBox: ${envelope.detail ?: envelope.error ?: "couldn't add"}")
        }
        // e.g. "Found cached torrent. Using cached torrent." / "Added to queue."
        return envelope.detail?.takeIf { it.isNotBlank() } ?: "Added to TorBox"
    }

    // --- mapping -----------------------------------------------------------

    private fun TbItemDto.toFiles(kind: Kind): List<DownloadItem> {
        val ready = downloadPresent ?: downloadFinished ?: false
        if (!ready) return emptyList()
        val parent = name.orEmpty().trim()
        return files.orEmpty().map { f ->
            val filename = f.shortName?.takeIf { it.isNotBlank() }
                ?: f.name?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                ?: "${kind.key}_${id}_${f.id}"
            DownloadItem(
                id = "tb:${kind.key}:$id:${f.id}",
                provider = ProviderId.TORBOX,
                filename = filename,
                sourceRef = SourceRef(kind, id, f.id).encode(),
                downloadUrl = null,
                // Parent name in the subtitle gives context for multi-file
                // torrents ("E01.mkv" alone is useless) and makes it searchable.
                host = if (parent.isEmpty() || parent == filename) kind.label else "${kind.label} · $parent",
                filesize = f.size ?: 0L,
                maxConnections = DEFAULT_CONNECTIONS,
                addedAt = createdAt
            )
        }
    }

    private enum class Kind(val key: String, val label: String) {
        TORRENT("torrent", "Torrent"),
        USENET("usenet", "Usenet"),
        WEB("web", "Web");

        companion object {
            fun fromKey(key: String) = entries.firstOrNull { it.key == key }
        }
    }

    private data class SourceRef(val kind: Kind, val itemId: Long, val fileId: Long) {
        fun encode() = "${kind.key}:$itemId:$fileId"

        companion object {
            fun parse(raw: String): SourceRef? {
                val parts = raw.split(':')
                if (parts.size != 3) return null
                val kind = Kind.fromKey(parts[0]) ?: return null
                val item = parts[1].toLongOrNull() ?: return null
                val file = parts[2].toLongOrNull() ?: return null
                return SourceRef(kind, item, file)
            }
        }
    }

    private companion object {
        /**
         * TorBox doesn't publish a per-file connection cap. 8 is a
         * conservative default that still saturates most mobile links; the
         * ChunkPlanner hard-caps at 16 regardless.
         */
        const val DEFAULT_CONNECTIONS = 8

        fun <T> TbEnvelope<T>.unwrap(): T {
            if (!success) throw ProviderException("TorBox: ${detail ?: error ?: "request failed"}")
            return data ?: throw ProviderException("TorBox: ${detail ?: "empty response"}")
        }

        fun TbEnvelope<List<TbItemDto>>.unwrapList(): List<TbItemDto> {
            if (!success) throw ProviderException("TorBox: ${detail ?: error ?: "request failed"}")
            return data.orEmpty()
        }

        suspend fun secondary(block: suspend () -> List<TbItemDto>): List<TbItemDto> = try {
            block()
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: HttpException) {
            if (e.code() == 401 || e.code() == 403) throw e else emptyList()
        } catch (e: ProviderException) {
            emptyList()
        }
    }
}
