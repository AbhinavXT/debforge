package com.abhinavxt.debforge.data.provider.torbox

import com.abhinavxt.debforge.data.prefs.TokenStore
import com.abhinavxt.debforge.data.provider.JobAction
import com.abhinavxt.debforge.data.provider.ProviderException
import com.abhinavxt.debforge.data.provider.torbox.dto.TbEnvelope
import com.abhinavxt.debforge.data.provider.torbox.dto.TbFileDto
import com.abhinavxt.debforge.data.provider.torbox.dto.TbItemDto
import com.abhinavxt.debforge.data.provider.torbox.dto.TbQueuedControl
import com.abhinavxt.debforge.data.provider.torbox.dto.TbQueuedDto
import com.abhinavxt.debforge.data.provider.torbox.dto.TbTorrentControl
import com.abhinavxt.debforge.domain.AddRequest
import com.squareup.moshi.JsonDataException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.io.IOException
import java.lang.reflect.Proxy
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class TorBoxQueuedTest {
    private val torrents = mutableListOf<TbItemDto>()
    private val queued = mutableListOf<TbQueuedDto>()
    private var queuedFailure: Throwable? = null
    private var queuedReads = 0
    private val queuedPageLimits = mutableListOf<Int>()
    private val controls = mutableListOf<TbQueuedControl>()
    private val torrentControls = mutableListOf<TbTorrentControl>()
    private var addData: Any = mapOf("queued_id" to 9.0, "hash" to "ABCDEF")

    private val api = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(TorBoxApi::class.java)) { _, m, args ->
        when (m.name) {
            "torrents" -> TbEnvelope(true, null, null, torrents.drop(args[0] as Int).take(args[1] as Int))
            "usenet", "webDownloads" -> TbEnvelope<List<TbItemDto>>(true, null, null, emptyList())
            "queued" -> {
                queuedPageLimits += args[2] as Int
                if (args[0] == "torrent") queuedReads++
                queuedFailure?.let { throw it }
                val rows = if (args[0] == "torrent") queued.drop(args[1] as Int).take(args[2] as Int) else emptyList()
                TbEnvelope(true, null, null, rows)
            }
            "controlQueued" -> {
                val c = args[0] as TbQueuedControl
                controls += c
                queued.removeAll { it.id == c.queuedId }
                TbEnvelope<Any>(true, null, null, null)
            }
            "controlTorrent" -> {
                torrentControls += args[0] as TbTorrentControl
                TbEnvelope<Any>(true, null, null, null)
            }
            "createTorrentFromMagnet" -> TbEnvelope(true, null, "Added to queue.", addData)
            else -> error(m.name)
        }
    } as TorBoxApi

    private val deviceApi = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(TorBoxDeviceApi::class.java)) { _, m, _ ->
        error(m.name)
    } as TorBoxDeviceApi

    private val provider = TorBoxProvider(api, deviceApi, TokenStore(RuntimeEnvironment.getApplication()))

    private fun q(id: Long, hash: String = "abcdef") = TbQueuedDto(id = id, hash = hash, name = "q$id")
    private fun file() = listOf(TbFileDto(1, "a.mkv", "a.mkv", 1, null))
    private fun later(s: Long) = ShadowSystemClock.advanceBy(Duration.ofSeconds(s))

    @Test fun queuedItemsListedAsQueuedWithStartNow() = runBlocking {
        queued += q(9)
        val job = provider.listProcessing().single()
        assertEquals("queued:9:abcdef", job.ref)
        assertEquals("queued", job.status)
        assertEquals(setOf(JobAction.START_NOW), job.actions)
    }

    @Test fun queuedFailuresNeverBreakTheList() = runBlocking {
        torrents += TbItemDto(1, "t", 1, null, false, false, null, downloadState = "downloading")
        for (failure in listOf(IOException("x"), JsonDataException("y"), IllegalStateException("z"))) {
            queuedFailure = failure
            later(31)
            assertEquals(listOf("torrent:1"), provider.listProcessing().map { it.ref })
        }
        queuedFailure = retrofit2.HttpException(retrofit2.Response.error<Any>(401, okhttp3.ResponseBody.create(null, "")))
        later(31)
        assertEquals(1, provider.listProcessing().size)
        assertEquals(0, provider.listFiles(1, 50).items.size)
    }

    @Test fun malformedQueuedRowsAreSkippedAndPagesStaySmall() = runBlocking {
        queued += TbQueuedDto(id = null) // no id: not actionable
        queued += q(3)
        assertEquals(listOf("queued:3:abcdef"), provider.listProcessing().map { it.ref })
        assertTrue(queuedPageLimits.all { it <= 100 })
    }

    @Test fun queueIsReadAtMostEvery30sUnlessChanged() = runBlocking {
        queued += q(9)
        provider.listProcessing()
        later(5); provider.listProcessing()
        later(5); provider.listProcessing()
        assertEquals(1, queuedReads)
        later(30); provider.listProcessing()
        assertEquals(2, queuedReads)
        // an add that came back queued reads at once
        later(1)
        provider.add(AddRequest.Magnet("magnet:?xt=urn:btih:abcdef"))
        later(1); provider.listProcessing()
        assertEquals(3, queuedReads)
        // so does start / remove
        provider.controlJob("queued:9:abcdef", JobAction.START_NOW)
        later(1); provider.listProcessing()
        assertEquals(4, queuedReads)
    }

    @Test fun addReturnsQueuedRefAndOtherAddsKeepTheirs() = runBlocking {
        assertEquals("queued:9:ABCDEF", provider.add(AddRequest.Magnet("m")).jobRef)
        addData = mapOf("torrent_id" to 7.0)
        assertEquals("torrent:7", provider.add(AddRequest.Magnet("m")).jobRef)
    }

    @Test fun startAndRemoveCallControlQueued() = runBlocking {
        queued += q(9); queued += q(10)
        provider.listProcessing()
        provider.controlJob("queued:9:abcdef", JobAction.START_NOW)
        provider.remove("queued:10:abcdef")
        assertEquals(listOf("start" to 9L, "delete" to 10L), controls.map { it.operation to it.queuedId })
        assertTrue(provider.listProcessing().isEmpty())
    }

    @Test fun refsOfOtherKindsAreNotQueuedAndBadOnesThrow() {
        assertNull(provider.removalKey("queued:9:abcdef"))
        assertNull(provider.zipBundle("queued:9:abcdef", "n", 1))
        runBlocking {
            try { provider.controlJob("queued:x:", JobAction.START_NOW); fail() } catch (_: ProviderException) {}
            try { provider.remove("queued:9"); fail() } catch (_: ProviderException) {}
        }
    }

    @Test fun downloadWhenReadyAcrossQueuedDownloadingReady() = runBlocking {
        queued += q(9, "abcdef")
        val ref = "queued:9:abcdef"
        assertNull(provider.filesForJob(ref)) // still queued
        // TorBox started it: no longer queued, downloading
        queued.clear()
        torrents += TbItemDto(5, "t", 1, null, false, false, null, downloadState = "downloading", hash = "ABCDEF")
        later(31)
        assertNull(provider.filesForJob(ref))
        // ready (case differs)
        torrents.clear()
        torrents += TbItemDto(5, "t", 1, null, true, true, file(), hash = "AbCdEf")
        later(31)
        val files = provider.filesForJob(ref)!!
        assertEquals(listOf("tb:torrent:5:1"), files.map { it.id })
        assertEquals(files.map { it.id }, provider.itemIdsIn(ref))
    }

    @Test fun vanishedQueuedItemBehavesLikeADeletedJob() = runBlocking {
        queued += q(9)
        assertNull(provider.filesForJob("queued:9:abcdef"))
        queued.clear()
        later(31)
        try { provider.filesForJob("queued:9:abcdef"); fail() } catch (_: ProviderException) {}
        try { provider.filesForJob("queued:9:"); fail() } catch (_: ProviderException) {}
    }

    @Test fun unreadableQueueKeepsWaitingInsteadOfGivingUp() = runBlocking {
        queuedFailure = IOException("offline")
        assertNull(provider.filesForJob("queued:9:abcdef"))
    }

    @Test fun stalledGetsReannounceAndPausedGetsResume() = runBlocking {
        torrents += TbItemDto(1, "a", 1, null, false, false, null, downloadState = "stalled (no seeds)")
        torrents += TbItemDto(2, "b", 1, null, false, false, null, downloadState = "paused")
        torrents += TbItemDto(3, "c", 1, null, false, false, null, downloadState = "downloading")
        val jobs = provider.listProcessing().associateBy { it.ref }
        assertEquals(setOf(JobAction.REANNOUNCE), jobs.getValue("torrent:1").actions)
        assertEquals(setOf(JobAction.RESUME), jobs.getValue("torrent:2").actions)
        assertTrue(jobs.getValue("torrent:3").actions.isEmpty())
        provider.controlJob("torrent:1", JobAction.REANNOUNCE)
        provider.controlJob("torrent:2", JobAction.RESUME)
        assertEquals(listOf("reannounce", "resume"), torrentControls.map { it.operation })
    }
}
