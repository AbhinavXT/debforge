package com.abhinavxt.debforge.ui.media

import kotlinx.coroutines.withContext
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abhinavxt.debforge.data.local.DownloadDao
import com.abhinavxt.debforge.data.podcast.PodcastFetchException
import com.abhinavxt.debforge.data.podcast.podcastTrackId
import com.abhinavxt.debforge.data.subscriptions.AutoDownload
import com.abhinavxt.debforge.data.subscriptions.FeedItemEntity
import com.abhinavxt.debforge.data.subscriptions.SubscriptionEntity
import com.abhinavxt.debforge.data.subscriptions.SubscriptionKind
import com.abhinavxt.debforge.data.subscriptions.SubscriptionRepository
import com.abhinavxt.debforge.domain.DirectLinkParser
import com.abhinavxt.debforge.domain.DownloadState
import com.abhinavxt.debforge.download.podcast.PodcastEnqueuer
import com.abhinavxt.debforge.download.youtube.EnqueueResult
import com.abhinavxt.debforge.player.AudioPlayer
import com.abhinavxt.debforge.player.AudioTrack
import com.abhinavxt.debforge.player.EnqueueOutcome
import com.abhinavxt.debforge.player.audioTrackFor
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PodcastRow(val sub: SubscriptionEntity, val unseen: Int)

enum class EpisodeDownload { NONE, ACTIVE, DONE, FAILED }

data class EpisodeRow(val item: FeedItemEntity, val download: EpisodeDownload)

data class PodcastDetail(val sub: SubscriptionEntity, val unseen: Int, val episodes: List<EpisodeRow>)

sealed interface AddState {
    data object Idle : AddState
    data object Loading : AddState
    class Error(val message: String) : AddState
}

/** One-off feedback for the screen to show. */
enum class PodcastMessage { QUEUED, ALREADY_QUEUED, DOWNLOAD_FAILED, NO_FILE, ADDED_TO_QUEUE, ALREADY_IN_QUEUE }

/** Followed podcasts, one podcast's episodes, and what a tap on an episode does. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PodcastsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: SubscriptionRepository,
    private val downloadDao: DownloadDao,
    private val enqueuer: PodcastEnqueuer,
    private val audioPlayer: AudioPlayer,
) : ViewModel() {

    private val podcastSubs = repo.subscriptions.map { l -> l.filter { it.kind == SubscriptionKind.PODCAST } }

    val podcasts: StateFlow<List<PodcastRow>?> = combine(podcastSubs, repo.unseenBySub) { subs, unseen ->
        subs.map { PodcastRow(it, unseen[it.id] ?: 0) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _openId = MutableStateFlow<Long?>(null)
    val openId: StateFlow<Long?> = _openId

    fun open(id: Long) { _openId.value = id }
    fun close() { _openId.value = null }

    /** Download id -> state, only for completed or running rows. */
    private val downloadStates = downloadDao.observeAll().map { rows ->
        rows.filter { it.id.startsWith("direct:") }.associate { it.id to it.state }
    }.distinctUntilChanged().flowOn(Dispatchers.Default)

    val detail: StateFlow<PodcastDetail?> = _openId.flatMapLatest { id ->
        if (id == null) flowOf(null)
        else combine(podcastSubs, repo.itemsOf(id), downloadStates, repo.unseenBySub) { subs, items, dl, unseen ->
            val sub = subs.firstOrNull { it.id == id } ?: return@combine null
            PodcastDetail(sub, unseen[id] ?: 0, items.map { EpisodeRow(it, downloadOf(it, dl)) })
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun downloadOf(item: FeedItemEntity, states: Map<String, DownloadState>): EpisodeDownload {
        val url = item.mediaUrl ?: return EpisodeDownload.NONE
        return when (states[DirectLinkParser.itemId(url)]) {
            null -> EpisodeDownload.NONE
            DownloadState.COMPLETED -> EpisodeDownload.DONE
            DownloadState.FAILED -> EpisodeDownload.FAILED
            else -> EpisodeDownload.ACTIVE
        }
    }

    private val _add = MutableStateFlow<AddState>(AddState.Idle)
    val add: StateFlow<AddState> = _add

    fun resetAdd() { _add.value = AddState.Idle }

    /** Follows [input]; on success (or when already followed) opens that podcast. */
    fun follow(input: String) {
        if (_add.value == AddState.Loading) return
        _add.value = AddState.Loading
        viewModelScope.launch {
            try {
                val sub = when (val r = repo.followPodcast(input)) {
                    is SubscriptionRepository.FollowResult.Followed -> r.sub
                    is SubscriptionRepository.FollowResult.AlreadyFollowing -> r.sub
                }
                _add.value = AddState.Idle
                if (sub.kind == SubscriptionKind.PODCAST) open(sub.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: PodcastFetchException) {
                _add.value = AddState.Error(e.userMessage(context))
            } catch (e: Exception) {
                _add.value = AddState.Error(PodcastFetchException(PodcastFetchException.Kind.NETWORK).userMessage(context))
            }
        }
    }

    fun unfollow(id: Long) {
        _openId.value = null
        viewModelScope.launch { repo.unfollow(id) }
    }

    fun setAutoDownload(id: Long, mode: AutoDownload) { viewModelScope.launch { repo.setAutoDownload(id, mode) } }

    fun markAllSeen(id: Long) { viewModelScope.launch { repo.markAllSeen(id) } }

    private val _messages = Channel<PodcastMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    /** The track for an episode: the downloaded file when there is one, else the stream. Null without a playable url. */
    private suspend fun trackFor(sub: SubscriptionEntity, item: FeedItemEntity): AudioTrack? {
        val url = item.mediaUrl ?: return null
        downloadDao.getById(DirectLinkParser.itemId(url))
            ?.takeIf { it.state == DownloadState.COMPLETED }
            ?.let { withContext(Dispatchers.IO) { audioTrackFor(context, it) } }
            // Episodes downloaded before covers were saved have no artwork file; fall back to the feed's image.
            ?.let { t -> if (t.artworkFile == null) t.copy(artworkUrl = item.thumbUrl ?: sub.artworkUrl) else t }
            ?.let { return it }
        val uri = Uri.parse(url).takeIf { it.scheme == "http" || it.scheme == "https" } ?: return null
        return AudioTrack(
            id = podcastTrackId(sub.url, item.itemKey), uri = uri, title = item.title, artist = sub.title,
            artworkFile = null, artworkUrl = item.thumbUrl ?: sub.artworkUrl
        )
    }

    /** Streams (or plays the downloaded file of) [item], replacing the queue with just that episode. */
    fun play(sub: SubscriptionEntity, item: FeedItemEntity) {
        viewModelScope.launch {
            repo.markSeen(sub.id, item.itemKey)
            val track = trackFor(sub, item)
            if (track == null) { _messages.send(PodcastMessage.NO_FILE); return@launch }
            audioPlayer.play(listOf(track), 0)
        }
    }

    fun addToQueue(sub: SubscriptionEntity, item: FeedItemEntity) {
        viewModelScope.launch {
            repo.markSeen(sub.id, item.itemKey)
            val track = trackFor(sub, item)
            if (track == null) { _messages.send(PodcastMessage.NO_FILE); return@launch }
            _messages.send(
                when (audioPlayer.enqueue(track)) {
                    EnqueueOutcome.ALREADY_QUEUED -> PodcastMessage.ALREADY_IN_QUEUE
                    else -> PodcastMessage.ADDED_TO_QUEUE
                }
            )
        }
    }

    private val _bulkRunning = MutableStateFlow(false)
    /** True while a "Download all" batch is being queued; the button is disabled meanwhile. */
    val bulkRunning: StateFlow<Boolean> = _bulkRunning

    private val _bulkQueued = Channel<Int>(Channel.BUFFERED)
    /** How many episodes a "Download all" actually queued. */
    val bulkQueued = _bulkQueued.receiveAsFlow()

    /** Queues [items] one by one, in order, through the same path as a single download. */
    fun downloadAll(sub: SubscriptionEntity, items: List<FeedItemEntity>) {
        if (items.isEmpty() || !_bulkRunning.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            try {
                var queued = 0
                for (item in items) {
                    // A failed episode already has a download row; re-queue that, as the downloads screen's retry does.
                    if (enqueuer.retryIfFailed(item) || enqueuer.enqueue(sub, item) == EnqueueResult.QUEUED) queued++
                }
                if (queued > 0) _bulkQueued.send(queued) else _messages.send(PodcastMessage.DOWNLOAD_FAILED)
            } finally {
                _bulkRunning.value = false
            }
        }
    }

    fun download(sub: SubscriptionEntity, item: FeedItemEntity) {
        viewModelScope.launch {
            repo.markSeen(sub.id, item.itemKey)
            if (enqueuer.retryIfFailed(item)) {
                _messages.send(PodcastMessage.QUEUED)
                return@launch
            }
            _messages.send(
                when (enqueuer.enqueue(sub, item)) {
                    EnqueueResult.QUEUED -> PodcastMessage.QUEUED
                    EnqueueResult.ALREADY_QUEUED -> PodcastMessage.ALREADY_QUEUED
                    EnqueueResult.NO_FORMAT -> PodcastMessage.NO_FILE
                    else -> PodcastMessage.DOWNLOAD_FAILED
                }
            )
        }
    }
}
