package com.abhinavxt.debforge.data.provider.torbox

import com.abhinavxt.debforge.data.prefs.TokenStore
import com.abhinavxt.debforge.data.provider.AccountInfo
import com.abhinavxt.debforge.data.provider.AddResult
import com.abhinavxt.debforge.data.provider.JobAction
import com.abhinavxt.debforge.data.provider.RemoteJob
import com.abhinavxt.debforge.data.provider.DebridProvider
import com.abhinavxt.debforge.data.provider.DeviceLogin
import com.abhinavxt.debforge.data.provider.NotCachedException
import com.abhinavxt.debforge.data.provider.ProviderException
import com.abhinavxt.debforge.data.provider.ProviderInfo
import com.abhinavxt.debforge.data.provider.ResolvedLink
import com.abhinavxt.debforge.data.provider.torbox.dto.TbEnvelope
import com.abhinavxt.debforge.data.provider.torbox.dto.TbItemDto
import com.abhinavxt.debforge.data.provider.torbox.dto.TbQueuedControl
import com.abhinavxt.debforge.data.provider.torbox.dto.TbQueuedDto
import com.abhinavxt.debforge.data.provider.torbox.dto.TbTorrentControl
import com.abhinavxt.debforge.data.provider.torbox.dto.TbUsenetControl
import com.abhinavxt.debforge.data.provider.torbox.dto.TbWebControl
import com.abhinavxt.debforge.domain.AddKind
import com.abhinavxt.debforge.domain.AddRequest
import com.abhinavxt.debforge.domain.DownloadItem
import com.abhinavxt.debforge.domain.Page
import com.abhinavxt.debforge.domain.ProviderId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.abhinavxt.debforge.data.provider.JobFailedException
import com.abhinavxt.debforge.domain.FollowMatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
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
    deviceApi: TorBoxDeviceApi,
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

    override val deviceLogin: DeviceLogin = TorBoxDeviceLogin(deviceApi) { validateToken(it) }

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

    private class Snapshot(val token: String?, val takenAt: Long, val items: List<Pair<Kind, TbItemDto>>)

    private val snapshotLock = kotlinx.coroutines.sync.Mutex()
    @Volatile private var snapshot: Snapshot? = null
    /** When DebForge last added or removed something: older snapshots are out of date. */
    @Volatile private var changedAt = Long.MIN_VALUE

    /** Runs a change to the account. Counts as changed even on failure: TorBox may have done it anyway. */
    private suspend fun <T> changing(block: suspend () -> T): T = try {
        block()
    } finally {
        changedAt = android.os.SystemClock.elapsedRealtime()
    }

    /**
     * Every torrent, usenet job and web download, newest first. [fresh] reads
     * TorBox again (bypassing its cache), except within a few seconds of the
     * last read (unless an add or remove happened since that read began), so a
     * refresh that loads page 1 and the processing strip together costs one
     * round of requests, not two.
     */
    private suspend fun allItems(fresh: Boolean): List<Pair<Kind, TbItemDto>> = snapshotLock.withLock {
        val now = android.os.SystemClock.elapsedRealtime()
        // A snapshot belongs to one account: after a sign-in as someone else it is never served.
        val token = tokenStore.token(ProviderId.TORBOX)
        snapshot?.takeIf { it.token == token }?.let { s -> if (!fresh || (s.takenAt > changedAt && now - s.takenAt < SNAPSHOT_REUSE_MS)) return@withLock s.items }
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
        snapshot = Snapshot(token, now, sorted)
        sorted
    }

    // --- queued adds -------------------------------------------------------------

    /** What the last queue read found, per type, for one account (token). [at] is when that read began. */
    private class QueueState(val token: String?, val byType: Map<String, List<TbQueuedDto>>, val at: Long?)

    @Volatile private var queue = QueueState(null, emptyMap(), null)
    /** Epoch millis a queued id was created (or, for a fresh add, about now): a started copy can't be older. */
    private val queuedSince = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    /** When DebForge last queued, started or removed a queued item. */
    @Volatile private var queuedChangedAt = Long.MIN_VALUE
    /** Reads run here, not in the caller: a cancelled or slow caller never leaves half a read behind. */
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var refreshing: Deferred<Unit>? = null

    /**
     * The current account's queued adds. Read at most every [QUEUED_REUSE_MS],
     * or at once after a change made here, by one shared background read. The
     * caller waits at most [QUEUED_WAIT_MS] for it and otherwise gets the last
     * known list (the next call gets the new one). Never throws, and holds no
     * lock while waiting: the torrent list never depends on this.
     */
    private suspend fun queuedJobs(): List<TbQueuedDto> {
        val token = tokenStore.token(ProviderId.TORBOX)
        if (queue.token != token) {
            queue = QueueState(token, emptyMap(), null)
            queuedSince.clear()
        }
        val now = android.os.SystemClock.elapsedRealtime()
        val at = queue.at
        if (at == null || queuedChangedAt >= at || now - at >= QUEUED_REUSE_MS) {
            val read = synchronized(this) {
                refreshing?.takeIf { it.isActive } ?: refreshScope.async { refreshQueue(token, now) }.also { refreshing = it }
            }
            withTimeoutOrNull(QUEUED_WAIT_MS) { read.await() }
        }
        return queue.takeIf { it.token == token }?.byType?.values?.flatten().orEmpty().distinctBy { it.id }
    }

    /** One read of the three types. A type that fails keeps its previous entries; the others are not affected. */
    private suspend fun refreshQueue(token: String?, started: Long) {
        val previous = queue.takeIf { it.token == token }?.byType.orEmpty()
        val results = withTimeoutOrNull(QUEUED_TIMEOUT_MS) {
            coroutineScope {
                QUEUED_TYPES.map { type ->
                    async {
                        try {
                            type to fetchQueued(type)
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (e: Exception) {
                            type to null
                        }
                    }
                }.awaitAll()
            }
        }.orEmpty().toMap()
        val byType = QUEUED_TYPES.associateWith { results[it] ?: previous[it].orEmpty() }
        synchronized(this) {
            if (queue.token != token) return // the account changed meanwhile
            queue = QueueState(token, byType, started)
        }
        byType.values.flatten().forEach { q ->
            FollowMatcher.addedMillis(q.createdAt)?.let { queuedSince[q.id!!] = it }
        }
    }

    private suspend fun fetchQueued(type: String): List<TbQueuedDto> {
        val out = ArrayList<TbQueuedDto>()
        var offset = 0
        while (offset < MAX_QUEUED) {
            val env = api.queued(type, offset, FRESH_LIMIT, true)
            if (!env.success) throw ProviderException("TorBox: ${env.detail ?: env.error ?: "request failed"}")
            val rows = env.data.orEmpty()
            out += rows.filter { it.id != null }.map { it.copy(type = type) }
            if (rows.size < FRESH_LIMIT) break
            offset += FRESH_LIMIT
        }
        return out
    }

    /** Forgets [id] locally right after it was started or removed, until the next read. */
    private fun forgetQueued(id: Long) = synchronized(this) {
        val q = queue
        queue = QueueState(q.token, q.byType.mapValues { (_, l) -> l.filter { it.id != id } }, q.at)
    }

    private fun TbQueuedDto.toJob(): RemoteJob {
        val h = hash?.trim()?.lowercase().orEmpty()
        return RemoteJob(
            ref = "$QUEUED_PREFIX$id:$h:${type ?: "torrent"}",
            provider = ProviderId.TORBOX,
            name = nameOverride?.takeIf { it.isNotBlank() } ?: name?.takeIf { it.isNotBlank() }
                ?: h.takeIf { it.isNotEmpty() } ?: "Queued download",
            progress = null,
            status = "queued",
            sizeBytes = 0L,
            etaSeconds = null,
            bytesPerSecond = null,
            actions = setOf(JobAction.START_NOW),
            // Without a hash there is no way to find the started item.
            canWatch = h.isNotEmpty(),
            hash = h.takeIf { it.isNotEmpty() }
        )
    }

    /** True when the queue says [id] is queued on the current account. */
    private suspend fun isQueued(id: Long) = queuedJobs().any { it.id == id }

    /**
     * Queued items hold no files: the item TorBox started from one (same type
     * and hash, and not older than the queued entry) carries them.
     */
    private suspend fun queuedFiles(ref: QueuedRef): List<DownloadItem>? {
        if (ref.hash.isEmpty()) throw JobFailedException("TorBox: this queued download can't be followed without its hash")
        if (isQueued(ref.id)) return null
        val since = queuedSince[ref.id]
        val hit = allItems(fresh = true).firstOrNull { (kind, item) ->
            val made = FollowMatcher.addedMillis(item.createdAt)
            kind == ref.kind && item.hash.equals(ref.hash, ignoreCase = true) &&
                (since == null || made == null || made >= since)
        } ?: throw ProviderException("TorBox: the queued download is gone")
        return if (hit.second.isListable()) hit.second.toFiles(ref.kind) else null
    }

    override fun sameJob(watchRef: String, job: RemoteJob): Boolean {
        if (watchRef == job.ref) return true
        val q = QueuedRef.parse(watchRef) ?: return false
        val kind = parseJobRef(job.ref)?.first ?: QueuedRef.parse(job.ref)?.kind ?: return false
        return q.hash.isNotEmpty() && q.hash == job.hash && q.kind == kind
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

    /**
     * One list until a short page (capped). Small pages first: TorBox ignores
     * `bypass_cache` for limits around 500-1000 and serves a list that is
     * minutes old (measured 2026-11-25; 100 and below were always fresh).
     */
    // shortcut: items past FRESH_ITEMS may be minutes old, until TorBox fixes bypass_cache for large limits.
    private suspend fun fetchAll(page: suspend (offset: Int, limit: Int) -> List<TbItemDto>): List<TbItemDto> {
        val out = ArrayList<TbItemDto>()
        var offset = 0
        while (offset < MAX_ITEMS) {
            val limit = if (offset < FRESH_ITEMS) FRESH_LIMIT else FETCH_LIMIT
            val batch = page(offset, limit)
            out += batch
            if (batch.size < limit) break
            offset += limit
        }
        // The list can shift between two page requests.
        return out.distinctBy { it.id }
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

    override val addCapabilities = setOf(AddKind.MAGNET, AddKind.TORRENT_FILE, AddKind.LINK, AddKind.NZB_FILE, AddKind.NZB_LINK)

    /** TorBox answers refusals (no Usenet on the plan, limits) with a 4xx and its own message in the body. */
    override suspend fun add(request: AddRequest): AddResult = addChecked(request, onlyIfCached = false)

    override val canAddOnlyIfCached = true

    override suspend fun addOnlyIfCached(request: AddRequest): AddResult = addChecked(request, onlyIfCached = true)

    private suspend fun addChecked(request: AddRequest, onlyIfCached: Boolean): AddResult = try {
        addRequest(request, onlyIfCached)
    } catch (e: HttpException) {
        val body = if (e.code() == 401 || e.code() == 403) null
        else runCatching { e.response()?.errorBody()?.string() }.getOrNull()
        val detail = tbRejectionDetail(body)
        throw when {
            tbIsNotCached(body) -> NotCachedException("Not cached on TorBox, nothing was added")
            detail != null -> ProviderException("TorBox: $detail", e)
            else -> e
        }
    }

    private suspend fun addRequest(request: AddRequest, onlyIfCached: Boolean): AddResult {
        val text = "text/plain".toMediaType()
        val cachedOnly = if (onlyIfCached) "true".toRequestBody(text) else null
        val envelope = changing {
            when (request) {
                is AddRequest.Magnet ->
                    api.createTorrentFromMagnet(request.uri.toRequestBody(text), cachedOnly)
                is AddRequest.TorrentFile -> api.createTorrentFromFile(
                    MultipartBody.Part.createFormData(
                        "file",
                        request.name,
                        request.bytes.toRequestBody("application/x-bittorrent".toMediaType())
                    ),
                    cachedOnly
                )
                is AddRequest.Link ->
                    api.createWebDownload(request.url.toRequestBody(text))
                is AddRequest.NzbFile -> api.createUsenetFromFile(
                    MultipartBody.Part.createFormData(
                        "file",
                        request.name.substringAfterLast('/').substringAfterLast('\\'),
                        request.bytes.toRequestBody("application/x-nzb".toMediaType())
                    ),
                    cachedOnly
                )
                is AddRequest.NzbLink ->
                    api.createUsenetFromLink(request.url.toRequestBody(text), cachedOnly)
            }
        }
        if (!envelope.success) {
            if (envelope.error == "DOWNLOAD_NOT_CACHED") throw NotCachedException("Not cached on TorBox, nothing was added")
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
            is AddRequest.NzbFile, is AddRequest.NzbLink -> Kind.USENET
            else -> Kind.TORRENT
        }
        val id = listOf("torrent_id", "webdownload_id", "web_id", "usenetdownload_id", "id")
            .firstNotNullOfOrNull { k -> (data?.get(k) as? Number)?.toLong() }
        // Slots full: TorBox queued it. Watch it by its queued ref (it carries the hash, see filesForJob).
        val queuedId = (data?.get("queued_id") as? Number)?.toLong()
        if (queuedId != null) {
            queuedChangedAt = android.os.SystemClock.elapsedRealtime()
            queuedSince[queuedId] = System.currentTimeMillis() - QUEUED_SKEW_MS
            val hash = (data?.get("hash") as? String)?.trim()?.lowercase().orEmpty()
            val type = if (kind == Kind.WEB) "webdl" else "torrent"
            // No hash: the started item can't be found, so there is nothing to watch.
            return AddResult(message, jobRef = hash.takeIf { it.isNotEmpty() }?.let { "$QUEUED_PREFIX$queuedId:$it:$type" })
        }
        return AddResult(message = message, jobRef = id?.let { "${kind.key}:$it" })
    }

    // --- jobs still processing on TorBox ---------------------------------------

    /**
     * Everything not in the Library yet, from the same whole-account read:
     * items still downloading, and ready ones whose files TorBox hasn't
     * listed yet (so they're never invisible; they move to the Library by
     * themselves once the files arrive).
     */
    override suspend fun listProcessing(): List<RemoteJob> = coroutineScope {
        val queued = async { queuedJobs() }
        allItems(fresh = true)
            .filter { (_, item) -> !item.isListable() }
            .map { (kind, item) -> item.toJob(kind) } + queued.await().map { it.toJob() }
    }

    override suspend fun filesForJob(jobRef: String): List<DownloadItem>? {
        QueuedRef.parse(jobRef)?.let { return queuedFiles(it) }
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
        QueuedRef.parse(key)?.let { return controlQueued(it.id, "delete") }
        val (kind, id) = parseJobRef(key) ?: throw ProviderException("Corrupt TorBox reference: $key")
        val envelope = changing {
            when (kind) {
                Kind.TORRENT -> api.controlTorrent(TbTorrentControl(id))
                Kind.USENET -> api.controlUsenet(TbUsenetControl(id))
                Kind.WEB -> api.controlWebDownload(TbWebControl(id))
            }
        }
        if (!envelope.success) {
            throw ProviderException("TorBox: ${envelope.detail ?: envelope.error ?: "couldn't delete"}")
        }
    }

    private suspend fun controlQueued(id: Long, operation: String) {
        // Never send an id that isn't in this account's queue (e.g. a row left over from another account).
        if (!isQueued(id)) throw ProviderException("TorBox: that download is no longer in the queue")
        val envelope = try {
            changing { api.controlQueued(TbQueuedControl(id, operation)) }
        } finally {
            queuedChangedAt = android.os.SystemClock.elapsedRealtime()
        }
        if (!envelope.success) {
            throw ProviderException("TorBox: ${envelope.detail ?: envelope.error ?: "couldn't $operation"}")
        }
        forgetQueued(id)
    }

    override suspend fun controlJob(ref: String, action: JobAction) {
        if (action == JobAction.START_NOW) {
            val q = QueuedRef.parse(ref) ?: throw ProviderException("Corrupt TorBox reference: $ref")
            return controlQueued(q.id, "start")
        }
        val (kind, id) = parseJobRef(ref)?.takeIf { it.first == Kind.TORRENT }
            ?: throw ProviderException("Corrupt TorBox reference: $ref")
        val operation = if (action == JobAction.RESUME) "resume" else "reannounce"
        val envelope = changing { api.controlTorrent(TbTorrentControl(id, operation)) }
        if (!envelope.success) {
            throw ProviderException("TorBox: ${envelope.detail ?: envelope.error ?: "couldn't $operation"}")
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
        bytesPerSecond = downloadSpeed?.toLong()?.takeIf { it > 0 },
        hash = hash?.trim()?.lowercase()?.takeIf { it.isNotEmpty() },
        actions = when {
            kind != Kind.TORRENT -> emptySet()
            downloadState.orEmpty().startsWith("stalled", ignoreCase = true) -> setOf(JobAction.REANNOUNCE)
            downloadState.orEmpty().startsWith("paused", ignoreCase = true) -> setOf(JobAction.RESUME)
            else -> emptySet()
        }
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

    /** "queued:<id>:<hash>:<type>" (older refs have no type: torrent); the hash may be empty. */
    private data class QueuedRef(val id: Long, val hash: String, val type: String) {
        val kind: Kind get() = when (type) {
            "usenet" -> Kind.USENET
            "webdl" -> Kind.WEB
            else -> Kind.TORRENT
        }

        companion object {
            fun parse(raw: String): QueuedRef? {
                val parts = raw.split(':')
                if (parts.size !in 3..4 || parts[0] != "queued") return null
                val type = parts.getOrElse(3) { "torrent" }
                if (type !in QUEUED_TYPES) return null
                return QueuedRef(parts[1].toLongOrNull() ?: return null, parts[2].lowercase(), type)
            }
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

        const val QUEUED_PREFIX = "queued:"
        val QUEUED_TYPES = listOf("torrent", "usenet", "webdl")
        /** The queue is read at most this often (three requests), unless we just changed it. */
        const val QUEUED_REUSE_MS = 30_000L
        const val QUEUED_TIMEOUT_MS = 10_000L
        /** Longest a caller waits for a queue read before using the last known queue. */
        const val QUEUED_WAIT_MS = 2_500L
        /** A fresh add is stamped this much earlier, for clock differences with TorBox. */
        const val QUEUED_SKEW_MS = 120_000L
        /** Safety cap per type. */
        const val MAX_QUEUED = 500

        /** Largest `mylist` page known to honour `bypass_cache`, used for the first [FRESH_ITEMS]. */
        const val FRESH_LIMIT = 100
        const val FRESH_ITEMS = 1000
        /** Items per `mylist` request after that. */
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
