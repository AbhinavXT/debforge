package com.abhinavxt.debforge.data.provider.torbox

import com.abhinavxt.debforge.data.prefs.TokenStore
import com.abhinavxt.debforge.data.provider.AccountInfo
import com.abhinavxt.debforge.data.provider.AddResult
import com.abhinavxt.debforge.data.provider.RemoteJob
import com.abhinavxt.debforge.data.provider.DebridProvider
import com.abhinavxt.debforge.data.provider.ProviderException
import com.abhinavxt.debforge.data.provider.ProviderInfo
import com.abhinavxt.debforge.data.provider.ResolvedLink
import com.abhinavxt.debforge.data.provider.torbox.dto.TbEnvelope
import com.abhinavxt.debforge.data.provider.torbox.dto.TbItemDto
import com.abhinavxt.debforge.data.provider.torbox.dto.TbTorrentControl
import com.abhinavxt.debforge.data.provider.torbox.dto.TbUsenetControl
import com.abhinavxt.debforge.data.provider.torbox.dto.TbWebControl
import com.abhinavxt.debforge.domain.AddKind
import com.abhinavxt.debforge.domain.AddRequest
import com.abhinavxt.debforge.domain.DownloadItem
import com.abhinavxt.debforge.domain.Page
import com.abhinavxt.debforge.domain.ProviderId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withLock
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
 *    that are ready ([TorBoxStatus.isReady]).
 *  - Three separate lists. Page 1 reads all three in full (fresh), sorts them
 *    newest first here, and later pages are slices of that snapshot; the
 *    processing strip uses the same read.
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
        emptyListHint = "Add a torrent, NZB or link on torbox.app to populate this list.",
        websiteUrl = "https://torbox.app/"
    )

    override suspend fun validateToken(token: String): AccountInfo {
        return api.validateUser("Bearer $token").unwrap().toAccount()
    }

    override suspend fun account(): AccountInfo = api.me().unwrap().toAccount()

    private fun com.abhinavxt.debforge.data.provider.torbox.dto.TbUserDto.toAccount() = AccountInfo(
        displayName = email ?: "TorBox user $id",
        premiumUntil = premiumExpiresAt,
        plan = when (plan) {
            0 -> "Free"
            1 -> "Essential"
            2 -> "Pro"
            3 -> "Standard"
            else -> null
        }
    )

    /**
     * Pages of ready items, newest first. Page 1 (every refresh) reads the
     * whole account fresh and sorts it here; later pages are slices of that
     * copy. TorBox doesn't document the order `mylist` returns, and asking it
     * page by page meant a new item could sit on a later page and never
     * appear at the top, however often the user refreshed.
     */
    override suspend fun listFiles(page: Int, pageSize: Int): Page<DownloadItem> {
        val ready = allItems(fresh = page == 1).filter { (_, item) -> item.isListable() }
        val from = ((page - 1).coerceAtLeast(0) * pageSize).coerceAtMost(ready.size)
        val to = (from + pageSize).coerceAtMost(ready.size)
        return Page(
            items = ready.subList(from, to).flatMap { (kind, item) -> item.toFiles(kind) },
            hasMore = to < ready.size
        )
    }

    // --- whole-account snapshot ------------------------------------------------

    private class Snapshot(val takenAt: Long, val items: List<Pair<Kind, TbItemDto>>)

    private val snapshotLock = kotlinx.coroutines.sync.Mutex()
    @Volatile private var snapshot: Snapshot? = null

    /**
     * Every torrent, usenet job and web download, newest first. [fresh] reads
     * TorBox again (bypassing its cache), except within a few seconds of the
     * last read, so a refresh that loads page 1 and the processing strip
     * together costs one round of requests, not two.
     */
    private suspend fun allItems(fresh: Boolean): List<Pair<Kind, TbItemDto>> = snapshotLock.withLock {
        val now = android.os.SystemClock.elapsedRealtime()
        snapshot?.let { s -> if (!fresh || now - s.takenAt < SNAPSHOT_REUSE_MS) return@withLock s.items }
        val items = coroutineScope {
            val torrents = async { fetchAll { off, lim -> api.torrents(off, lim, true).unwrapList() } }
            // Usenet / web downloads are secondary: a failure there (plan
            // limits, transient 5xx) shouldn't hide the user's torrents. Auth
            // failures still propagate so a revoked key is reported properly.
            val usenet = async { secondary { fetchAll { off, lim -> api.usenet(off, lim, true).unwrapList() } } }
            val web = async { secondary { fetchAll { off, lim -> api.webDownloads(off, lim, true).unwrapList() } } }
            torrents.await().map { Kind.TORRENT to it } +
                usenet.await().map { Kind.USENET to it } +
                web.await().map { Kind.WEB to it }
        }
        val sorted = TorBoxStatus.newestFirst(fillMissingFiles(items), { it.second.createdAt }, { it.second.id })
        snapshot = Snapshot(now, sorted)
        sorted
    }

    /**
     * Asks TorBox directly for ready items the list returned without files
     * (see [TorBoxStatus.needingFiles]). An item still without files after
     * that shows in the processing strip until they arrive.
     */
    private suspend fun fillMissingFiles(items: List<Pair<Kind, TbItemDto>>): List<Pair<Kind, TbItemDto>> {
        val wanted = TorBoxStatus.needingFiles(
            items, MAX_FILE_LOOKUPS,
            ready = { it.second.isReady() },
            fileCount = { it.second.files?.size ?: 0 },
            createdAt = { it.second.createdAt },
            id = { it.second.id }
        )
        if (wanted.isEmpty()) return items
        val found: Map<Pair<Kind, Long>, TbItemDto> = coroutineScope {
            wanted.map { (kind, item) -> async { lookup(kind, item.id)?.let { (kind to item.id) to it } } }
                .awaitAll()
                .filterNotNull()
                .toMap()
        }
        return items.map { (kind, item) -> kind to (found[kind to item.id] ?: item) }
    }

    /** One item, fresh. Null on any failure: the list's copy is kept. */
    private suspend fun lookup(kind: Kind, id: Long): TbItemDto? = try {
        when (kind) {
            Kind.TORRENT -> api.torrent(id)
            Kind.USENET -> api.usenetJob(id)
            Kind.WEB -> api.webDownload(id)
        }.unwrap()
    } catch (ce: CancellationException) {
        throw ce
    } catch (e: Exception) {
        null
    }

    /** One list in [FETCH_LIMIT]-sized requests until a short one (capped). */
    private suspend fun fetchAll(page: suspend (offset: Int, limit: Int) -> List<TbItemDto>): List<TbItemDto> {
        val out = ArrayList<TbItemDto>()
        var offset = 0
        while (offset < MAX_ITEMS) {
            val batch = page(offset, FETCH_LIMIT)
            out += batch
            if (batch.size < FETCH_LIMIT) break
            offset += FETCH_LIMIT
        }
        return out
    }

    private fun TbItemDto.isReady() = TorBoxStatus.isReady(downloadPresent, downloadFinished, downloadState)

    /** Ready and its files known: can be shown in the Library. */
    private fun TbItemDto.isListable() = isReady() && !files.isNullOrEmpty()

    override suspend fun resolveLink(sourceRef: String): ResolvedLink {
        if (sourceRef.startsWith(ZIP_PREFIX)) return resolveZip(sourceRef.removePrefix(ZIP_PREFIX))
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

    // --- whole-item zip -------------------------------------------------------

    override fun zipBundle(parentRef: String, parentName: String, fileCount: Int): DownloadItem? {
        val (kind, id) = parseJobRef(parentRef) ?: return null
        val safe = parentName.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim().ifEmpty { "${kind.key}_$id" }
        return DownloadItem(
            id = "tb:zip:${kind.key}:$id",
            provider = ProviderId.TORBOX,
            filename = "$safe.zip",
            sourceRef = "$ZIP_PREFIX${kind.key}:$id",
            downloadUrl = null,
            host = "${kind.label} · zip of $fileCount files",
            filesize = 0L, // unknown until TorBox starts streaming it
            maxConnections = 1,
            addedAt = null,
            parentRef = parentRef,
            parentName = parentName
        )
    }

    private suspend fun resolveZip(jobRef: String): ResolvedLink {
        val (kind, id) = parseJobRef(jobRef) ?: throw ProviderException("Corrupt TorBox zip reference: $jobRef")
        val token = tokenStore.token(ProviderId.TORBOX) ?: throw ProviderException("Not signed in to TorBox")
        val envelope = when (kind) {
            Kind.TORRENT -> api.torrentLink(token, id, null, zipLink = true)
            Kind.USENET -> api.usenetLink(token, id, null, zipLink = true)
            Kind.WEB -> api.webDownloadLink(token, id, null, zipLink = true)
        }
        val url = envelope.unwrap()
        if (url.isBlank()) throw ProviderException("TorBox returned an empty zip link")
        return ResolvedLink(url = url, maxConnections = 1, filesize = null)
    }

    private fun parseJobRef(ref: String): Pair<Kind, Long>? {
        val parts = ref.split(':')
        if (parts.size != 2) return null
        val kind = Kind.fromKey(parts[0]) ?: return null
        val id = parts[1].toLongOrNull() ?: return null
        return kind to id
    }

    // --- adding ------------------------------------------------------------

    override val addCapabilities = setOf(AddKind.MAGNET, AddKind.TORRENT_FILE, AddKind.LINK)

    override suspend fun add(request: AddRequest): AddResult {
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
        val message = envelope.detail?.takeIf { it.isNotBlank() } ?: "Added to TorBox"
        // data is e.g. {"torrent_id": 123, "hash": ...} or {"queued_id": 9}
        // when the account's active slots are full. Only a real item id can be
        // watched for "download when ready".
        val data = envelope.data as? Map<*, *>
        val kind = when (request) {
            is AddRequest.Link -> Kind.WEB
            else -> Kind.TORRENT
        }
        val id = listOf("torrent_id", "webdownload_id", "web_id", "usenetdownload_id", "id")
            .firstNotNullOfOrNull { k -> (data?.get(k) as? Number)?.toLong() }
        return AddResult(message = message, jobRef = id?.let { "${kind.key}:$it" })
    }

    // --- jobs still processing on TorBox ---------------------------------------

    /**
     * Everything not in the Library yet, from the same whole-account read:
     * items still downloading, and ready ones whose files TorBox hasn't
     * listed yet (so they're never invisible; they move to the Library by
     * themselves once the files arrive).
     */
    override suspend fun listProcessing(): List<RemoteJob> =
        allItems(fresh = true)
            .filter { (_, item) -> !item.isListable() }
            .map { (kind, item) -> item.toJob(kind) }

    override suspend fun filesForJob(jobRef: String): List<DownloadItem>? {
        val parts = jobRef.split(':')
        val kind = parts.getOrNull(0)?.let(Kind::fromKey)
        val id = parts.getOrNull(1)?.toLongOrNull()
        if (parts.size != 2 || kind == null || id == null) {
            throw ProviderException("Corrupt TorBox job reference: $jobRef")
        }
        val item = when (kind) {
            Kind.TORRENT -> api.torrent(id)
            Kind.USENET -> api.usenetJob(id)
            Kind.WEB -> api.webDownload(id)
        }.unwrap()
        // Ready but no files listed yet counts as "not ready": keep waiting.
        return if (item.isListable()) item.toFiles(kind) else null
    }

    // --- removing ------------------------------------------------------------

    /**
     * Every file of a torrent / usenet job / web download shares one key, the
     * job ref ("torrent:123"), which is also what the processing strip uses.
     * Item ids look like "tb:torrent:123:4" or "tb:zip:torrent:123".
     */
    override fun removalKey(itemId: String): String? {
        val parts = itemId.split(':')
        return when {
            parts.size == 4 && parts[0] == "tb" && parts[1] == "zip" -> "${parts[2]}:${parts[3]}"
            parts.size == 4 && parts[0] == "tb" -> "${parts[1]}:${parts[2]}"
            else -> null
        }?.takeIf { parseJobRef(it) != null }
    }

    override suspend fun remove(key: String) {
        val (kind, id) = parseJobRef(key) ?: throw ProviderException("Corrupt TorBox reference: $key")
        val envelope = when (kind) {
            Kind.TORRENT -> api.controlTorrent(TbTorrentControl(id))
            Kind.USENET -> api.controlUsenet(TbUsenetControl(id))
            Kind.WEB -> api.controlWebDownload(TbWebControl(id))
        }
        if (!envelope.success) {
            throw ProviderException("TorBox: ${envelope.detail ?: envelope.error ?: "couldn't delete"}")
        }
    }

    override suspend fun itemIdsIn(key: String): List<String>? =
        filesForJob(key)?.map { it.id }

    override suspend fun itemsIn(key: String): List<DownloadItem>? = filesForJob(key)

    // --- cache check ---------------------------------------------------------

    override val supportsCacheCheck = true

    override suspend fun cachedHashes(infoHashes: List<String>): Set<String> {
        if (infoHashes.isEmpty()) return emptySet()
        // ~100 hashes per request per TorBox's docs.
        return infoHashes.distinct().chunked(100).flatMap { batch ->
            val envelope = api.checkCached(batch.joinToString(","))
            if (!envelope.success) {
                throw ProviderException("TorBox: ${envelope.detail ?: envelope.error ?: "cache check failed"}")
            }
            cachedFrom(envelope.data)
        }.toSet()
    }

    /** list format: [{hash, size, ...}]; object format: {hash: {...}}. */
    private fun cachedFrom(data: Any?): List<String> = when (data) {
        is List<*> -> data.mapNotNull { entry ->
            val m = entry as? Map<*, *> ?: return@mapNotNull null
            val size = (m["size"] as? Number)?.toLong()
            (m["hash"] as? String)?.lowercase()?.takeIf { size == null || size > 0 }
        }
        is Map<*, *> -> data.keys.mapNotNull { (it as? String)?.lowercase() }
        else -> emptyList()
    }

    private fun TbItemDto.toJob(kind: Kind) = RemoteJob(
        ref = "${kind.key}:$id",
        provider = ProviderId.TORBOX,
        name = name?.takeIf { it.isNotBlank() } ?: "${kind.label} $id",
        progress = progress?.toFloat()?.coerceIn(0f, 1f),
        status = if (isReady()) "preparing files" else downloadState?.replace('_', ' ') ?: "processing",
        sizeBytes = size ?: 0L,
        etaSeconds = eta?.toLong()?.takeIf { it > 0 },
        bytesPerSecond = downloadSpeed?.toLong()?.takeIf { it > 0 }
    )

    // --- mapping -----------------------------------------------------------

    private fun TbItemDto.toFiles(kind: Kind): List<DownloadItem> {
        if (!isReady()) return emptyList()
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
                addedAt = createdAt,
                parentRef = "${kind.key}:$id",
                parentName = parent.ifEmpty { null },
                // "Torrent Name/Subs/…/2_English.srt": keep the folders.
                path = f.name?.takeIf { '/' in it }
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

        /** sourceRef prefix for whole-item zip downloads: "zip:torrent:123". */
        const val ZIP_PREFIX = "zip:"

        /** Items per `mylist` request when reading the whole account. */
        const val FETCH_LIMIT = 1000
        /** Safety cap per list (very large accounts). */
        const val MAX_ITEMS = 20_000
        /** A second fresh read within this window reuses the first (page 1 + processing strip). */
        const val SNAPSHOT_REUSE_MS = 5_000L
        /** Most single-item lookups per refresh for items listed without files (rate limit). */
        const val MAX_FILE_LOOKUPS = 10

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
