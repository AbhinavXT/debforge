package com.abhinavxt.debforge.ui.video

import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.data.playback.AudioLists
import com.abhinavxt.debforge.data.playback.Playlist
import com.abhinavxt.debforge.data.playback.YouTubeMeta
import com.abhinavxt.debforge.data.prefs.SettingsStore
import com.abhinavxt.debforge.data.youtube.YouTubeResolver
import com.abhinavxt.debforge.data.provider.ProviderRegistry
import com.abhinavxt.debforge.domain.ProviderId
import com.abhinavxt.debforge.domain.DownloadItem
import com.abhinavxt.debforge.domain.FormatChoice
import com.abhinavxt.debforge.domain.YouTubeFormats
import com.abhinavxt.debforge.domain.YouTubeLinks
import com.abhinavxt.debforge.domain.YouTubeSearchResult
import com.abhinavxt.debforge.domain.YouTubeSubtitle
import com.abhinavxt.debforge.domain.YouTubeVideo
import com.abhinavxt.debforge.domain.pickDefaultChoice
import com.abhinavxt.debforge.download.DownloadController
import com.abhinavxt.debforge.download.StorageAccess
import com.abhinavxt.debforge.download.youtube.EnqueueResult
import com.abhinavxt.debforge.download.youtube.YouTubeEnqueuer
import com.abhinavxt.debforge.player.AudioPlayer
import com.abhinavxt.debforge.player.EnqueueOutcome
import com.abhinavxt.debforge.player.VideoLauncher
import com.abhinavxt.debforge.player.cast.DlnaCaster
import com.abhinavxt.debforge.player.youtube.YouTubeStreaming
import com.abhinavxt.debforge.ui.browse.PlayRequest
import com.abhinavxt.debforge.ui.browse.YtStream
import com.abhinavxt.debforge.ui.browse.youTubePlayRequest
import com.abhinavxt.debforge.ui.youtube.AddToPlaylistRequest
import com.abhinavxt.debforge.ui.youtube.playlistAddMessage
import com.abhinavxt.debforge.ui.youtube.toAddRequest
import com.abhinavxt.debforge.ui.youtube.youTubeDownloadItem
import com.abhinavxt.debforge.ui.youtube.youTubeSubtitleItem
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * What the watch page and the in-app player ask of the app beyond playing: other videos in place,
 * downloads, playlists and the audio queue, and the YouTube details of a stream started without them.
 * Results are told with toasts (the watch page has no snackbar of its own).
 */
@HiltViewModel
class VideoHostViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    val launcher: VideoLauncher,
    val settings: SettingsStore,
    val caster: DlnaCaster,
    private val resolver: YouTubeResolver,
    private val enqueuer: YouTubeEnqueuer,
    private val downloads: DownloadController,
    private val audioPlayer: AudioPlayer,
    private val audioLists: AudioLists,
    private val providers: ProviderRegistry,
) : ViewModel() {

    /** The debrid service's name for the source line; null for what is not one. */
    fun providerName(id: ProviderId): String? =
        if (id == ProviderId.DIRECT || id == ProviderId.LOCAL) null else runCatching { providers[id].info.displayName }.getOrNull()

    private fun toast(res: Int, vararg args: Any) =
        Toast.makeText(context, context.getString(res, *args), Toast.LENGTH_LONG).show()

    /** A YouTube stream started from a history row or a link has no details yet: fetch its related videos and channel. */
    fun loadDetails(active: ActiveVideo, show: (YouTubeVideo?, Boolean) -> Unit) {
        val id = active.request.youtube?.videoId ?: return show(null, false)
        val known = active.video
        if (known != null) return show(known, false)
        show(null, true)
        viewModelScope.launch {
            val video = try {
                resolver.resolve(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (video != null) launcher.detailsArrived(active.token, video)
            if (launcher.active.value?.token == active.token) show(video, false)
        }
    }

    /** A related video: resolved, then played in place (the one playing is saved first). */
    fun playRelated(result: YouTubeSearchResult, loading: () -> Unit, failed: () -> Unit) {
        loading()
        viewModelScope.launch {
            try {
                val video = resolver.resolve(result.url)
                val choices = YouTubeFormats.choices(video.streams, Build.VERSION.SDK_INT, video.durationSec)
                val choice = YouTubeStreaming.quickPlayChoice(settings.youTubeQualityFlow.first(), choices)
                    ?: pickDefaultChoice(choices, settings.youTubeQualityFlow.first())
                    ?: choices.firstOrNull { it.videoItag != null }
                val request = choice?.let { youTubePlayRequest(video, it, emptyList()) }
                if (request == null) {
                    toast(R.string.yt_err_unavailable)
                    failed()
                } else launcher.open(context, request, video = video)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(R.string.yt_err_unavailable)
                failed()
            }
        }
    }

    // --- the audio queue and playlists ----------------------------------------------

    fun addToQueue(result: YouTubeSearchResult) {
        val id = YouTubeLinks.videoId(result.url) ?: return
        queue(id, YouTubeMeta(result.title, result.channel?.takeIf { it.isNotBlank() }, result.durationSec.toInt().takeIf { it > 0 }))
    }

    fun addToQueue(video: YouTubeVideo) =
        queue(video.id, YouTubeMeta(video.title, video.uploader.takeIf { it.isNotBlank() }, video.durationSec.toInt().takeIf { it > 0 }))

    private fun queue(id: String, meta: YouTubeMeta) {
        viewModelScope.launch {
            try {
                when (audioPlayer.enqueueYouTube(id, meta)) {
                    EnqueueOutcome.ALREADY_QUEUED -> toast(R.string.yt_queue_already)
                    else -> toast(R.string.yt_queue_added)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(R.string.yt_err_unavailable)
            }
        }
    }

    suspend fun playlists(): List<Playlist> = audioLists.snapshot().playlists

    fun addToPlaylist(req: AddToPlaylistRequest, listId: String, mode: com.abhinavxt.debforge.data.playback.YouTubeMode) {
        viewModelScope.launch {
            val name = audioLists.snapshot().playlists.firstOrNull { it.id == listId }?.name.orEmpty()
            val result = audioLists.addYouTube(listId, req.videoId, mode, req.meta)
            toast(playlistAddMessage(result, mode), name)
        }
    }

    fun addToNewPlaylist(req: AddToPlaylistRequest, name: String, mode: com.abhinavxt.debforge.data.playback.YouTubeMode) {
        viewModelScope.launch {
            val id = audioLists.create(name)
            if (id.isEmpty()) toast(R.string.yt_pl_failed) else addToPlaylist(req, id, mode)
        }
    }

    // --- downloads -------------------------------------------------------------------

    /** The selected quality of the playing YouTube video (and its subtitle) to the downloads. */
    fun downloadYouTube(video: YouTubeVideo, choice: FormatChoice, subtitle: YouTubeSubtitle?) {
        viewModelScope.launch {
            if (!StorageAccess.canWrite(context, settings.downloadDirFlow.first())) return@launch toast(R.string.add_needs_storage)
            val item = enqueuer.itemFor(video, choice)
            val sub = subtitle?.takeIf { choice.videoItag != null }?.let { youTubeSubtitleItem(item, it) }
            val first = enqueuer.tryEnqueue(item, video.thumbnailUrl)
            val second = sub?.let { enqueuer.tryEnqueue(it, null) }
            toast(
                when {
                    first == EnqueueResult.QUEUED -> if (second == EnqueueResult.FAILED) R.string.yt_queued_no_subtitle else R.string.yt_queued
                    first == EnqueueResult.ALREADY_QUEUED -> if (second == EnqueueResult.QUEUED) R.string.yt_subtitle_added else R.string.yt_already_queued
                    else -> R.string.yt_err_queue
                }
            )
        }
    }

    /** A debrid stream's file to the downloads. */
    fun downloadFile(item: DownloadItem) {
        viewModelScope.launch {
            if (!StorageAccess.canWrite(context, settings.downloadDirFlow.first())) return@launch toast(R.string.add_needs_storage)
            try {
                downloads.enqueue(item, foreground = true)
                toast(R.string.msg_added_one, item.filename)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(R.string.yt_err_queue)
            }
        }
    }

    /** "Download instead" for a YouTube stream that could not be kept going: queues it, says so, then [done]. */
    fun downloadFailedStream(yt: YtStream, title: String, uri: android.net.Uri, subtitles: List<com.abhinavxt.debforge.domain.SubtitleLink>, done: () -> Unit) {
        val ref = YouTubeLinks.parseSourceRef(yt.ref)
        viewModelScope.launch {
            if (ref == null || !StorageAccess.canWrite(context, settings.downloadDirFlow.first())) {
                toast(R.string.add_needs_storage)
                done()
                return@launch
            }
            val item = youTubeDownloadItem(
                videoId = yt.videoId, title = title, uploader = yt.uploader.ifBlank { null }, videoItag = ref.videoItag,
                audioItag = ref.audioItag, extension = yt.extension, height = yt.height, estimatedBytes = 0, url = uri.toString(),
            )
            val sub = subtitles.firstOrNull { !it.url.startsWith("file:") && it.language != null }?.let {
                youTubeSubtitleItem(item, YouTubeSubtitle(it.language!!, it.label, it.url, false))
            }
            val first = enqueuer.tryEnqueue(item, com.abhinavxt.debforge.ui.youtube.youTubeThumbnailUrl(yt.videoId))
            var message = when (first) {
                EnqueueResult.QUEUED -> R.string.yt_queued
                EnqueueResult.ALREADY_QUEUED -> R.string.yt_already_queued
                else -> R.string.yt_err_queue
            }
            if (first == EnqueueResult.QUEUED && sub != null && enqueuer.tryEnqueue(sub, null) == EnqueueResult.FAILED) message = R.string.yt_queued_no_subtitle
            toast(message)
            done()
        }
    }
}
