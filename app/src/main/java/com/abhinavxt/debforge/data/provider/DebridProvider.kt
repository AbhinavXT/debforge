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
    suspend fun add(request: AddRequest): String =
        throw ProviderException("${info.displayName} doesn't support adding ${request.kind.label}")
}

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
    val emptyListHint: String
)

data class AccountInfo(
    val displayName: String,
    val premiumUntil: String?
)

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
class ProviderException(message: String, cause: Throwable? = null) : Exception(message, cause)
