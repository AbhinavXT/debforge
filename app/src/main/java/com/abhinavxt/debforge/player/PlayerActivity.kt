package com.abhinavxt.debforge.player

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.lifecycle.lifecycleScope
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.data.playback.PlaybackEntity
import com.abhinavxt.debforge.data.playback.PlaybackPositions
import com.abhinavxt.debforge.data.repository.DownloadsRepository
import com.abhinavxt.debforge.domain.DataResult
import com.abhinavxt.debforge.domain.LinkRefresh
import com.abhinavxt.debforge.domain.ProviderId
import com.abhinavxt.debforge.domain.Resume
import com.abhinavxt.debforge.domain.SubtitleLink
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.abhinavxt.debforge.ui.browse.PlayRequest
import com.abhinavxt.debforge.ui.browse.playExternally

/**
 * DebForge's own video player (Media3 ExoPlayer + PlayerView): play/pause,
 * seek bar, playback speed, audio and subtitle track pickers, and Android TV
 * D-pad control, all from PlayerView.
 *
 * Decoding uses the device's own codecs (no ffmpeg extension, so the APK and
 * the reproducible F-Droid build stay simple). When a file or its audio
 * can't be decoded on this device (DTS / TrueHD on many phones, some HEVC
 * profiles), the user is offered their external player (VLC, mpv…) instead
 * of a black screen or silent video.
 *
 * Positions are saved per file (every few seconds, on leaving, at the end)
 * and restored on the next play, with a "Start over" chip. Later steps: the
 * torrent's own subtitle files, next episode, link refresh.
 */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class PlayerActivity : ComponentActivity() {

    @Inject lateinit var positions: PlaybackPositions
    @Inject lateinit var upNext: UpNext
    @Inject lateinit var subtitleResolver: SubtitleResolver
    @Inject lateinit var repository: DownloadsRepository
    @Inject lateinit var settings: com.abhinavxt.debforge.data.prefs.SettingsStore

    /** Settings → "Autoplay next episode". Off: the card still offers Play now, without a countdown. */
    private var autoplay = true

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var titleView: TextView
    private lateinit var startOverView: TextView

    /** Which file this is (null for plain links): positions are saved only when known. */
    private var identity: Identity? = null
    private var tickJob: Job? = null

    // --- next episode ---
    /** Episodes after this one (same show), best copy of each. */
    private val queue = ArrayDeque<QueuedEpisode>()
    /** The next episode with its links fetched ahead of time. */
    private var prepared: Prepared? = null
    private var prefetchJob: Job? = null
    /** User tapped Cancel on the card: no autoplay for this file. */
    private var nextCancelled = false
    /** Switching files: the countdown and "ended" can both fire; only one may. */
    private var switching = false
    private lateinit var nextPlayNow: View

    /** Quiet link refreshes after network errors (expired debrid links). */
    private var refreshState = LinkRefresh.State()
    private var refreshing = false

    /** Android TV: move focus to overlays when they appear (no touch screen). */
    private val isTv by lazy {
        (getSystemService(UI_MODE_SERVICE) as android.app.UiModeManager).currentModeType ==
            android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    }

    /** Back while "Next: …" is showing cancels it instead of leaving the player. */
    private val cancelNextOnBack = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            nextCancelled = true
            hideNextCard()
        }
    }
    /** When the card appeared (elapsedRealtime), for its countdown; null = hidden. */
    private var cardShownAt: Long? = null
    private lateinit var nextCard: View
    private lateinit var nextTitle: TextView
    private lateinit var nextCountdown: TextView

    private data class Prepared(val episode: QueuedEpisode, val url: String, val subtitles: List<SubtitleLink>)

    private lateinit var uri: Uri
    private var title: String = ""
    private var subtitles: List<SubtitleLink> = emptyList()

    // Survive onStop/onStart (app switch) and rotation-free recreation.
    private var resumePosition = 0L
    private var resumePlayWhenReady = true
    /** Asked once per file whether to switch players: don't nag on every track change. */
    private var offeredFallback = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uri = intent.data ?: run { finish(); return }
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        identity = Identity.from(intent)
        subtitles = subtitlesFrom(intent)
        identity?.let { queue.addAll(upNext.take(it.itemId)) }
        lifecycleScope.launch { autoplay = settings.autoplayNextFlow.first() }
        savedInstanceState?.let {
            resumePosition = it.getLong(STATE_POSITION)
            resumePlayWhenReady = it.getBoolean(STATE_PLAY_WHEN_READY, true)
            offeredFallback = it.getBoolean(STATE_OFFERED, false)
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.decorView.setBackgroundColor(Color.BLACK)

        playerView = PlayerView(this).apply {
            setShowSubtitleButton(true)
            setShowNextButton(false)
            setShowPreviousButton(false)
            keepScreenOn = true
            setBackgroundColor(Color.BLACK)
        }
        titleView = TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            val pad = dp(16)
            setPadding(pad, pad, pad, dp(28))
            // Soft top scrim so the title reads over bright video.
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x99000000.toInt(), Color.TRANSPARENT)
            )
        }
        // The title shows and hides together with the controls.
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            titleView.visibility = visibility
            if (visibility == View.VISIBLE) showSystemBars() else hideSystemBars()
        })

        // "Start over" after resuming mid-way; hides itself after a few seconds.
        startOverView = TextView(this).apply {
            text = getString(R.string.player_start_over)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            val h = dp(16); val v = dp(10)
            setPadding(h, v, h, v)
            background = focusable(0xCC202020.toInt(), dp(20))
            visibility = View.GONE
            isFocusable = true // reachable with the TV remote
            setOnClickListener {
                player?.seekTo(0)
                visibility = View.GONE
            }
        }

        nextCard = buildNextCard()

        setContentView(FrameLayout(this).apply {
            addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(
                titleView,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP)
            )
            addView(
                startOverView,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START)
                    .apply { setMargins(dp(24), 0, 0, dp(96)) }
            )
            addView(
                nextCard,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END)
                    .apply { setMargins(0, 0, dp(24), dp(96)) }
            )
        })
        hideSystemBars()
        onBackPressedDispatcher.addCallback(this, cancelNextOnBack)

        // Fresh start (not a rotation/recreate): pick up where the user left off.
        if (savedInstanceState == null) restoreSavedPosition()
    }

    /** Looks up the saved position; seeks there once known, unless the user already moved. */
    private fun restoreSavedPosition() {
        val id = identity ?: return
        lifecycleScope.launch {
            val saved = positions.get(id.itemId) ?: return@launch
            val at = Resume.resumeAt(saved.positionMs, saved.durationMs, saved.finished)
            if (at <= 0) return@launch
            val p = player
            if (p == null) {
                resumePosition = at
            } else if (p.currentPosition < Resume.MIN_RESUME_MS) {
                p.seekTo(at)
            } else {
                return@launch
            }
            startOverView.visibility = View.VISIBLE
            if (isTv) startOverView.requestFocus()
            delay(START_OVER_VISIBLE_MS)
            startOverView.visibility = View.GONE
        }
    }

    /**
     * Once a second: the next-episode card and prefetch; every few seconds
     * while playing, save the position (so a crash or a swipe-away loses little).
     */
    private fun startTicking() {
        tickJob?.cancel()
        tickJob = lifecycleScope.launch {
            var ticks = 0
            while (true) {
                delay(1_000)
                ticks++
                if (ticks % SAVE_EVERY_TICKS == 0 && player?.isPlaying == true) savePosition()
                updateNextEpisode()
            }
        }
    }

    /** Prefetch near the end, then show the card and count down to the next episode. */
    private fun updateNextEpisode() {
        if (switching) return
        val p = player ?: return
        val next = queue.firstOrNull()
        val duration = p.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        if (next == null || duration == null || nextCancelled) return hideNextCard()
        val remaining = duration - p.currentPosition
        if (remaining <= PREFETCH_BEFORE_END_MS) prefetch(next)
        if (remaining > CARD_BEFORE_END_MS) return hideNextCard() // e.g. seeked back
        val shownAt = cardShownAt ?: android.os.SystemClock.elapsedRealtime().also {
            cardShownAt = it
            nextTitle.text = getString(R.string.player_next, next.title)
            nextCard.visibility = View.VISIBLE
            cancelNextOnBack.isEnabled = true
            if (isTv) nextPlayNow.requestFocus()
        }
        if (!autoplay) {
            // Offer only: the user starts it with Play now.
            nextCountdown.visibility = View.GONE
            return
        }
        nextCountdown.visibility = View.VISIBLE
        val left = ((COUNTDOWN_MS - (android.os.SystemClock.elapsedRealtime() - shownAt)) / 1000).toInt()
        if (left <= 0) {
            playNext()
        } else {
            nextCountdown.text = resources.getQuantityString(R.plurals.player_next_in, left, left)
        }
    }

    private fun hideNextCard() {
        cardShownAt = null
        cancelNextOnBack.isEnabled = false
        if (::nextCard.isInitialized) nextCard.visibility = View.GONE
    }

    /** Rounded background with a white ring when focused (TV remote). */
    private fun focusable(color: Int, radius: Int): android.graphics.drawable.Drawable {
        fun shape(ring: Boolean) = GradientDrawable().apply {
            cornerRadius = radius.toFloat()
            setColor(color)
            if (ring) setStroke(dp(2), Color.WHITE)
        }
        return android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(ring = true))
            addState(intArrayOf(), shape(ring = false))
        }
    }

    /**
     * Keys go to PlayerView (D-pad shows the controls, media keys play and
     * pause), except while one of DebForge's own overlays has focus.
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val focus = currentFocus
        val overlayFocused = focus != null && (focus === startOverView || isInside(focus, nextCard))
        if (!overlayFocused && ::playerView.isInitialized && playerView.dispatchKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    private fun isInside(view: View, parent: View): Boolean {
        var v: View? = view
        while (v != null) {
            if (v === parent) return true
            v = v.parent as? View
        }
        return false
    }

    /**
     * The stream failed with a network error: most often the debrid link
     * expired (a long pause, or hours in). Get a fresh one for the same
     * file, and fresh subtitle links, and continue from the same second.
     * Capped by [LinkRefresh]; after that, or for plain links, the Retry
     * dialog.
     */
    private fun refreshLinkOrAsk() {
        val id = identity
        val next = LinkRefresh.next(refreshState, android.os.SystemClock.elapsedRealtime())
        if (id == null || next == null || refreshing) {
            if (!refreshing) showNetworkError()
            return
        }
        refreshState = next
        refreshing = true
        val position = player?.currentPosition ?: resumePosition
        lifecycleScope.launch {
            try {
                val fresh = (repository.resolveLink(id.provider, id.sourceRef) as? DataResult.Success)?.data?.url
                if (fresh == null || id != identity) {
                    if (id == identity) showNetworkError()
                    return@launch
                }
                uri = Uri.parse(fresh)
                subtitles = subtitleResolver.refresh(subtitles)
                player?.apply {
                    setMediaItem(buildMediaItem(), position)
                    prepare()
                    play()
                }
            } finally {
                refreshing = false
            }
        }
    }

    /** Fetches the next episode's link and subtitles once, ahead of time. */
    private fun prefetch(episode: QueuedEpisode) {
        if (prefetchJob != null || prepared?.episode == episode) return
        prefetchJob = lifecycleScope.launch {
            prepared = prepare(episode)
            prefetchJob = null
        }
    }

    private suspend fun prepare(episode: QueuedEpisode): Prepared? {
        val item = episode.item
        val url = item.downloadUrl
            ?: (repository.resolveLink(item.provider, item.sourceRef) as? DataResult.Success)?.data?.url
            ?: return null
        return Prepared(episode, url, subtitleResolver.resolve(episode.subtitles))
    }

    /**
     * Switches to the next episode in this same player: marks the current one
     * watched, then plays the next from its saved position (if any).
     */
    private fun playNext() {
        if (switching) return
        val episode = queue.firstOrNull() ?: return
        switching = true
        hideNextCard()
        savePosition(ended = true)
        player?.pause()
        lifecycleScope.launch {
            try {
                switchTo(episode)
            } finally {
                switching = false
            }
        }
    }

    private suspend fun switchTo(episode: QueuedEpisode) {
        prefetchJob?.join()
        val ready = prepared?.takeIf { it.episode == episode } ?: prepare(episode)
        prepared = null
        if (ready == null) {
            Toast.makeText(this@PlayerActivity, R.string.player_next_failed, Toast.LENGTH_LONG).show()
            return
        }
        queue.removeFirst()
        val item = episode.item
        identity = Identity(item.id, item.provider, item.sourceRef, item.filename, episode.showKey, item.parentRef)
        uri = Uri.parse(ready.url)
        title = episode.title
        subtitles = ready.subtitles
        titleView.text = title
        offeredFallback = false
        nextCancelled = false
        refreshState = LinkRefresh.State()
        val saved = positions.get(item.id)
        val at = saved?.let { Resume.resumeAt(it.positionMs, it.durationMs, it.finished) } ?: 0L
        player?.apply {
            setMediaItem(buildMediaItem(), at)
            prepare()
            play()
        }
    }

    private fun buildNextCard(): View {
        nextTitle = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = dp(280)
        }
        nextCountdown = TextView(this).apply {
            setTextColor(0xCCFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(2), 0, dp(10))
        }
        fun pill(textRes: Int, primary: Boolean, onClick: () -> Unit) = TextView(this).apply {
            text = getString(textRes)
            setTextColor(if (primary) Color.BLACK else Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            val h = dp(16); val v = dp(8)
            setPadding(h, v, h, v)
            background = focusable(if (primary) Color.WHITE else 0x33FFFFFF, dp(18))
            isFocusable = true // TV remote
            setOnClickListener { onClick() }
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            nextPlayNow = pill(R.string.player_play_now, primary = true) { playNext() }
            addView(nextPlayNow)
            addView(
                pill(R.string.action_cancel, primary = false) {
                    nextCancelled = true
                    hideNextCard()
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { marginStart = dp(8) }
            )
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(0xE0181818.toInt())
            }
            addView(nextTitle)
            addView(nextCountdown)
            addView(buttons)
            visibility = View.GONE
        }
    }

    private fun savePosition(ended: Boolean = false) {
        val id = identity ?: return
        val p = player ?: return
        val duration = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        val position = if (ended) duration else p.currentPosition.coerceIn(0, duration)
        positions.save(
            PlaybackEntity(
                itemId = id.itemId,
                provider = id.provider,
                sourceRef = id.sourceRef,
                filename = id.filename,
                title = title,
                showKey = id.showKey,
                parentRef = id.parentRef,
                positionMs = position,
                durationMs = duration,
                finished = ended || Resume.isFinished(position, duration),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    override fun onStart() {
        super.onStart()
        initializePlayer()
        playerView.onResume()
        startTicking()
    }

    override fun onStop() {
        tickJob?.cancel()
        savePosition()
        playerView.onPause()
        releasePlayer()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        player?.let {
            resumePosition = it.currentPosition
            resumePlayWhenReady = it.playWhenReady
        }
        outState.putLong(STATE_POSITION, resumePosition)
        outState.putBoolean(STATE_PLAY_WHEN_READY, resumePlayWhenReady)
        outState.putBoolean(STATE_OFFERED, offeredFallback)
    }

    private fun initializePlayer() {
        if (player != null) return
        val exo = ExoPlayer.Builder(this)
            // Pause for calls / other apps' audio, and when headphones come out.
            .setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        exo.addListener(listener)
        exo.setMediaItem(buildMediaItem())
        exo.seekTo(resumePosition)
        exo.playWhenReady = resumePlayWhenReady
        exo.prepare()
        playerView.player = exo
        player = exo
    }

    /** The current file, with the torrent's own subtitle files (pick them with the CC button). */
    private fun buildMediaItem(): MediaItem =
        MediaItem.Builder()
            .setUri(uri)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
            .setSubtitleConfigurations(subtitles.map { sub ->
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub.url))
                    .setMimeType(sub.mimeType)
                    .setLanguage(sub.language)
                    .setLabel(sub.label)
                    // Forced subs (signs, foreign dialogue) show without being picked.
                    .setSelectionFlags(if (sub.forced) C.SELECTION_FLAG_FORCED else 0)
                    .build()
            })
            .build()

    private fun releasePlayer() {
        val exo = player ?: return
        resumePosition = exo.currentPosition
        resumePlayWhenReady = exo.playWhenReady
        exo.removeListener(listener)
        exo.release()
        playerView.player = null
        player = null
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) {
                savePosition(ended = true)
                // Reached the end before the countdown did (e.g. seeked there).
                if (autoplay && queue.isNotEmpty() && !nextCancelled) playNext()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            when (PlayerErrors.kindOf(error.errorCode)) {
                PlayerErrors.Kind.UNSUPPORTED -> offerExternal(getString(R.string.player_unsupported_body))
                // Most likely an expired link or a dropped connection.
                PlayerErrors.Kind.NETWORK -> refreshLinkOrAsk()
                PlayerErrors.Kind.OTHER -> offerExternal(getString(R.string.player_error_body, error.errorCodeName))
            }
        }

        /**
         * Video plays but none of its audio tracks can be decoded here (DTS,
         * TrueHD…): ExoPlayer would silently play without sound.
         */
        override fun onTracksChanged(tracks: Tracks) {
            val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
            if (audio.isNotEmpty() && audio.none { it.isSupported }) {
                offerExternal(getString(R.string.player_no_audio_body))
            }
        }
    }

    /** "Can't play this here" → hand the same link to VLC & co. */
    private fun offerExternal(message: String) {
        if (offeredFallback || isFinishing) return
        offeredFallback = true
        player?.pause()
        AlertDialog.Builder(this)
            .setTitle(R.string.player_unsupported_title)
            .setMessage(message)
            .setPositiveButton(R.string.action_open_other_player) { _, _ -> openExternally() }
            .setNegativeButton(R.string.player_keep_watching) { _, _ -> player?.play() }
            .setOnCancelListener { player?.play() }
            .show()
    }

    private fun openExternally() {
        if (!playExternally(PlayRequest(uri, title, subtitles = subtitles))) {
            Toast.makeText(this, R.string.msg_no_player, Toast.LENGTH_LONG).show()
            player?.play()
            return
        }
        // Nothing to come back to: the other player has it now.
        finish()
    }

    private fun showNetworkError() {
        if (isFinishing) return
        AlertDialog.Builder(this)
            .setTitle(R.string.player_network_title)
            .setMessage(R.string.player_network_body)
            .setPositiveButton(R.string.action_retry) { _, _ ->
                if (identity != null) {
                    // A fresh link, with a fresh budget: the user asked for it.
                    refreshState = LinkRefresh.State()
                    refreshLinkOrAsk()
                } else {
                    player?.let {
                        resumePosition = it.currentPosition
                        it.prepare()
                        it.play()
                    }
                }
            }
            .setNegativeButton(R.string.action_close) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun showSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** The file being played, from the intent; null for a plain link. */
    private data class Identity(
        val itemId: String,
        val provider: ProviderId,
        val sourceRef: String,
        val filename: String,
        val showKey: String?,
        val parentRef: String? = null
    ) {
        companion object {
            fun from(intent: Intent): Identity? {
                val id = intent.getStringExtra(EXTRA_ITEM_ID) ?: return null
                val provider = ProviderId.fromName(intent.getStringExtra(EXTRA_PROVIDER)) ?: return null
                return Identity(
                    itemId = id,
                    provider = provider,
                    sourceRef = intent.getStringExtra(EXTRA_SOURCE_REF) ?: return null,
                    filename = intent.getStringExtra(EXTRA_FILENAME).orEmpty(),
                    showKey = intent.getStringExtra(EXTRA_SHOW_KEY),
                    parentRef = intent.getStringExtra(EXTRA_PARENT_REF)
                )
            }
        }
    }

    companion object {
        private const val SAVE_EVERY_TICKS = 5
        /** Fetch the next episode's links this long before the end. */
        private const val PREFETCH_BEFORE_END_MS = 90_000L
        /** Show "Next: …" this long before the end (end credits). */
        private const val CARD_BEFORE_END_MS = 30_000L
        /** The card counts down this long, then the next episode starts. */
        private const val COUNTDOWN_MS = 10_000L
        private const val START_OVER_VISIBLE_MS = 8_000L
        private const val EXTRA_ITEM_ID = "itemId"
        private const val EXTRA_PROVIDER = "provider"
        private const val EXTRA_SOURCE_REF = "sourceRef"
        private const val EXTRA_FILENAME = "filename"
        private const val EXTRA_SHOW_KEY = "showKey"
        private const val EXTRA_PARENT_REF = "parentRef"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_SUB_URLS = "subUrls"
        private const val EXTRA_SUB_MIMES = "subMimes"
        private const val EXTRA_SUB_LANGS = "subLangs"
        private const val EXTRA_SUB_LABELS = "subLabels"
        private const val EXTRA_SUB_FORCED = "subForced"
        private const val EXTRA_SUB_PROVIDERS = "subProviders"
        private const val EXTRA_SUB_REFS = "subRefs"

        /** Parallel arrays: no Parcelable class needed for a handful of strings. */
        private fun subtitlesFrom(intent: Intent): List<SubtitleLink> {
            val urls = intent.getStringArrayExtra(EXTRA_SUB_URLS) ?: return emptyList()
            val mimes = intent.getStringArrayExtra(EXTRA_SUB_MIMES) ?: return emptyList()
            val langs = intent.getStringArrayExtra(EXTRA_SUB_LANGS)
            val labels = intent.getStringArrayExtra(EXTRA_SUB_LABELS)
            val forced = intent.getBooleanArrayExtra(EXTRA_SUB_FORCED)
            val providers = intent.getStringArrayExtra(EXTRA_SUB_PROVIDERS)
            val refs = intent.getStringArrayExtra(EXTRA_SUB_REFS)
            return urls.indices.mapNotNull { i ->
                SubtitleLink(
                    url = urls[i],
                    mimeType = mimes.getOrNull(i) ?: return@mapNotNull null,
                    language = langs?.getOrNull(i)?.ifEmpty { null },
                    label = labels?.getOrNull(i).orEmpty(),
                    forced = forced?.getOrNull(i) == true,
                    provider = ProviderId.fromName(providers?.getOrNull(i)),
                    sourceRef = refs?.getOrNull(i)?.ifEmpty { null }
                )
            }
        }
        private const val STATE_POSITION = "position"
        private const val STATE_PLAY_WHEN_READY = "playWhenReady"
        private const val STATE_OFFERED = "offeredFallback"

        fun intent(context: Context, request: PlayRequest): Intent =
            Intent(context, PlayerActivity::class.java)
                .setData(request.uri)
                .putExtra(EXTRA_TITLE, request.title)
                .apply {
                    request.item?.let { item ->
                        putExtra(EXTRA_ITEM_ID, item.id)
                        putExtra(EXTRA_PROVIDER, item.provider.name)
                        putExtra(EXTRA_SOURCE_REF, item.sourceRef)
                        putExtra(EXTRA_FILENAME, item.filename)
                        putExtra(EXTRA_SHOW_KEY, request.showKey)
                        putExtra(EXTRA_PARENT_REF, item.parentRef)
                    }
                    if (request.subtitles.isNotEmpty()) {
                        val subs = request.subtitles
                        putExtra(EXTRA_SUB_URLS, subs.map { it.url }.toTypedArray())
                        putExtra(EXTRA_SUB_MIMES, subs.map { it.mimeType }.toTypedArray())
                        putExtra(EXTRA_SUB_LANGS, subs.map { it.language.orEmpty() }.toTypedArray())
                        putExtra(EXTRA_SUB_LABELS, subs.map { it.label }.toTypedArray())
                        putExtra(EXTRA_SUB_FORCED, subs.map { it.forced }.toBooleanArray())
                        putExtra(EXTRA_SUB_PROVIDERS, subs.map { it.provider?.name.orEmpty() }.toTypedArray())
                        putExtra(EXTRA_SUB_REFS, subs.map { it.sourceRef.orEmpty() }.toTypedArray())
                    }
                }
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
