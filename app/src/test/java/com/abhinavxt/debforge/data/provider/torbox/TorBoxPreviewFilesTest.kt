package com.abhinavxt.debforge.data.provider.torbox

import com.abhinavxt.debforge.data.prefs.TokenStore
import com.abhinavxt.debforge.data.provider.torbox.dto.TbEnvelope
import com.abhinavxt.debforge.domain.TorrentEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class TorBoxPreviewFilesTest {
    private var data: Any? = null
    private var failure: Throwable? = null
    private var listFiles: Any? = null

    private val api = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(TorBoxApi::class.java)) { _, m, args ->
        when (m.name) {
            "checkCached" -> {
                listFiles = args[2]
                failure?.let { throw it }
                TbEnvelope(true, null, null, data)
            }
            else -> error(m.name)
        }
    } as TorBoxApi
    private val deviceApi = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(TorBoxDeviceApi::class.java)) { _, m, _ -> error(m.name) } as TorBoxDeviceApi

    init { com.abhinavxt.debforge.data.prefs.TokenCipher.testKey = javax.crypto.spec.SecretKeySpec(ByteArray(16), "AES") }
    private val provider = TorBoxProvider(api, deviceApi, TokenStore(RuntimeEnvironment.getApplication()))

    private fun preview() = runBlocking { provider.previewFiles("abc") }

    @Test fun cachedWithFiles() {
        data = listOf(mapOf("hash" to "abc", "files" to listOf(mapOf("name" to "T/a.mkv", "size" to 10.0), mapOf("path" to "T/b.nfo", "size" to 2.0))))
        assertEquals(listOf(TorrentEntry("T/a.mkv", 10), TorrentEntry("T/b.nfo", 2)), preview())
        assertEquals(true, listFiles)
    }

    @Test fun objectFormatKeyedByHash() {
        data = mapOf("ABC" to mapOf("files" to listOf(mapOf("short_name" to "a.mkv", "size" to 5.0))))
        assertEquals(listOf(TorrentEntry("a.mkv", 5)), preview())
    }

    @Test fun missingSizeIsZeroAndNamelessSkipped() {
        data = listOf(mapOf("hash" to "abc", "files" to listOf(mapOf("name" to "a"), mapOf("size" to 1.0), "junk")))
        assertEquals(listOf(TorrentEntry("a", 0)), preview())
    }

    @Test fun notCachedIsNull() {
        data = null
        assertNull(preview())
        data = emptyList<Any>()
        assertNull(preview())
    }

    @Test fun oddShapesAreNull() {
        data = listOf(mapOf("hash" to "abc", "files" to "nope"))
        assertNull(preview())
        data = listOf(mapOf("hash" to "abc"))
        assertNull(preview())
        data = listOf(mapOf("hash" to "other", "files" to listOf(mapOf("name" to "x", "size" to 1.0))))
        assertNull(preview())
        data = "weird"
        assertNull(preview())
    }

    @Test fun failureIsNullNotThrown() {
        failure = IOException("down")
        assertNull(preview())
    }
}
