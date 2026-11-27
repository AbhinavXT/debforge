package com.abhinavxt.debforge.data.provider

import com.abhinavxt.debforge.domain.AddKind
import com.abhinavxt.debforge.domain.AddRequest
import com.abhinavxt.debforge.domain.DownloadItem
import com.abhinavxt.debforge.domain.Page
import com.abhinavxt.debforge.domain.ProviderId

/**
 * The one seam between the app and a debrid service. The UI, queue, and
 * chunked engine only ever talk to this interface; each service lives in its
 * own package (realdebrid/, torbox/) with its own Retrofit API + DTOs.
 *
 * Contract:
 *  - Methods may throw. [ProviderCalls] maps HttpException / IOException /
 *    [ProviderException] into user-facing messages, so implementations should
 *    just let failures propagate (and throw [ProviderException] for API-level
 *    errors that come back as HTTP 200, e.g. TorBox's `success: false`).
 *  - Authenticated calls use the token stored for THIS provider (see
 *    [com.abhinavxt.debforge.data.remote.AuthInterceptor]); [validateToken]
 *    is the only call that takes the token explicitly.
 */
interface DebridProvider {

    val info: ProviderInfo

    /** Checks a candidate token WITHOUT persisting it. Throws if invalid. */
    suspend fun validateToken(token: String): AccountInfo

    /** Account details for the stored token (Settings card, expiry warning). */
    suspend fun account(): AccountInfo

    /**
     * One page (1-based) of downloadable, ready-on-server files. Providers that
     * group files (torrents, usenet jobs) flatten them to one item per file.
     */
    suspend fun listFiles(page: Int, pageSize: Int): Page<DownloadItem>

    /**
     * Turns a [DownloadItem.sourceRef] into a fresh direct URL. Called lazily
     * before the first byte when the item had no URL, and again whenever a
     * stored URL has expired (401/403/404/410 on probe).
     */
    suspend fun resolveLink(sourceRef: String): ResolvedLink

    /** Which [AddRequest] kinds [add] accepts. Empty = adding isn't supported. */
    val addCapabilities: Set<AddKind> get() = emptySet()

    /**
     * Sends a magnet / .torrent / link to the service. Returns a short
     * user-facing confirmation (e.g. "Cached — ready now"). Only called for
     * kinds listed in [addCapabilities].
     */
    suspend fun add(request: AddRequest): AddResult =
        throw ProviderException("${info.displayName} doesn't support adding ${request.kind.label}")

    /** True if [addOnlyIfCached] works (TorBox). */
    val canAddOnlyIfCached: Boolean get() = false

    /** Like [add], but adds nothing unless the service already has it cached; throws [NotCachedException] otherwise. */
    suspend fun addOnlyIfCached(request: AddRequest): AddResult =
        throw ProviderException("${info.displayName} can't add only what is cached")

    // --- optional: jobs still being fetched by the service ------------------

    /** Torrents / jobs the service is still fetching (not downloadable yet). */
    suspend fun listProcessing(): List<RemoteJob> = emptyList()

    /**
     * Files of one job once it's ready, or null while it's still processing.
     * Throws [ProviderException] if the job is gone.
     */
    suspend fun filesForJob(jobRef: String): List<DownloadItem>? = null

    // --- optional: whole job as one archive -----------------------------------

    /**
     * A single downloadable item that packs every file of [parentRef] into
     * one archive (TorBox's zip links), or null if unsupported. Its size is
     * usually unknown until the download starts.
     */
    fun zipBundle(parentRef: String, parentName: String, fileCount: Int): DownloadItem? = null

    // --- optional: removing things from the service ---------------------------

    /**
     * The service-side entry that holds the file with id [itemId] (a
     * [DownloadItem.id]), or null if it can't be removed from the app.
     *
     * Files that share a key disappear together: a TorBox, AllDebrid or
     * Debrid-Link torrent is one key for all its files; a Real-Debrid download
     * or a Premiumize file is a key of its own. [RemoteJob.ref]s from
     * [listProcessing] are valid keys too (e.g. a stalled torrent).
     */
    fun removalKey(itemId: String): String? = null

    /**
     * Runs [action] on a job from [listProcessing] ([RemoteJob.actions] says
     * which are offered). Throws on failure.
     */
    suspend fun controlJob(ref: String, action: JobAction): Unit =
        throw ProviderException("${info.displayName} doesn't support that")

    /** True if a watch saved under [watchRef] is for [job] (a queued item and the download it became). */
    fun sameJob(watchRef: String, job: RemoteJob): Boolean = watchRef == job.ref

    /** Deletes [key] (from [removalKey] or a [RemoteJob.ref]) on the service. */
    suspend fun remove(key: String): Unit =
        throw ProviderException("${info.displayName} doesn't support removing items from the app")

    /**
     * Ids of every file that removing [key] would delete, used to remove a
     * torrent only once ALL of its files are downloaded. Null if unknown.
     */
    suspend fun itemIdsIn(key: String): List<String>? = null

    /**
     * The files [key] holds, for services that can list them (torrents).
     * Lets auto-remove ignore a torrent's extras (release-group notes, covers,
     * samples) that the user never downloads. Null = fall back to [itemIdsIn].
     */
    suspend fun itemsIn(key: String): List<DownloadItem>? = null

    // --- optional: is a torrent already cached on the service? ------------------

    /** True if [cachedHashes] works (TorBox, Premiumize; the others removed it). */
    val supportsCacheCheck: Boolean get() = false

    /** The subset of [infoHashes] (lowercase hex) the service has cached. */
    suspend fun cachedHashes(infoHashes: List<String>): Set<String> = emptySet()

    // --- optional: list a torrent's files before adding it ---------------------

    /** The files of the torrent with [hash] if the service can tell without adding it; null = no preview. Never throws. */
    suspend fun previewFiles(hash: String): List<com.abhinavxt.debforge.domain.TorrentEntry>? = null

    // --- optional: sign in by approving a code in the browser ---------------

    /** Non-null if the service supports device-code sign-in. */
    val deviceLogin: DeviceLogin? get() = null

    // --- optional: server-side quality choice for streaming (HLS) -----------

    /** True if [streamQualities] can offer lower-quality streams (TorBox Pro). */
    val supportsStreamQualities: Boolean get() = false

    /** Qualities for the file at [sourceRef], "Original" first; empty if none are offered. */
    suspend fun streamQualities(sourceRef: String): List<StreamQuality> = emptyList()

    /** A playable URL for [quality] of [sourceRef]. Only called with a quality from [streamQualities]. */
    suspend fun streamUrl(sourceRef: String, quality: StreamQuality): String =
        throw ProviderException("${info.displayName} doesn't offer stream qualities")
}

/** One choice in the stream quality list. [id] is "original" or provider-specific; [height] is null if unknown. */
data class StreamQuality(val id: String, val label: String, val height: Int?) {
    companion object { const val ORIGINAL = "original" }
}

/**
 * What an add produced. [readyFiles] are downloadable right now (e.g. a
 * hoster link Real-Debrid unrestricted); [jobRef] identifies a job the
 * service is still fetching, for "download when ready".
 */
data class AddResult(
    val message: String,
    val readyFiles: List<DownloadItem> = emptyList(),
    val jobRef: String? = null
)

/** A torrent / usenet job / web download the service is still working on. */
data class RemoteJob(
    val ref: String,
    val provider: ProviderId,
    val name: String,
    /** 0..1, or null if unknown. */
    val progress: Float?,
    /** Service's own status text ("downloading", "queued", "stalled", ...). */
    val status: String,
    val sizeBytes: Long,
    val etaSeconds: Long?,
    val bytesPerSecond: Long?,
    /** Extra things the service can do with this job (besides removing it). */
    val actions: Set<JobAction> = emptySet(),
    /** False when "download when ready" can't work for this job. */
    val canWatch: Boolean = true,
    /** Lowercase info hash, for telling a job apart from its earlier/later forms. */
    val hash: String? = null
)

/** Optional per-job actions of the processing strip. */
enum class JobAction { START_NOW, REANNOUNCE, RESUME }

/**
 * OAuth-style device flow: show [DeviceCode.userCode], the user approves it
 * on the service's website, we poll until we get a token.
 */
interface DeviceLogin {
    suspend fun start(): DeviceCode

    /** Null while the user hasn't approved yet. */
    suspend fun poll(code: DeviceCode): DeviceGrant?
}

data class DeviceCode(
    val userCode: String,
    val verificationUrl: String,
    /** URL with the code pre-filled, if the service offers one. */
    val directUrl: String?,
    val intervalSeconds: Int,
    val expiresAtMillis: Long,
    /** Provider-private handle (e.g. RD's device_code). */
    val handle: String
)

/** Result of an approved device login. [refresh] is stored for token renewal. */
data class DeviceGrant(
    val accessToken: String,
    val refresh: OAuthCredentials?,
    /** Set if the login already validated [accessToken] (a token that can't be fetched twice). */
    val account: AccountInfo? = null
)

data class OAuthCredentials(
    val clientId: String,
    val clientSecret: String,
    val refreshToken: String
)

/** Static, UI-facing metadata about a provider. */
data class ProviderInfo(
    val id: ProviderId,
    val displayName: String,
    /** Page where the user finds their API token/key. */
    val tokenUrl: String,
    /** Short label for [tokenUrl], shown on the Setup screen. */
    val tokenUrlLabel: String,
    /** What the credential is called by this service ("API token", "API key"). */
    val tokenLabel: String,
    /** Hint shown when the account has nothing downloadable yet. */
    val emptyListHint: String,
    /** Service home page (renewing premium, managing the account). */
    val websiteUrl: String
)

data class AccountInfo(
    val displayName: String,
    /** ISO-8601 expiry of premium, if any. */
    val premiumUntil: String?,
    /** "Premium", "Pro", "Free", ... */
    val plan: String? = null
) {
    /** Whole days of premium left (0 = expires today), or null if unknown. */
    val daysLeft: Long? get() = daysUntil(premiumUntil, System.currentTimeMillis())

    companion object {
        /** Whole days from [nowMs] to the ISO-8601 [until] (-1 once past), or null if absent or unparseable. */
        fun daysUntil(until: String?, nowMs: Long): Long? {
            until ?: return null
            val instant = runCatching { java.time.Instant.parse(until) }.getOrNull()
                ?: runCatching { java.time.OffsetDateTime.parse(until).toInstant() }.getOrNull()
                ?: return null
            val ms = instant.toEpochMilli() - nowMs
            return if (ms < 0) -1 else ms / (24L * 60 * 60 * 1000)
        }
    }
}

/**
 * A freshly minted direct link. [maxConnections] / [filesize] are null when the
 * provider doesn't report them — the engine then keeps what it already had.
 */
data class ResolvedLink(
    val url: String,
    val maxConnections: Int?,
    val filesize: Long?
)

/** An API-level failure with a message that is safe to show the user. */
open class ProviderException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The service refused a feature the account's plan doesn't include. */
class PlanRestrictedException(message: String) : ProviderException(message)

/** "Add only if cached" found nothing cached: the service added nothing. */
class NotCachedException(message: String) : ProviderException(message)

/** A job the service gave up on (error or cancelled); it will never become ready. */
class JobFailedException(message: String) : ProviderException(message)
