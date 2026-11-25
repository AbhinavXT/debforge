package com.abhinavxt.debforge.data.provider.torbox

import com.abhinavxt.debforge.data.provider.AccountInfo
import com.abhinavxt.debforge.data.provider.DeviceCode
import com.abhinavxt.debforge.data.provider.DeviceGrant
import com.abhinavxt.debforge.data.provider.DeviceLogin
import com.abhinavxt.debforge.data.provider.ProviderException
import com.abhinavxt.debforge.data.provider.torbox.dto.TbEnvelope
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.JsonDataException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import retrofit2.HttpException
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import java.io.IOException

/**
 * TorBox device sign-in. Separate from [TorBoxApi] because these calls must
 * never carry the stored token: this one is built without an AuthInterceptor.
 */
interface TorBoxDeviceApi {

    @GET("user/auth/device/start")
    suspend fun start(@Query("app") app: String = "DebForge"): TbEnvelope<TbDeviceCodeDto>

    /** Answers with an error (DEVICE_CODE_NOT_USED) until the user approves. */
    @POST("user/auth/device/token")
    suspend fun token(@Body body: TbDeviceTokenRequest): TbEnvelope<TbDeviceTokenDto>
}

@JsonClass(generateAdapter = true)
data class TbDeviceCodeDto(
    @Json(name = "device_code") val deviceCode: String? = null,
    @Json(name = "code") val code: String? = null,
    @Json(name = "interval") val interval: Int? = null,
    @Json(name = "expires_in") val expiresIn: Int? = null,
    @Json(name = "verification_url") val verificationUrl: String? = null,
    @Json(name = "verification_url_complete") val verificationUrlComplete: String? = null,
    @Json(name = "friendly_verification_url") val friendlyVerificationUrl: String? = null
)

@JsonClass(generateAdapter = true)
data class TbDeviceTokenRequest(
    @Json(name = "device_code") val deviceCode: String
)

@JsonClass(generateAdapter = true)
data class TbDeviceTokenDto(
    @Json(name = "access_token") val accessToken: String? = null
)

/**
 * [DeviceLogin] for TorBox. [validate] checks a token against user/me: TorBox
 * hands the token out only once, so a network failure there is retried (for
 * up to [validateForMillis]) instead of losing the token.
 */
class TorBoxDeviceLogin(
    private val api: TorBoxDeviceApi,
    private val validateForMillis: Long = 5 * 60_000L,
    private val validate: suspend (String) -> AccountInfo
) : DeviceLogin {

    override suspend fun start(): DeviceCode {
        val r = api.start()
        val d = r.data
        val handle = d?.deviceCode
        val userCode = d?.code
        if (!r.success || handle.isNullOrBlank() || userCode.isNullOrBlank()) {
            throw ProviderException("TorBox: couldn't start sign-in. Try again.")
        }
        val page = torBoxUrl(d.verificationUrl)
        return DeviceCode(
            userCode = userCode,
            // Not the short tor.box address: some ISPs block it (Airtel India, measured 2026-11-26).
            verificationUrl = page?.substringBefore('?') ?: torBoxUrl(d.friendlyVerificationUrl) ?: FALLBACK_URL,
            directUrl = torBoxUrl(d.verificationUrlComplete) ?: page ?: FALLBACK_URL,
            intervalSeconds = (d.interval ?: 0).coerceIn(5, 30),
            // TorBox's codes last 10 minutes; its docs show 0 here.
            expiresAtMillis = System.currentTimeMillis() +
                (d.expiresIn?.takeIf { it > 0 } ?: 600).coerceIn(60, 600) * 1000L,
            handle = handle
        )
    }

    override suspend fun poll(code: DeviceCode): DeviceGrant? {
        val token = try {
            redeem(code.handle) ?: return null
        } catch (e: IOException) {
            return null // transient; keep polling
        } catch (e: JsonDataException) {
            return null // not TorBox's answer (e.g. a proxy's)
        }
        // The token lives only in this call: cancelling, failing or succeeding drops it.
        val account = withTimeoutOrNull(validateForMillis) { validateWithRetry(token, code.intervalSeconds) }
            ?: throw ProviderException("TorBox: couldn't reach the service to finish signing in. Try again.")
        // TorBox hands out the account's API token, which doesn't expire.
        return DeviceGrant(accessToken = token, refresh = null, account = account)
    }

    private suspend fun validateWithRetry(token: String, intervalSeconds: Int): AccountInfo {
        while (true) {
            try {
                return validate(token)
            } catch (e: IOException) {
                // no network, or a captive portal's page: retry
            } catch (e: HttpException) {
                if (!e.isTransient()) throw e
            }
            delay(intervalSeconds * 1000L)
        }
    }

    /** The token, or null if not approved yet. Throws [ProviderException] once the code is dead. */
    private suspend fun redeem(deviceCode: String): String? {
        val r = try {
            api.token(TbDeviceTokenRequest(deviceCode))
        } catch (e: HttpException) {
            if (e.isTransient()) return null
            val error = ERROR_CODE.find(e.response()?.errorBody()?.string().orEmpty())?.groupValues?.get(1)
            // Only TorBox's own refusals end the sign-in, not a proxy's error page.
            stopIfDead(error, refused = error != null && e.code() in REFUSED)
            return null
        }
        if (!r.success) {
            stopIfDead(r.error, refused = false)
            return null
        }
        return r.data?.accessToken?.takeIf { it.isNotBlank() }
    }

    /** Unknown errors keep polling until the code expires. */
    private fun stopIfDead(error: String?, refused: Boolean) {
        val code = error.orEmpty().uppercase()
        // The answer while waiting: never ends the sign-in, whatever the status.
        if (code == "DEVICE_CODE_NOT_USED" || "PENDING" in code) return
        if ("DENIED" in code || "DECLINED" in code) {
            throw ProviderException("TorBox: the sign-in was declined. Try again.")
        }
        if (refused || code == "DEVICE_CODE_USED" || code == "ITEM_NOT_FOUND" || "EXPIRED" in code) {
            throw ProviderException("TorBox: the code expired or was already used. Try again.")
        }
    }

    private fun HttpException.isTransient() = code() == 408 || code() == 429 || code() >= 500

    companion object {
        const val FALLBACK_URL = "https://torbox.app/oauth/device"
        private val HOSTS = setOf("torbox.app", "tor.box")
        private val REFUSED = setOf(401, 403, 404, 410)
        private val ERROR_CODE = Regex(""""error"\s*:\s*"([^"]+)"""")

        /** [url] if it is an https page on TorBox's own hosts, else null. */
        internal fun torBoxUrl(url: String?): String? = url?.toHttpUrlOrNull()
            ?.takeIf { it.isHttps && it.port == 443 && it.host in HOSTS && it.username.isEmpty() && it.password.isEmpty() }
            ?.toString()
    }
}
