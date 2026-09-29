package com.abhinavxt.debforge.player

import android.content.Context
import android.net.Uri
import com.abhinavxt.debforge.data.local.DownloadDao
import com.abhinavxt.debforge.data.local.DownloadEntity
import com.abhinavxt.debforge.domain.DownloadItem
import com.abhinavxt.debforge.domain.DownloadState
import com.abhinavxt.debforge.domain.MediaKind
import com.abhinavxt.debforge.domain.NextEpisode
import com.abhinavxt.debforge.domain.ReleaseNameParser
import com.abhinavxt.debforge.domain.Subtitles
import com.abhinavxt.debforge.download.LocalFiles
import com.abhinavxt.debforge.download.SafPaths
import com.abhinavxt.debforge.ui.browse.PlayRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plays finished downloads from the device: offline, no data used. The file
 * keeps the same id as in the Library, so the resume position is shared
 * with streaming.
 *
 * Subtitles are the downloaded .srt/.ass/.vtt files in the same folder, and
 * the next episodes come from the show's other downloads (any folder, e.g.
 * the next season's). All links are local, so nothing needs the network.
 */
@Singleton
class LocalPlayback @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloadDao: DownloadDao,
    private val subtitleResolver: SubtitleResolver,
    private val upNext: UpNext
) {
    /** A play request for [itemId]'s downloaded copy, or null if there is none (or the file is gone). */
    suspend fun requestFor(itemId: String): PlayRequest? {
        val entity = downloadDao.getById(itemId)?.takeIf { it.state == DownloadState.COMPLETED } ?: return null
        return requestFor(entity)
    }

    suspend fun requestFor(entity: DownloadEntity): PlayRequest? = withContext(Dispatchers.IO) {
        val path = entity.finalFilePath ?: return@withContext null
        if (!LocalFiles.exists(path)) return@withContext null
        val uri = LocalFiles.uriFor(context, path) ?: return@withContext null

        val info = ReleaseNameParser.parse(entity.filename)
        val folder = folderOf(path)
        // Only what can matter: files in the same folder (subtitles) and the
        // same show (next episodes). Links are looked up just for these.
        val related = downloadDao.completed().filter { other ->
            val p = other.finalFilePath ?: return@filter false
            other.id != entity.id && (folderOf(p) == folder ||
                (info.kind == MediaKind.SHOW && ReleaseNameParser.parse(other.filename).groupKey == info.groupKey))
        }
        val video = entity.toLocalItem(uri, folder)
        val pool = listOf(video) + related.mapNotNull { other ->
            val p = other.finalFilePath ?: return@mapNotNull null
            LocalFiles.uriFor(context, p)?.let { other.toLocalItem(it, folderOf(p)) }
        }

        val candidates = pool.map { NextEpisode.Candidate(it, ReleaseNameParser.parse(it.filename)) }
        val upcoming = NextEpisode.upcoming(video, info, candidates).map { c ->
            QueuedEpisode(
                item = c.item.copy(parentRef = null),
                title = c.info.displayTitle,
                showKey = c.info.groupKey,
                subtitles = Subtitles.forVideo(c.item, pool)
            )
        }
        upNext.set(video.id, upcoming)

        PlayRequest(
            uri = uri,
            title = info.displayTitle,
            // No torrent reference: the "parent" here is only a folder, used to
            // pair subtitles, and mustn't be saved as the file's torrent.
            item = video.copy(parentRef = null),
            showKey = info.groupKey.takeIf { info.kind == MediaKind.SHOW },
            subtitles = subtitleResolver.resolve(Subtitles.forVideo(video, pool))
        )
    }

    /** Same folder = same "torrent" for pairing subtitles with their video. */
    private fun folderOf(path: String): String =
        SafPaths.parse(path)?.let { "saf:" + it.treeUri + "#" + it.dirs.joinToString("/") }
            ?: (File(path).parent ?: "")

    /**
     * As a DownloadItem whose [DownloadItem.downloadUrl] is the local file:
     * the player and the subtitle resolver use it as-is, no network.
     */
    private fun DownloadEntity.toLocalItem(uri: Uri, folder: String) = DownloadItem(
        id = id,
        provider = provider,
        filename = filename,
        sourceRef = sourceRef,
        downloadUrl = uri.toString(),
        host = host,
        filesize = filesize,
        maxConnections = 1,
        addedAt = null,
        parentRef = "local:$folder"
    )
}
