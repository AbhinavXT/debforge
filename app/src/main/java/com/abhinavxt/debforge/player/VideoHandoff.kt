package com.abhinavxt.debforge.player

import android.content.Context
import android.net.Uri
import android.util.Log
import com.abhinavxt.debforge.data.local.DownloadDao
import com.abhinavxt.debforge.data.prefs.SettingsStore
import com.abhinavxt.debforge.data.youtube.YouTubeResolver
import com.abhinavxt.debforge.domain.DownloadState
import com.abhinavxt.debforge.domain.YouTubeFormats
import com.abhinavxt.debforge.domain.YouTubeLinks
import com.abhinavxt.debforge.download.LocalFiles
import com.abhinavxt.debforge.download.youtube.EnqueueMode
import com.abhinavxt.debforge.download.youtube.choiceForMode
import com.abhinavxt.debforge.player.youtube.YouTubeStreaming
import com.abhinavxt.debforge.ui.browse.PlayRequest
import com.abhinavxt.debforge.ui.browse.youTubePlayRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** The outcome of [VideoHandoff.watchRequest]. */
sealed interface WatchResult {
    data class Ready(val request: PlayRequest) : WatchResult
    /** The downloaded file is gone. */
    object FileMissing : WatchResult
    /** The stream could not be looked up or has no video. */
    object StreamFailed : WatchResult
}

/** Switching one item between the video player and the audio player. */
@Singleton
class VideoHandoff @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloadDao: DownloadDao,
    private val localPlayback: LocalPlayback,
    private val resolver: YouTubeResolver,
    private val settings: SettingsStore
) {
    /**
     * The audio track for the video being watched: a local download by [itemId] (its download id), or a streamed YouTube
     * video by [videoId], using [audioUrl] and [audioItag] when known (else the audio player looks the stream up).
     * Null for a debrid or Library stream, or a download that is gone.
     */
    suspend fun audioTrackForVideo(
        itemId: String,
        videoId: String?,
        title: String,
        channel: String? = null,
        audioUrl: String? = null,
        audioItag: Int? = null
    ): AudioTrack? {
        if (videoId != null) {
            return AudioTrack(
                id = YouTubeStreaming.streamPositionKey(videoId, audioOnly = true),
                uri = audioUrl?.let { Uri.parse(it) } ?: Uri.EMPTY,
                title = title,
                artist = channel?.ifBlank { null },
                artworkFile = null,
                artworkUrl = com.abhinavxt.debforge.data.playback.PlaylistEntry.thumbnailUrl(videoId),
                youtube = YouTubeAudio(videoId, audioItag ?: 0)
            )
        }
        val row = withContext(Dispatchers.IO) { downloadDao.getById(itemId) } ?: return null
        if (row.state != DownloadState.COMPLETED) return null
        return audioTrackFor(context, row)?.takeIf { it.canWatch }
    }

    /** The video to open for [track]: a local video file, a local video download of the YouTube video, else its stream. */
    suspend fun watchRequest(track: AudioTrack): WatchResult {
        val yt = track.youtube
        if (yt == null) {
            if (!track.isVideoFile) return WatchResult.FileMissing
            return localPlayback.requestFor(track.id)?.let { WatchResult.Ready(it) } ?: WatchResult.FileMissing
        }
        val rows = withContext(Dispatchers.IO) {
            downloadDao.completed().filter { row ->
                YouTubeLinks.parseSourceRef(row.sourceRef)?.videoId == yt.videoId && row.finalFilePath?.let { LocalFiles.exists(it) } == true
            }
        }
        preferredVideoDownload(rows.map { YouTubeDownloadRow(it.id, it.sourceRef) }, yt.videoId)
            ?.let { localPlayback.requestFor(it) }?.let { return WatchResult.Ready(it) }
        return youTubeVideoRequest(yt.videoId)?.let { WatchResult.Ready(it) } ?: WatchResult.StreamFailed
    }

    /** The streamed video of [videoId] at the user's default video quality; null when it cannot be resolved. */
    suspend fun youTubeVideoRequest(videoId: String): PlayRequest? = try {
        // The resolver takes a link, not a bare id: a bare id is "not a video link" and always failed.
        val video = resolver.resolve(YouTubeLinks.watchUrl(videoId))
        val choices = YouTubeFormats.choices(video.streams, android.os.Build.VERSION.SDK_INT, video.durationSec)
        choiceForMode(choices, EnqueueMode.VIDEO, settings.youTubeQualityFlow.first())?.let { youTubePlayRequest(video, it, emptyList()) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("VideoHandoff", "Opening YouTube video $videoId failed", e)
        null
    }
}
