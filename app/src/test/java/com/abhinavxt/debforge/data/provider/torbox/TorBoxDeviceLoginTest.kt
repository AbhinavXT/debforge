package com.abhinavxt.debforge.data.provider.torbox

import com.abhinavxt.debforge.data.provider.AccountInfo
import com.abhinavxt.debforge.data.provider.DeviceCode
import com.abhinavxt.debforge.data.provider.ProviderException
import com.abhinavxt.debforge.di.ProviderModule
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import java.io.IOException

/** Runs the real Retrofit API over a client whose network is [answers]. */
class TorBoxDeviceLoginTest {

    private val requests = mutableListOf<Request>()
    /** (HTTP status, body), or null for no network. */
    private val answers = ArrayDeque<Pair<Int, String>?>()
    private val validated = mutableListOf<String>()
    private val validateFailures = ArrayDeque<Exception>()

    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        requests += chain.request()
        val (status, body) = answers.removeFirst() ?: throw IOException("offline")
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("x")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }.build()

    private val api = ProviderModule.provideTorBoxDeviceApi(client, Moshi.Builder().build())
    private val account = AccountInfo("me", null)

    private val login = TorBoxDeviceLogin(api) {
        validated += it
        validateFailures.removeFirstOrNull()?.let { e -> throw e }
        account
    }

    private val code = DeviceCode("123456", "u", null, 0, Long.MAX_VALUE, "dev")
    private val granted =
        200 to """{"success":true,"error":null,"detail":"d","data":{"access_token":"tok","token_type":"Bearer"}}"""

    private fun fail(status: Int, error: String) =
        status to """{"success":false,"error":"$error","detail":"d","data":null}"""

    private fun poll() = runBlocking { login.poll(code) }

    private fun start(data: String): DeviceCode {
        answers += 200 to """{"success":true,"error":null,"detail":"d","data":$data}"""
        return runBlocking { login.start() }
    }

    private fun http(status: Int) =
        HttpException(retrofit2.Response.error<Any>(status, "{}".toResponseBody()))

    @Test fun startReadsTheCode() {
        val c = start(
            """{"device_code":"dev","code":"123456","interval":5,"expires_in":600,"expires_at":"2026-01-01T00:00:00Z",
            "verification_url":"https://torbox.app/oauth/device?app=DebForge",
            "verification_url_complete":"https://torbox.app/oauth/device?app=DebForge&code=123456",
            "friendly_verification_url":"https://tor.box/link"}"""
        )
        assertEquals("123456", c.userCode)
        assertEquals("dev", c.handle)
        assertEquals("https://torbox.app/oauth/device", c.verificationUrl)
        assertEquals("https://torbox.app/oauth/device?app=DebForge&code=123456", c.directUrl)
        assertEquals(5, c.intervalSeconds)
        assertEquals(600.0, (c.expiresAtMillis - System.currentTimeMillis()) / 1000.0, 5.0)
        assertEquals("https://api.torbox.app/v1/api/user/auth/device/start?app=DebForge", requests.single().url.toString())
        assertNull(requests.single().header("Authorization"))
    }

    @Test fun clampsIntervalAndExpiry() {
        fun secondsLeft(c: DeviceCode) = (c.expiresAtMillis - System.currentTimeMillis()) / 1000.0
        val zero = start("""{"device_code":"d","code":"1","interval":0,"expires_in":0}""")
        assertEquals(5, zero.intervalSeconds)
        assertEquals(600.0, secondsLeft(zero), 5.0)
        val huge = start("""{"device_code":"d","code":"1","interval":9999,"expires_in":999999}""")
        assertEquals(30, huge.intervalSeconds)
        assertEquals(600.0, secondsLeft(huge), 5.0)
        val missing = start("""{"device_code":"d","code":"1"}""")
        assertEquals(5, missing.intervalSeconds)
        assertEquals(600.0, secondsLeft(missing), 5.0)
        val short = start("""{"device_code":"d","code":"1","interval":-3,"expires_in":1}""")
        assertEquals(5, short.intervalSeconds)
        assertEquals(60.0, secondsLeft(short), 5.0)
    }

    @Test fun startWithoutCodesFails() {
        assertThrows(ProviderException::class.java) { start("""{"code":"1"}""") }
        assertThrows(ProviderException::class.java) { start("""{"device_code":"d","code":""}""") }
        assertThrows(ProviderException::class.java) { start("null") }
    }

    @Test fun onlyTorBoxPagesAreShownOrOpened() {
        listOf(
            null, "", "nonsense", "http://torbox.app/x", "javascript:alert(1)", "intent://torbox.app",
            "https://evil.example/oauth", "https://torbox.app.evil.example/", "https://eviltorbox.app/",
            "https://torbox.app@evil.example/", "https://user:pw@torbox.app/", "https://torbox.app:8443/",
            "https://evil.example\\@torbox.app/"
        ).forEach { assertNull(it, TorBoxDeviceLogin.torBoxUrl(it)) }
        assertEquals("https://tor.box/link", TorBoxDeviceLogin.torBoxUrl("https://tor.box/link"))

        val c = start(
            """{"device_code":"d","code":"1","verification_url":"http://torbox.app/x",
            "verification_url_complete":"https://evil.example/","friendly_verification_url":"https://evil.example/"}"""
        )
        assertEquals("https://torbox.app/oauth/device", c.verificationUrl)
        assertEquals("https://torbox.app/oauth/device", c.directUrl)
    }

    @Test fun pendingThenApproved() {
        answers += listOf(fail(400, "DEVICE_CODE_NOT_USED"), fail(200, "DEVICE_CODE_NOT_USED"), granted)
        assertNull(poll())
        assertNull(poll())
        val grant = poll()!!
        assertEquals("tok", grant.accessToken)
        assertNull(grant.refresh)
        assertEquals(listOf("tok"), validated)
        requests.forEach {
            assertNull(it.header("Authorization"))
            assertEquals("https://api.torbox.app/v1/api/user/auth/device/token", it.url.toString())
            assertEquals("""{"device_code":"dev"}""", Buffer().also { b -> it.body!!.writeTo(b) }.readUtf8())
        }
    }

    @Test fun transientFailuresKeepPolling() {
        answers += listOf(
            null, fail(429, "RATE_LIMIT_EXCEEDED"), fail(500, "UNKNOWN_ERROR"), 503 to "busy", 408 to "",
            403 to "<html>blocked</html>", 200 to "<html>portal</html>", 200 to "{}",
            200 to """{"success":true,"data":{}}""", 422 to """{"detail":[]}""", granted
        )
        repeat(10) { assertNull(poll()) }
        assertEquals("tok", poll()!!.accessToken)
    }

    @Test fun deadCodesStop() {
        listOf(
            fail(404, "ITEM_NOT_FOUND"), fail(400, "DEVICE_CODE_USED"), fail(400, "DEVICE_CODE_EXPIRED"),
            fail(403, "ACCESS_DENIED"), fail(400, "ACCESS_DENIED"), fail(200, "ITEM_NOT_FOUND"), fail(401, "BAD_TOKEN")
        ).forEach {
            answers += it
            assertThrows(ProviderException::class.java) { poll() }
        }
        assertTrue(validated.isEmpty())
    }

    @Test fun networkBlipWhileValidatingDoesNotLoseTheToken() {
        answers += granted
        validateFailures += listOf(IOException("offline"), http(429), http(503))
        val grant = poll()!!
        assertEquals("tok", grant.accessToken)
        assertEquals("already validated, so it isn't checked again", account, grant.account)
        assertEquals("redeemed once", 1, requests.size)
        assertEquals(4, validated.size)
    }

    @Test fun rejectedTokenIsNotGranted() {
        answers += granted
        validateFailures += http(401)
        assertThrows(HttpException::class.java) { poll() }
        answers += granted
        validateFailures += ProviderException("no")
        assertThrows(ProviderException::class.java) { poll() }
        assertEquals("a refusal isn't retried", 2, validated.size)
    }

    @Test fun validatingIsBoundedInTime() {
        answers += granted
        val stuck = TorBoxDeviceLogin(api, validateForMillis = 50) { delay(60_000); account }
        assertThrows(ProviderException::class.java) { runBlocking { stuck.poll(code) } }
    }

    @Test fun cancellingStopsTheRetries() = runBlocking {
        answers += granted
        repeat(1000) { validateFailures += IOException("offline") }
        val job = launch(Dispatchers.IO) { login.poll(code.copy(intervalSeconds = 1)) }
        withTimeout(5_000) { while (validated.isEmpty()) delay(10) }
        job.cancelAndJoin()
        assertEquals(1, validated.size)
    }

    @Test fun overlappingSignInsKeepTheirOwnToken() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val slow = TorBoxDeviceLogin(api) { gate.await(); account }
        answers += granted
        val first = async(Dispatchers.IO) { slow.poll(code) }
        withTimeout(5_000) { while (requests.isEmpty()) delay(10) }
        answers += 200 to """{"success":true,"error":null,"detail":"d","data":{"device_code":"dev2","code":"1"}}"""
        slow.start()
        gate.complete(Unit)
        assertEquals("tok", first.await()!!.accessToken)
    }
}
