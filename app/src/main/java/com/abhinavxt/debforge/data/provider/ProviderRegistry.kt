package com.abhinavxt.debforge.data.provider

import com.abhinavxt.debforge.R

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

    /** Application context for localized messages; set in Application.onCreate. */
    @Volatile private var appContext: android.content.Context? = null

    fun init(context: android.content.Context) {
        appContext = context.applicationContext
    }

    /** Localized message, or [fallback] (English) before init / in JVM tests. */
    private fun msg(fallback: String, res: Int, vararg args: Any): String =
        appContext?.let { runCatching { it.getString(res, *args) }.getOrNull() } ?: fallback

    private fun tokenLabel(info: ProviderInfo): String =
        appContext?.getString(tokenLabelRes(info)) ?: info.tokenLabel

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
            DataResult.Error(e.message ?: msg("${info.displayName}: $what failed", R.string.err_failed, info.displayName), e)
        } catch (e: HttpException) {
            val text = when (e.code()) {
                401, 403 -> msg(
                    "${info.displayName} rejected your ${info.tokenLabel}. Check it at ${info.tokenUrlLabel}.",
                    R.string.err_auth, info.displayName, tokenLabel(info), info.tokenUrlLabel
                )
                429 -> msg("${info.displayName} is limiting requests", R.string.err_rate_limit, info.displayName)
                else -> msg("${info.displayName}: $what failed (HTTP ${e.code()})", R.string.err_http, info.displayName, e.code())
            }
            DataResult.Error(text, e)
        } catch (e: JsonDataException) {
            DataResult.Error(msg("Unexpected response from ${info.displayName}", R.string.err_bad_response, info.displayName), e)
        } catch (e: JsonEncodingException) {
            // Must precede IOException — JsonEncodingException extends it.
            DataResult.Error(msg("Unexpected response from ${info.displayName}", R.string.err_bad_response, info.displayName), e)
        } catch (e: IOException) {
            DataResult.Error(msg("Network error", R.string.err_network), e)
        } catch (e: Throwable) {
            // Belt-and-braces: anything unanticipated becomes a surfaced error
            // instead of a process crash.
            DataResult.Error(msg("Unexpected error: $what", R.string.err_unexpected), e)
        }
    }
}

/** Translated name of the credential a service signs in with ("API key" / "API token"). */
@androidx.annotation.StringRes
fun tokenLabelRes(info: ProviderInfo): Int =
    if (info.tokenLabel.contains("token", ignoreCase = true)) R.string.token_api_token else R.string.token_api_key

