package com.abhinavxt.debforge.player.session

import android.net.Uri
import androidx.media3.common.PlaybackException
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.domain.DataResult
import com.abhinavxt.debforge.domain.LinkRefresh
import com.abhinavxt.debforge.player.PlayerErrors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Keeps a stream going for [VideoSession]: fresh debrid links after network errors (expired links),
 * YouTube re-resolving and its MP4 fallback, and the Retry dialog with its wait for the connection.
 */
internal class VideoRefresh(private val s: VideoSession) {

    /** Quiet link refreshes after network errors (expired debrid links). */
    private companion object { const val QUALITY_CACHE_MS = 5 * 60_000L }

    private var refreshState = LinkRefresh.State()
    private var refreshing = false
    private var triedMp4Fallback = false // a YouTube stream that the device could not decode is switched to MP4 once
    private var networkWatch: kotlinx.coroutines.Job? = null

    /** A new file: a fresh budget. */
    fun resetBudget() {
        refreshState = LinkRefresh.State()
    }

    fun cancel() {
        networkWatch?.cancel()
        networkWatch = null
    }

    /**
     * The stream failed with a network error: most often the debrid link
     * expired (a long pause, or hours in). Get a fresh one for the same
     * file, and fresh subtitle links, and continue from the same second.
     * Capped by [LinkRefresh]; after that, or for plain links, the Retry
     * dialog.
     */
    fun refreshLinkOrAsk(refused: Boolean = false) {
        val id = s.identity
        val next = LinkRefresh.next(refreshState, android.os.SystemClock.elapsedRealtime())
        if (id == null || next == null || refreshing) {
            // "YouTube stopped this stream" is only for a link YouTube kept refusing; anything else is the connection.
            if (!refreshing) if (s.youtube != null && refused) s.showStreamFailed() else showNetworkError()
            return
        }
        refreshState = next
        refreshing = true
        val position = s.player?.currentPosition ?: s.resumePosition
        s.scope.launch {
            try {
                if (s.youtube != null) {
                    val result = refreshYouTube()
                    if (id == s.identity) when {
                        result == YtRefresh.OK -> Unit
                        result == YtRefresh.GONE && refused -> s.showStreamFailed()
                        else -> showNetworkError()
                    }
                    return@launch
                }
                val fresh = (s.repository.resolveLink(id.provider, id.sourceRef) as? DataResult.Success)?.data?.url
                if (fresh == null || id != s.identity) {
                    if (id == s.identity) showNetworkError()
                    return@launch
                }
                s.uri = Uri.parse(fresh)
                s.subtitles = s.subtitleResolver.refresh(s.subtitles)
                s.reloadMediaItem(position, play = true)
            } finally {
                refreshing = false
            }
        }
    }

    /**
     * The YouTube stream's URLs expired (or the connection broke): resolve the video again, pick the same
     * itags (or the best at or below the old height) and carry on from [position]. False when that failed.
     */
    private enum class YtRefresh { OK, GONE, OFFLINE }

    private var resolved: Triple<String, Long, com.abhinavxt.debforge.domain.YouTubeVideo>? = null // videoId, when, video; memory only

    /** The video's resolve, reused when it is younger than [maxAgeMs]. */
    private suspend fun resolveVideo(videoId: String, maxAgeMs: Long): com.abhinavxt.debforge.domain.YouTubeVideo {
        val now = android.os.SystemClock.elapsedRealtime()
        resolved?.let { (id, at, v) -> if (id == videoId && now - at < maxAgeMs) return v }
        val video = s.youTubeResolver.resolve("https://www.youtube.com/watch?v=$videoId")
        resolved = Triple(videoId, android.os.SystemClock.elapsedRealtime(), video)
        return video
    }

    private suspend fun refreshYouTube(
        maxAgeMs: Long = 0,
        pick: (com.abhinavxt.debforge.domain.YouTubeLinks.Ref, Int, List<com.abhinavxt.debforge.domain.FormatChoice>) -> com.abhinavxt.debforge.domain.FormatChoice? =
            com.abhinavxt.debforge.player.youtube.YouTubeStreaming::reselect,
        play: Boolean = true
    ): YtRefresh {
        val yt = s.youtube ?: return YtRefresh.GONE
        val ref = com.abhinavxt.debforge.domain.YouTubeLinks.parseSourceRef(yt.ref) ?: return YtRefresh.GONE
        val id = s.identity
        val request = try {
            val video = resolveVideo(yt.videoId, maxAgeMs)
            // Up next may have moved on to another video while this looked it up.
            if (id != s.identity) return YtRefresh.OK
            val choices = com.abhinavxt.debforge.domain.YouTubeFormats.choices(video.streams, android.os.Build.VERSION.SDK_INT, video.durationSec)
            val choice = pick(ref, yt.height, choices) ?: return YtRefresh.GONE
            // Exactly the subtitles that were attached (new links), not every track of the video.
            val attached = s.subtitles.filter { !it.url.startsWith("file:") }.map { com.abhinavxt.debforge.player.youtube.SubtitleChoice(it.language, it.label) }
            com.abhinavxt.debforge.ui.browse.youTubePlayRequest(
                video, choice, com.abhinavxt.debforge.player.youtube.reattachSubtitles(video.subtitles, attached)
            ) ?: return YtRefresh.GONE
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: com.abhinavxt.debforge.data.youtube.YouTubeException.Network) {
            return YtRefresh.OFFLINE
        } catch (e: Exception) {
            return YtRefresh.GONE
        }
        val fresh = request.youtube ?: return YtRefresh.GONE
        val position = s.player?.currentPosition ?: s.resumePosition // after the lookup, which took seconds
        s.uri = request.uri
        s.youtube = fresh
        // The same subtitles as before (new links), plus those added from OpenSubtitles.
        s.subtitles = s.subtitles.filter { it.url.startsWith("file:") } + request.subtitles
        s.reloadMediaItem(position, play)
        return YtRefresh.OK
    }

    /** Quality entry: the video choices of the playing YouTube video, current one marked; null when they can't be looked up. */
    suspend fun qualityOptions(): List<com.abhinavxt.debforge.player.youtube.QualityOption>? {
        val yt = s.youtube ?: return null
        val ref = com.abhinavxt.debforge.domain.YouTubeLinks.parseSourceRef(yt.ref) ?: return null
        return try {
            val video = resolveVideo(yt.videoId, QUALITY_CACHE_MS)
            com.abhinavxt.debforge.player.youtube.YouTubeStreaming.qualityOptions(
                ref, com.abhinavxt.debforge.domain.YouTubeFormats.choices(video.streams, android.os.Build.VERSION.SDK_INT, video.durationSec)
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Quality entry: carry on in [target] at the same second, keeping play or pause. Only this video: the new itags
     * live in the stream's ref, so the next video starts at the default quality. On failure the old quality plays on;
     * [failed] is told. The same choice does nothing.
     */
    fun changeQuality(target: com.abhinavxt.debforge.domain.FormatChoice, failed: () -> Unit) {
        val yt = s.youtube ?: return
        val ref = com.abhinavxt.debforge.domain.YouTubeLinks.parseSourceRef(yt.ref) ?: return
        if (refreshing || com.abhinavxt.debforge.player.youtube.YouTubeStreaming.sameChoice(ref, target)) return
        val id = s.identity
        val play = s.player?.playWhenReady ?: true
        refreshing = true
        s.scope.launch {
            try {
                val result = refreshYouTube(QUALITY_CACHE_MS, { _, _, choices ->
                    choices.firstOrNull { it.videoItag == target.videoItag && it.audioItag == target.audioItag }
                }, play)
                if (result != YtRefresh.OK && id == s.identity) failed()
            } finally {
                refreshing = false
            }
        }
    }

    /** A YouTube stream broke: refused link, lost connection, undecodable format or something else. */
    fun onYouTubeError(error: PlaybackException) {
        when (PlayerErrors.youTubeFailure(error.errorCode, PlayerErrors.httpStatusOf(error))) {
            PlayerErrors.YouTubeFailure.LINK_REFUSED -> refreshLinkOrAsk(refused = true)
            PlayerErrors.YouTubeFailure.NETWORK ->
                // Offline: wait for the connection instead of looking the video up again.
                if (s.networkMonitor.online.value) refreshLinkOrAsk() else showNetworkError()
            PlayerErrors.YouTubeFailure.DECODER -> youTubeDecoderFallback()
            PlayerErrors.YouTubeFailure.OTHER -> s.showYouTubeProblem(s.string(R.string.player_error_body, error.errorCodeName))
        }
    }

    /** The device could not decode this format: carry on in an MP4 choice at the same position, once. */
    private fun youTubeDecoderFallback() {
        // A refresh already in flight replaces the source and reports its own failure with a dialog,
        // so a decoder error from the old source needs no second message here.
        if (refreshing || s.host?.isClosing == true) return
        if (triedMp4Fallback) { s.showYouTubeProblem(s.string(R.string.player_unsupported_body)); return }
        triedMp4Fallback = true
        refreshing = true
        val id = s.identity
        s.scope.launch {
            try {
                val result = refreshYouTube(0, com.abhinavxt.debforge.player.youtube.YouTubeStreaming::mp4Fallback)
                if (id != s.identity) return@launch
                when (result) {
                    YtRefresh.OK -> Unit
                    YtRefresh.OFFLINE -> showNetworkError()
                    YtRefresh.GONE -> s.showYouTubeProblem(s.string(R.string.player_unsupported_body))
                }
            } finally {
                refreshing = false
            }
        }
    }

    private fun showNetworkError() {
        val host = s.host ?: return
        if (host.isClosing) return
        host.showNetworkError()
        if (s.youtube != null && !s.networkMonitor.online.value) {
            // Offline: carry on by itself when the connection is back.
            networkWatch = s.scope.launch {
                s.networkMonitor.online.first { it }
                s.host?.dismissNetworkError()
                refreshState = LinkRefresh.State()
                refreshLinkOrAsk()
            }
        }
    }

    /** Retry in the network dialog. */
    fun retry() {
        if (s.identity != null) {
            // A fresh link, with a fresh budget: the user asked for it.
            refreshState = LinkRefresh.State()
            refreshLinkOrAsk()
        } else {
            s.player?.let {
                s.resumePosition = it.currentPosition
                it.prepare()
                it.play()
            }
        }
    }

    /** The network dialog went away: stop waiting for the connection. */
    fun networkDialogGone() {
        networkWatch?.cancel()
        networkWatch = null
    }
}
