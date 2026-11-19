package com.abhinavxt.debforge.download.youtube

import android.content.Context
import android.os.Build
import com.abhinavxt.debforge.data.local.DownloadDao
import com.abhinavxt.debforge.data.prefs.SettingsStore
import com.abhinavxt.debforge.data.youtube.YouTubeResolver
import com.abhinavxt.debforge.di.ApiHttpClient
import com.abhinavxt.debforge.domain.DefaultQuality
import com.abhinavxt.debforge.domain.DownloadItem
import com.abhinavxt.debforge.domain.FormatChoice
import com.abhinavxt.debforge.domain.YouTubeFormats
import com.abhinavxt.debforge.domain.YouTubeVideo
import com.abhinavxt.debforge.domain.pickDefaultChoice
import com.abhinavxt.debforge.download.DownloadController
import com.abhinavxt.debforge.ui.youtube.saveYouTubeThumbnail
import com.abhinavxt.debforge.ui.youtube.youTubeDownloadItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/** What to download: the user's default quality, audio only, or the best video. */
enum class EnqueueMode { DEFAULT, AUDIO, VIDEO }

/** What happened to a queued item. */
enum class EnqueueResult { QUEUED, ALREADY_QUEUED, FAILED }

/** The choice to download for [mode]; [quality] is the default-quality setting. Null when there are no choices. */
internal fun choiceForMode(choices: List<FormatChoice>, mode: EnqueueMode, quality: DefaultQuality): FormatChoice? = when (mode) {
    EnqueueMode.DEFAULT -> pickDefaultChoice(choices, quality)
    EnqueueMode.AUDIO -> pickDefaultChoice(choices, DefaultQuality.AUDIO)
    EnqueueMode.VIDEO -> pickDefaultChoice(
        choices, if (quality == DefaultQuality.ASK || quality == DefaultQuality.AUDIO) DefaultQuality.BEST else quality,
    )
}

/**
 * Resolves a YouTube video, picks its format, builds the download row and queues it through
 * [DownloadController] (which applies the Wi-Fi, charging and window rules). Needs no UI, so the
 * background checker can use it too.
 */
@Singleton
class YouTubeEnqueuer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val resolver: YouTubeResolver,
    private val downloads: DownloadController,
    private val dao: DownloadDao,
    private val settings: SettingsStore,
    @ApiHttpClient private val http: OkHttpClient,
) {
    /**
     * Resolves [videoUrlOrId] and queues it per [mode]. [quality] overrides the default-quality setting.
     * Resolve failures throw (callers map them to messages); queueing failures return [EnqueueResult.FAILED].
     */
    suspend fun enqueue(videoUrlOrId: String, mode: EnqueueMode = EnqueueMode.DEFAULT, quality: DefaultQuality? = null): EnqueueResult {
        val video = resolver.resolve(videoUrlOrId)
        val choices = YouTubeFormats.choices(video.streams, Build.VERSION.SDK_INT, video.durationSec)
        val pick = choiceForMode(choices, mode, quality ?: settings.youTubeQualityFlow.first()) ?: return EnqueueResult.FAILED
        return tryEnqueue(itemFor(video, pick), video.thumbnailUrl)
    }

    fun itemFor(video: YouTubeVideo, c: FormatChoice): DownloadItem = youTubeDownloadItem(
        videoId = video.id, title = video.title, uploader = video.uploader, videoItag = c.videoItag, audioItag = c.audioItag,
        extension = c.extension, height = c.height, estimatedBytes = c.estimatedBytes,
        url = video.streams.firstOrNull { it.itag == (c.videoItag ?: c.audioItag) }?.url,
    )

    /** Queues [item] and says what happened: QUEUED, ALREADY_QUEUED or FAILED. */
    suspend fun tryEnqueue(item: DownloadItem, thumbnailUrl: String?): EnqueueResult {
        try {
            // enqueue ignores an id it already has, so ask first and report what really happens.
            if (dao.getById(item.id) != null) return EnqueueResult.ALREADY_QUEUED
            downloads.enqueue(item, foreground = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return EnqueueResult.FAILED
        }
        saveYouTubeThumbnail(context, http, thumbnailUrl, item.id)
        return EnqueueResult.QUEUED
    }
}
