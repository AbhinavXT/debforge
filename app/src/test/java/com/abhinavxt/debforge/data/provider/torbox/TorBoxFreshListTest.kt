package com.abhinavxt.debforge.data.provider.torbox

import com.abhinavxt.debforge.data.prefs.TokenStore
import com.abhinavxt.debforge.data.provider.torbox.dto.TbEnvelope
import com.abhinavxt.debforge.data.provider.torbox.dto.TbFileDto
import com.abhinavxt.debforge.data.provider.torbox.dto.TbItemDto
import com.abhinavxt.debforge.domain.AddRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.lang.reflect.Proxy
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class TorBoxFreshListTest {
    private val server = mutableListOf<TbItemDto>()
    private val limits = mutableListOf<Int>()

    private val api = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(TorBoxApi::class.java)) { _, m, args ->
        when (m.name) {
            "torrents" -> {
                val (offset, limit) = args[0] as Int to args[1] as Int
                limits += limit
                TbEnvelope(true, null, null, server.drop(offset).take(limit))
            }
            "usenet", "webDownloads" -> TbEnvelope<List<TbItemDto>>(true, null, null, emptyList())
            "createTorrentFromMagnet" -> {
                server.add(0, item(7))
                TbEnvelope<Any>(true, null, null, mapOf("torrent_id" to 7.0))
            }
            "controlTorrent" -> {
                server.clear()
                TbEnvelope<Any>(true, null, null, null)
            }
            else -> error(m.name)
        }
    } as TorBoxApi

    private val provider = TorBoxProvider(api, TokenStore(RuntimeEnvironment.getApplication()))

    private fun item(id: Long) = TbItemDto(
        id, "t$id", 1, "2026-11-25T00:00:00Z", true, true, listOf(TbFileDto(1, "a.mkv", "a.mkv", 1, null))
    )

    @Test fun addAndRemoveShowRightAfterAnotherRead() = runBlocking {
        assertEquals(0, provider.listFiles(1, 50).items.size)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        provider.add(AddRequest.Magnet("magnet:?xt=urn:btih:x"))
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        assertEquals(1, provider.listFiles(1, 50).items.size)
        provider.remove("torrent:7")
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        assertEquals(0, provider.listFiles(1, 50).items.size)
    }

    @Test fun readsCloseTogetherShareOneRequest() = runBlocking {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        provider.listFiles(1, 50)
        provider.listProcessing()
        assertEquals(1, limits.size)
    }

    @Test fun readsEverythingWithSmallPagesFirst() = runBlocking {
        server += (1L..1150L).map(::item)
        assertEquals(1150, provider.listFiles(1, 5000).items.size)
        assertTrue(limits.take(10).all { it <= 100 })
        assertEquals(11, limits.size)
    }
}
