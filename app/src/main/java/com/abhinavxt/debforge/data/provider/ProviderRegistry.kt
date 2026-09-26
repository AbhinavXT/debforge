package com.abhinavxt.debforge.data.provider

import com.abhinavxt.debforge.domain.DataResult
import com.abhinavxt.debforge.domain.ProviderId
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonEncodingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * All providers bound via Hilt multibinding (see ProviderModule). Lookup is by
 * [ProviderId], which is what gets persisted on queued downloads — so a file
 * queued from Real-Debrid is still refreshed through Real-Debrid even if the
 * user has since switched the active service to TorBox.
 */
@Singleton
class ProviderRegistry @Inject constructor(
    providers: Set<@JvmSuppressWildcards DebridProvider>
) {
    private val byId: Map<ProviderId, DebridProvider> = providers.associateBy { it.info.id }

    /** Stable display order = enum order. */
    val all: List<DebridProvider> = ProviderId.entries.mapNotNull { byId[it] }

    operator fun get(id: ProviderId): DebridProvider =
        byId[id] ?: error("No DebridProvider bound for $id — add it to ProviderModule")
}

/**
 * Runs a provider call on IO and converts every failure mode into a
 * [DataResult.Error] with a message tailored to that provider. Cancellation is
 * always rethrown so pause/cancel keeps working.
 */
object ProviderCalls {

    suspend fun <T> run(
        info: ProviderInfo,
        what: String,
        block: suspend () -> T
    ): DataResult<T> = withContext(Dispatchers.IO) {
        try {
            DataResult.Success(block())
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: ProviderException) {
            DataResult.Error(e.message ?: "${info.displayName}: $what failed", e)
        } catch (e: HttpException) {
            val msg = when (e.code()) {
                401, 403 -> "${info.displayName} rejected the ${info.tokenLabel.lowercase()} — " +
                    "check it at ${info.tokenUrlLabel}"
                429 -> "${info.displayName} rate limit hit — wait a minute and retry"
                else -> "${info.displayName}: $what failed (HTTP ${e.code()})"
            }
            DataResult.Error(msg, e)
        } catch (e: JsonDataException) {
            DataResult.Error("Unexpected response from ${info.displayName}", e)
        } catch (e: JsonEncodingException) {
            // Must precede IOException — JsonEncodingException extends it.
            DataResult.Error("Unexpected response from ${info.displayName}", e)
        } catch (e: IOException) {
            DataResult.Error("Network error — check your connection", e)
        } catch (e: Throwable) {
            // Belt-and-braces: anything unanticipated becomes a surfaced error
            // instead of a process crash.
            DataResult.Error("Unexpected error: $what", e)
        }
    }
}
