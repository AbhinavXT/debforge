package com.abhinavxt.debforge.ui.video

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abhinavxt.debforge.data.prefs.SettingsStore
import com.abhinavxt.debforge.data.subscriptions.SubscriptionRepository
import com.abhinavxt.debforge.domain.FormatChoice
import com.abhinavxt.debforge.domain.YouTubeChannelLinks
import com.abhinavxt.debforge.domain.YouTubeFormats
import com.abhinavxt.debforge.domain.YouTubeSubtitle
import com.abhinavxt.debforge.domain.YouTubeVideo
import com.abhinavxt.debforge.domain.pickDefaultChoice
import com.abhinavxt.debforge.ui.youtube.isAudio
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Activity-scoped details for the watch page of a YouTube video: its quality rows, subtitle choice and the
 * channel's follow state. [show] is called by the host when a YouTube video starts (null otherwise); the page reads [details].
 */
@HiltViewModel
class WatchViewModel @Inject constructor(
    private val subscriptions: SubscriptionRepository,
    private val settings: SettingsStore,
) : ViewModel() {

    private data class Base(
        val video: YouTubeVideo? = null,
        val choices: List<FormatChoice> = emptyList(),
        val selected: FormatChoice? = null,
        val subtitle: YouTubeSubtitle? = null,
        val canonicalChannel: String? = null,
        val loading: Boolean = false,
    )

    private val base = MutableStateFlow(Base())
    private val busy = MutableStateFlow(false)

    private val followedUrls: StateFlow<Set<String>> = subscriptions.subscriptions
        .map { list -> list.map { it.url }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val details: StateFlow<WatchDetails> = combine(base, followedUrls, busy) { b, followed, isBusy ->
        val v = b.video
        WatchDetails(
            video = v,
            channelName = v?.uploader.orEmpty(),
            channelUrl = v?.uploaderUrl,
            following = if (v?.uploaderUrl != null && b.canonicalChannel != null) b.canonicalChannel in followed else null,
            followBusy = isBusy,
            formats = if (v != null && b.selected != null) WatchFormats(b.choices, b.selected, v.subtitles, b.subtitle) else null,
            loading = b.loading,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WatchDetails())

    /** The YouTube video now playing (null: something else, or nothing). [loading] while it is still being resolved. */
    fun show(video: YouTubeVideo?, loading: Boolean = false) {
        if (video == null) { base.value = Base(loading = loading); return }
        val choices = YouTubeFormats.choices(video.streams, Build.VERSION.SDK_INT, video.durationSec)
        base.value = Base(video, choices, null, loading = loading, canonicalChannel = video.uploaderUrl?.let { YouTubeChannelLinks.canonicalUrl(it) })
        viewModelScope.launch {
            val pick = pickDefaultChoice(choices, settings.youTubeQualityFlow.first()) ?: choices.firstOrNull()
            base.update { if (it.video?.id == video.id) it.copy(selected = pick) else it }
            val url = video.uploaderUrl ?: return@launch
            if (base.value.canonicalChannel == null) {
                val canonical = runCatching { subscriptions.canonicalChannelUrl(url) }.getOrNull()
                base.update { if (it.video?.id == video.id) it.copy(canonicalChannel = canonical) else it }
            }
        }
    }

    /** Another video is being looked up ([loading]) or that failed: the page keeps what it showed underneath. */
    fun setLoading(loading: Boolean) = base.update { it.copy(loading = loading) }

    fun select(choice: FormatChoice) = base.update { it.copy(selected = choice) }

    fun selectSubtitle(s: YouTubeSubtitle?) = base.update { it.copy(subtitle = s) }

    /** Video | Audio switch: that mode's default (first) row. */
    fun selectMode(audio: Boolean) {
        val b = base.value
        if (b.selected?.isAudio == audio) return
        val inMode = b.choices.filter { it.isAudio == audio }
        (inMode.firstOrNull { it.isDefault } ?: inMode.firstOrNull())?.let(::select)
    }

    /** Follows the playing video's channel, or unfollows it. Returns false when it failed. */
    fun toggleFollow(onFailed: () -> Unit = {}) {
        val b = base.value
        val url = b.video?.uploaderUrl ?: return
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                val canonical = b.canonicalChannel ?: subscriptions.canonicalChannelUrl(url)
                val existing = canonical?.let { subscriptions.followedChannel(it) }
                if (existing != null) {
                    subscriptions.unfollow(existing.id)
                } else {
                    when (val r = subscriptions.follow(url)) {
                        is SubscriptionRepository.FollowResult.Followed -> base.update { it.copy(canonicalChannel = r.sub.url) }
                        is SubscriptionRepository.FollowResult.AlreadyFollowing -> base.update { it.copy(canonicalChannel = r.sub.url) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onFailed()
            } finally {
                busy.value = false
            }
        }
    }
}
