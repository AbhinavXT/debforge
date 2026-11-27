package com.abhinavxt.debforge.data.provider.torbox

import com.abhinavxt.debforge.data.prefs.TokenStore
import com.abhinavxt.debforge.data.provider.ProviderException
import com.abhinavxt.debforge.data.provider.StreamQuality
import com.abhinavxt.debforge.data.provider.torbox.dto.TbEnvelope
import com.abhinavxt.debforge.data.provider.torbox.dto.TbStreamDto
import com.abhinavxt.debforge.domain.ProviderId
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class TorBoxStreamQualitiesTest {
    private val fixture = """
        {"success":true,"error":null,"detail":"Stream created.","data":{
          "hls_url":"https://stream.torbox.app/hls/abc/master.m3u8",
          "resolution_index":4,"is_transcoding":true,"needs_transcoding":true,
          "metadata":{"video":{"codec":"h264","width":1920,"height":1080,"fps":23.976}}}}
    """.trimIndent()

    private fun parse(json: String): TbEnvelope<TbStreamDto> {
        val type = Types.newParameterizedType(TbEnvelope::class.java, TbStreamDto::class.java)
        return Moshi.Builder().build().adapter<TbEnvelope<TbStreamDto>>(type).fromJson(json)!!
    }

    private var response: () -> TbEnvelope<TbStreamDto> = { parse(fixture) }
    private val indexes = mutableListOf<Int?>()

    private val api = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(TorBoxApi::class.java)) { _, m, args ->
        when (m.name) {
            "createStream" -> { indexes += args[3] as Int?; response() }
            "torrentLink" -> TbEnvelope(true, null, null, "https://dl.example/file.mkv")
            else -> error(m.name)
        }
    } as TorBoxApi
    private val deviceApi = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(TorBoxDeviceApi::class.java)) { m, _, _ ->
        error(m)
    } as TorBoxDeviceApi

    init { com.abhinavxt.debforge.data.prefs.TokenCipher.testKey = javax.crypto.spec.SecretKeySpec(ByteArray(16), "AES") }
    private val tokens = TokenStore(RuntimeEnvironment.getApplication())
    private val provider = TorBoxProvider(api, deviceApi, tokens)
    private val ref = "torrent:7:3"

    private fun http(code: Int) =
        retrofit2.HttpException(retrofit2.Response.error<Any>(code, okhttp3.ResponseBody.create(null, "")))

    @Test fun dtoParsesFixture() {
        val d = parse(fixture).data!!
        assertEquals("https://stream.torbox.app/hls/abc/master.m3u8", d.hlsUrl)
        assertEquals(4, d.resolutionIndex)
        assertEquals(true, d.isTranscoding)
        assertEquals(1080, d.videoHeight)
    }

    @Test fun dtoToleratesMissingFields() {
        assertNull(parse("""{"success":true,"data":{"hls_url":null}}""").data!!.videoHeight)
        assertNull(parse("""{"success":true,"data":{"metadata":{"video":{"height":null}}}}""").data!!.videoHeight)
        assertEquals(720, parse("""{"success":true,"data":{"metadata":{"video":[{"height":720}]}}}""").data!!.videoHeight)
    }

    @Test fun source1080OffersOriginalAndLower() = runBlocking {
        val q = provider.streamQualities(ref)
        assertEquals(listOf("original", "720", "480", "360", "240", "144"), q.map { it.id })
        assertEquals(listOf("Original", "720p", "480p", "360p", "240p", "144p"), q.map { it.label })
        assertEquals(1080, q.first().height)
        assertEquals(listOf<Int?>(null), indexes)
    }

    @Test fun unknownHeightOffersCommonThree() = runBlocking {
        response = { parse("""{"success":true,"data":{"hls_url":"u"}}""") }
        val q = provider.streamQualities(ref)
        assertEquals(listOf("original", "1080", "720", "480"), q.map { it.id })
        assertNull(q.first().height)
    }

    @Test fun refusalHidesQualities() = runBlocking {
        response = { TbEnvelope(false, "PLAN_RESTRICTED_FEATURE", "Pro only", null) }
        assertEquals(emptyList<StreamQuality>(), provider.streamQualities(ref))
        response = { throw http(403) }
        assertEquals(emptyList<StreamQuality>(), provider.streamQualities(ref))
        response = { throw http(500) }
        assertThrows(retrofit2.HttpException::class.java) { runBlocking { provider.streamQualities(ref) } }
        Unit
    }

    @Test fun streamUrlMapsHeightToIndex() = runBlocking {
        for ((height, index) in listOf(144 to 0, 480 to 3, 720 to 4, 1080 to 5)) {
            assertEquals(
                "https://stream.torbox.app/hls/abc/master.m3u8",
                provider.streamUrl(ref, StreamQuality(height.toString(), "${height}p", height))
            )
            assertEquals(index, indexes.last())
        }
    }

    @Test fun originalKeepsDirectLink() = runBlocking {
        tokens.setToken(ProviderId.TORBOX, "k")
        assertEquals("https://dl.example/file.mkv", provider.streamUrl(ref, StreamQuality("original", "Original", 1080)))
        assertEquals(emptyList<Int?>(), indexes)
    }

    @Test fun unknownQualityThrows() {
        assertThrows(ProviderException::class.java) { runBlocking { provider.streamUrl(ref, StreamQuality("999", "x", 999)) } }
    }
}
