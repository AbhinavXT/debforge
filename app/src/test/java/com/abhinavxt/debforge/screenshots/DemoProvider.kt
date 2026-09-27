package com.abhinavxt.debforge.screenshots

import com.abhinavxt.debforge.data.local.DownloadEntity
import com.abhinavxt.debforge.data.provider.AccountInfo
import com.abhinavxt.debforge.data.provider.DebridProvider
import com.abhinavxt.debforge.data.provider.ProviderInfo
import com.abhinavxt.debforge.data.provider.RemoteJob
import com.abhinavxt.debforge.data.provider.ResolvedLink
import com.abhinavxt.debforge.di.ProviderBindings
import com.abhinavxt.debforge.domain.AddKind
import com.abhinavxt.debforge.domain.DownloadItem
import com.abhinavxt.debforge.domain.DownloadState
import com.abhinavxt.debforge.domain.Page
import com.abhinavxt.debforge.domain.ProviderId
import dagger.Binds
import dagger.Module
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import dagger.multibindings.IntoSet
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A fake "TorBox" with a library of Blender open movies (CC-BY) and one
 * invented series, so store screenshots show the real UI without a real
 * account or network. Screenshot tests only.
 */
@Singleton
class DemoProvider @Inject constructor() : DebridProvider {

    override val info = ProviderInfo(
        id = ProviderId.TORBOX,
        displayName = "TorBox",
        tokenUrl = "https://torbox.app/settings",
        tokenUrlLabel = "torbox.app/settings",
        tokenLabel = "API key",
        emptyListHint = "",
        websiteUrl = "https://torbox.app/"
    )

    override suspend fun validateToken(token: String) = account()

    override suspend fun account() = AccountInfo(
        displayName = "demo@example.com",
        premiumUntil = Instant.now().plusSeconds(86_400L * 143).toString(),
        plan = "Pro"
    )

    override suspend fun listFiles(page: Int, pageSize: Int): Page<DownloadItem> =
        if (page == 1) Page(DemoData.library, hasMore = false) else Page(emptyList(), hasMore = false)

    override suspend fun resolveLink(sourceRef: String) = ResolvedLink("https://example.com/$sourceRef", null, null)

    override val addCapabilities = setOf(AddKind.MAGNET, AddKind.TORRENT_FILE, AddKind.LINK)

    override suspend fun listProcessing(): List<RemoteJob> = DemoData.processing

    override fun zipBundle(parentRef: String, parentName: String, fileCount: Int) = DownloadItem(
        id = "demo:zip:$parentRef", provider = ProviderId.TORBOX, filename = "$parentName.zip",
        sourceRef = "zip:$parentRef", downloadUrl = null, host = "Torrent · zip of $fileCount files",
        filesize = 0, maxConnections = 1, addedAt = null, parentRef = parentRef, parentName = parentName
    )

    override fun removalKey(itemId: String): String = itemId
}

/** Swaps every real service for [DemoProvider] in screenshot tests. */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [ProviderBindings::class])
abstract class DemoProviderModule {
    @Binds @IntoSet
    abstract fun demo(impl: DemoProvider): DebridProvider
}

object DemoData {
    const val SHOW_TITLE = "Open Movie Diaries"
    private const val GB = 1024L * 1024 * 1024
    private const val MB = 1024L * 1024

    private fun daysAgo(d: Long) = Instant.now().minusSeconds(86_400L * d).toString()

    private fun movie(id: String, file: String, size: Long, age: Long) = DownloadItem(
        id = "demo:$id", provider = ProviderId.TORBOX, filename = file, sourceRef = id, downloadUrl = null,
        host = "Torrent", filesize = size, maxConnections = 16, addedAt = daysAgo(age)
    )

    private val episodes = (1..6).map { e ->
        DownloadItem(
            id = "demo:omd:$e", provider = ProviderId.TORBOX,
            filename = "Open.Movie.Diaries.S01E0$e.1080p.WEB-DL.DDP5.1.H.264-DEMO.mkv",
            sourceRef = "omd:$e", downloadUrl = null, host = "Torrent · Open.Movie.Diaries.S01",
            filesize = (1_150L + e * 37) * MB, maxConnections = 16, addedAt = daysAgo(1),
            parentRef = "torrent:omd", parentName = "Open.Movie.Diaries.S01.1080p.WEB-DL-DEMO"
        )
    }

    val library: List<DownloadItem> = episodes + listOf(
        movie("sintel", "Sintel.2010.2160p.BluRay.REMUX.HEVC.DTS-HD.MA.5.1-DEMO.mkv", 18 * GB + 400 * MB, 0),
        movie("tos", "Tears.of.Steel.2012.2160p.WEB-DL.DDP5.1.HDR.x265-DEMO.mkv", 7 * GB + 900 * MB, 2),
        movie("bbb", "Big.Buck.Bunny.2008.1080p.BluRay.x264-DEMO.mkv", 2 * GB + 300 * MB, 3),
        movie("spring", "Spring.2019.2160p.WEB-DL.HDR10.x265-DEMO.mkv", 4 * GB + 100 * MB, 4),
        movie("cosmos", "Cosmos.Laundromat.2015.1080p.WEB-DL.x264-DEMO.mkv", 1 * GB + 700 * MB, 6),
        movie("ed", "Elephants.Dream.2006.1080p.BluRay.x264-DEMO.mkv", 1 * GB + 200 * MB, 9),
        movie("agent", "Agent.327.Operation.Barbershop.2017.1080p.WEB-DL-DEMO.mkv", 800 * MB, 12),
        movie("coffee", "Coffee.Run.2020.2160p.WEB-DL.x265-DEMO.mkv", 1 * GB + 900 * MB, 15),
        movie("sprite", "Sprite.Fright.2021.1080p.WEB-DL.DDP5.1.x264-DEMO.mkv", 2 * GB + 600 * MB, 20)
    )

    val processing = listOf(
        RemoteJob("torrent:charge", ProviderId.TORBOX, "Charge.2022.2160p.WEB-DL.DDP5.1.x265-DEMO", 0.64f,
            "downloading", 6 * GB, 290L, 38 * MB),
        RemoteJob("torrent:wing", ProviderId.TORBOX, "Wing.It.2023.1080p.WEB-DL.x264-DEMO", 0.12f,
            "downloading", 1 * GB + 400 * MB, 1_140L, 9 * MB)
    )

    private fun row(item: DownloadItem, state: DownloadState, done: Long) = DownloadEntity(
        id = item.id, provider = item.provider, filename = item.filename, sourceRef = item.sourceRef,
        downloadUrl = "", host = item.host, filesize = item.filesize, chunksAllowed = 16,
        state = state, bytesDownloaded = done,
        partFilePath = "/storage/emulated/0/Movies/DebForge/${item.filename}.part",
        finalFilePath = "/storage/emulated/0/Movies/DebForge/${item.filename}"
    )

    private fun byId(id: String) = library.first { it.id == "demo:$id" }

    /** Downloads screen: one running, two waiting, one paused. */
    val downloading get() = byId("sintel")
    val downloads: List<DownloadEntity> get() = listOf(
        row(downloading, DownloadState.DOWNLOADING, downloading.filesize * 58 / 100),
        row(byId("tos"), DownloadState.QUEUED, 0),
        row(byId("spring"), DownloadState.QUEUED, 0),
        row(byId("cosmos"), DownloadState.PAUSED, byId("cosmos").filesize * 37 / 100)
    )
}
