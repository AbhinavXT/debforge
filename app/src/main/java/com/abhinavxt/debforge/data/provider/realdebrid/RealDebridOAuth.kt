package com.abhinavxt.debforge.data.provider.realdebrid

import com.abhinavxt.debforge.data.prefs.TokenStore
import com.abhinavxt.debforge.data.provider.DeviceCode
import com.abhinavxt.debforge.data.provider.DeviceGrant
import com.abhinavxt.debforge.data.provider.DeviceLogin
import com.abhinavxt.debforge.data.provider.OAuthCredentials
import com.abhinavxt.debforge.domain.ProviderId
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import retrofit2.Call
import retrofit2.HttpException
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import java.io.IOException

/**
 * Real-Debrid OAuth2 device flow for open-source apps
 * (https://api.real-debrid.com/ → "Workflow for opensource apps"):
 *
 *  1. GET  device/code?client_id=<OSS id>&new_credentials=yes
 *       -> device_code, user_code, verification_url, interval, expires_in
 *  2. user enters user_code at verification_url and approves
 *  3. GET  device/credentials?client_id=<OSS id>&code=<device_code>
 *       -> user-bound client_id + client_secret (error until approved)
 *  4. POST token (client_id, client_secret, code=<device_code>, grant_type)
 *       -> access_token (expires), refresh_token
 *  Refresh: POST token again with code=<refresh_token>.
 *
 * No auth interceptor on this API: these calls authenticate by their params.
 */
interface RealDebridOAuthApi {

    @GET("device/code")
    suspend fun deviceCode(
        @Query("client_id") clientId: String = OPEN_SOURCE_CLIENT_ID,
        @Query("new_credentials") newCredentials: String = "yes"
    ): RdDeviceCodeDto

    @GET("device/credentials")
    suspend fun deviceCredentials(
        @Query("client_id") clientId: String = OPEN_SOURCE_CLIENT_ID,
        @Query("code") deviceCode: String
    ): RdDeviceCredentialsDto

    @FormUrlEncoded
    @POST("token")
    suspend fun token(
        @Field("client_id") clientId: String,
        @Field("client_secret") clientSecret: String,
        @Field("code") code: String,
        @Field("grant_type") grantType: String = DEVICE_GRANT
    ): RdTokenDto

    /** Blocking variant for the OkHttp [Authenticator] (not a coroutine). */
    @FormUrlEncoded
    @POST("token")
    fun tokenCall(
        @Field("client_id") clientId: String,
        @Field("client_secret") clientSecret: String,
        @Field("code") code: String,
        @Field("grant_type") grantType: String = DEVICE_GRANT
    ): Call<RdTokenDto>

    companion object {
        const val BASE_URL = "https://api.real-debrid.com/oauth/v2/"
        const val OPEN_SOURCE_CLIENT_ID = "X245A4XAIBGVM"
        const val DEVICE_GRANT = "http://oauth.net/grant_type/device/1.0"
    }
}

@JsonClass(generateAdapter = true)
data class RdDeviceCodeDto(
    @Json(name = "device_code") val deviceCode: String,
    @Json(name = "user_code") val userCode: String,
    @Json(name = "interval") val interval: Int?,
    @Json(name = "expires_in") val expiresIn: Int?,
    @Json(name = "verification_url") val verificationUrl: String,
    @Json(name = "direct_verification_url") val directVerificationUrl: String?
)

@JsonClass(generateAdapter = true)
data class RdDeviceCredentialsDto(
    @Json(name = "client_id") val clientId: String,
    @Json(name = "client_secret") val clientSecret: String
)

@JsonClass(generateAdapter = true)
data class RdTokenDto(
    @Json(name = "access_token") val accessToken: String,
    @Json(name = "expires_in") val expiresIn: Long?,
    @Json(name = "refresh_token") val refreshToken: String?
)

/** [DeviceLogin] for Real-Debrid. */
class RealDebridDeviceLogin(private val oauth: RealDebridOAuthApi) : DeviceLogin {

    override suspend fun start(): DeviceCode {
        val c = oauth.deviceCode()
        return DeviceCode(
            userCode = c.userCode,
            verificationUrl = c.verificationUrl,
            directUrl = c.directVerificationUrl,
            intervalSeconds = (c.interval ?: 5).coerceIn(2, 30),
            expiresAtMillis = System.currentTimeMillis() + (c.expiresIn ?: 600) * 1000L,
            handle = c.deviceCode
        )
    }

    override suspend fun poll(code: DeviceCode): DeviceGrant? {
        val creds = try {
            oauth.deviceCredentials(deviceCode = code.handle)
        } catch (e: HttpException) {
            return null // not approved yet (RD answers with an error until then)
        } catch (e: IOException) {
            return null // transient; keep polling
        }
        val token = oauth.token(creds.clientId, creds.clientSecret, code.handle)
        val refresh = token.refreshToken
        return DeviceGrant(
            accessToken = token.accessToken,
            refresh = refresh?.let { OAuthCredentials(creds.clientId, creds.clientSecret, it) }
        )
    }
}

/**
 * Renews an expired OAuth access token on HTTP 401 and retries once.
 * Only applies when the stored token came from device login (pasted API
 * tokens don't expire and have no refresh credentials).
 */
class RealDebridAuthenticator(
    private val tokenStore: TokenStore,
    private val oauth: RealDebridOAuthApi
) : Authenticator {

    private val lock = Any()

    override fun authenticate(route: Route?, response: Response): Request? {
        val request = response.request
        // Never "fix" a sign-in validation with the stored token, and never loop.
        if (request.header(VALIDATE_HEADER) != null || response.priorResponse != null) return null
        val sent = request.header("Authorization")?.removePrefix("Bearer ")?.trim() ?: return null

        synchronized(lock) {
            val current = runBlocking { tokenStore.token(ProviderId.REAL_DEBRID) } ?: return null
            // Another request already refreshed while we waited: reuse it.
            if (current != sent) return request.withToken(current)

            val creds = runBlocking { tokenStore.oauth(ProviderId.REAL_DEBRID) } ?: return null
            val result = try {
                oauth.tokenCall(creds.clientId, creds.clientSecret, creds.refreshToken).execute()
            } catch (e: IOException) {
                return null
            }
            val body = result.body()
            if (!result.isSuccessful || body == null) return null

            runBlocking {
                tokenStore.updateToken(ProviderId.REAL_DEBRID, body.accessToken)
                tokenStore.setOAuth(
                    ProviderId.REAL_DEBRID,
                    creds.copy(refreshToken = body.refreshToken ?: creds.refreshToken)
                )
            }
            return request.withToken(body.accessToken)
        }
    }

    private fun Request.withToken(token: String) =
        newBuilder().header("Authorization", "Bearer $token").build()

    companion object {
        /** Marker header on the sign-in validation call (see RealDebridApi). */
        const val VALIDATE_HEADER = "X-DebForge-Validate"
    }
}
