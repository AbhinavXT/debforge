package com.abhinavxt.debforge.data.youtube

import com.abhinavxt.debforge.di.ApiHttpClient
import com.abhinavxt.debforge.domain.Container
import com.abhinavxt.debforge.domain.StreamKind
import com.abhinavxt.debforge.domain.YouTubeLinks
import com.abhinavxt.debforge.domain.YouTubeSearchResult
import com.abhinavxt.debforge.domain.YouTubeStream
import com.abhinavxt.debforge.domain.YouTubeVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Why a link could not be resolved; the UI picks its message from the subtype. */
sealed class YouTubeException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class PlaylistOnly : YouTubeException("Playlists aren't supported; open a single video")
    class SignInRequired(cause: Throwable? = null) : YouTubeException("Age restricted or sign-in required", cause)
    class Unavailable(message: String = "Video unavailable or private", cause: Throwable? = null) : YouTubeException(message, cause)
    class Network(cause: Throwable?) : YouTubeException("Network error", cause)
    class Failed(cause: Throwable?) : YouTubeException(cause?.message ?: "Could not read the video", cause)
}

@Singleton
class YouTubeResolver @Inject constructor(@ApiHttpClient private val client: OkHttpClient) {
    private val init by lazy { NewPipe.init(NewPipeDownloader(client), Localization("en", "US")) }

    suspend fun resolve(url: String): YouTubeVideo = withContext(Dispatchers.IO) {
        val id = YouTubeLinks.videoId(url)
            ?: throw if (YouTubeLinks.isPlaylistOnly(url)) YouTubeException.PlaylistOnly()
            else YouTubeException.Unavailable("Not a YouTube video link")
        val info = try {
            init
            StreamInfo.getInfo("https://www.youtube.com/watch?v=$id")
        } catch (e: Exception) {
            throw map(e)
        }
        YouTubeVideo(
            id = id,
            title = info.name.orEmpty(),
            uploader = info.uploaderName.orEmpty(),
            durationSec = info.duration,
            thumbnailUrl = info.thumbnails.maxByOrNull { it.height }?.url,
            streams = info.videoOnlyStreams.mapNotNull { video(it) } + info.audioStreams.mapNotNull { audio(it) },
        )
    }

    suspend fun search(query: String): List<YouTubeSearchResult> = withContext(Dispatchers.IO) {
        try {
            init
            val yt = ServiceList.YouTube
            // shortcut: first page of results only, add paging when users ask for more
            SearchInfo.getInfo(yt, yt.searchQHFactory.fromQuery(query, listOf(YoutubeSearchQueryHandlerFactory.VIDEOS), ""))
                .relatedItems.filterIsInstance<StreamInfoItem>().map {
                    YouTubeSearchResult(it.url, it.name.orEmpty(), it.uploaderName, it.duration, it.thumbnails.maxByOrNull { t -> t.height }?.url)
                }
        } catch (e: Exception) {
            throw map(e)
        }
    }

    private fun map(e: Exception): YouTubeException = when {
        e is YouTubeException -> e
        e is AgeRestrictedContentException || e is SignInConfirmNotBotException || e is ReCaptchaException ->
            YouTubeException.SignInRequired(e)
        e is ContentNotAvailableException -> YouTubeException.Unavailable(cause = e)
        generateSequence<Throwable>(e) { it.cause }.any { it is IOException } -> YouTubeException.Network(e)
        else -> YouTubeException.Failed(e)
    }

    private fun container(suffix: String?) = when (suffix) {
        "mp4" -> Container.MP4
        "m4a" -> Container.M4A
        "webm" -> Container.WEBM
        else -> null
    }

    private fun video(s: VideoStream): YouTubeStream? {
        if (!s.isUrl) return null
        return YouTubeStream(
            s.itag, s.content, StreamKind.VIDEO, container(s.format?.suffix) ?: return null, s.codec.orEmpty(),
            s.height, s.fps, s.bitrate.toLong(), s.itagItem?.contentLength ?: -1,
        )
    }

    private fun audio(s: AudioStream): YouTubeStream? {
        if (!s.isUrl || (s.audioTrackType != null && s.audioTrackType != AudioTrackType.ORIGINAL)) return null
        return YouTubeStream(
            s.itag, s.content, StreamKind.AUDIO, container(s.format?.suffix) ?: return null, s.codec.orEmpty(),
            bitrateBps = s.bitrate.toLong(), contentLength = s.itagItem?.contentLength ?: -1,
        )
    }
}
