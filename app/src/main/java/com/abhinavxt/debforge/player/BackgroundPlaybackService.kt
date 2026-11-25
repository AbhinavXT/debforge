package com.abhinavxt.debforge.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media3.session.DefaultMediaNotificationProvider
import kotlinx.coroutines.delay
import android.os.Bundle
import com.abhinavxt.debforge.R
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.abhinavxt.debforge.data.playback.AudioLists
import com.abhinavxt.debforge.data.prefs.SettingsStore
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the player's sound going after the user leaves it (Home, another
 * app, screen off) when "Keep playing in the background" is on.
 *
 * The player itself stays owned by the video engine
 * ([com.abhinavxt.debforge.player.session.VideoSession]; or the audio player),
 * which hands it over in [BackgroundPlayback.player] before starting this
 * service and takes it back (stopping the service) when its screen returns. The service only adds a media
 * session around it: Media3 then shows the media notification, keeps the
 * process in the foreground while playing, and wires lock-screen, Bluetooth
 * and headset controls.
 */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class BackgroundPlaybackService : MediaSessionService() {

    @Inject lateinit var settings: SettingsStore
    @Inject lateinit var audio: AudioPlayer
    @Inject lateinit var audioLists: AudioLists

    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The video's notification: a TV returns to its player screen, a phone or tablet to the app's watch page. */
    private fun videoOpenIntent(): Intent {
        val tv = (getSystemService(UI_MODE_SERVICE) as android.app.UiModeManager).currentModeType ==
            android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
        return if (tv) {
            Intent(this, PlayerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        } else {
            Intent(this, com.abhinavxt.debforge.MainActivity::class.java)
                .putExtra(VideoLauncher.EXTRA_OPEN_VIDEO, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
    }

    override fun onCreate() {
        super.onCreate()
        BackgroundPlayback.service = this
        val player = BackgroundPlayback.player ?: return // onStartCommand ends the service (after any required startForeground)
        // Tapping the notification returns to the player where it left off
        // (audio: the app with "now playing" open).
        val open = PendingIntent.getActivity(
            this, 0,
            BackgroundPlayback.openIntent ?: videoOpenIntent(),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // The audio player (it sets openIntent) gets skip, favourite, sleep and speed buttons by choice; video gets skip.
        isAudio = BackgroundPlayback.openIntent != null
        session = MediaSession.Builder(this, player).setSessionActivity(open).setCallback(ButtonCallback()).build()
            .also { addSession(it) }
        if (isAudio) {
            scope.launch { settings.keepPlayingSwipedFlow.collect { keepPlayingSwiped = it } }
            scope.launch {
                combine(
                    settings.audioSkipSecondsFlow, settings.notificationButtonsFlow,
                    audio.state.map { AudioButtonState(it.track?.id, it.track?.youtube == null, it.speed, it.sleepLeftMs != null || it.sleepAtEnd) }.distinctUntilChanged(),
                    audioLists.state.map { it.favourites }.distinctUntilChanged()
                ) { seconds, choice, st, favs -> audioButtons(seconds, choice, st, st.id != null && st.id in favs) }
                    .collect { applyButtons(it) }
            }
        } else {
            val listener = object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_AVAILABLE_COMMANDS_CHANGED)) applyButtons(videoButtons())
                }
            }
            player.addListener(listener)
            videoListener = listener
            scope.launch { settings.audioSkipSecondsFlow.collect { currentSeconds = validSkipSeconds(it); applyButtons(videoButtons()) } }
        }
    }

    private var isAudio = false
    @Volatile private var keepPlayingSwiped = false
    private var videoListener: Player.Listener? = null
    @Volatile private var currentButtons: ImmutableList<CommandButton> = ImmutableList.of()

    private fun applyButtons(buttons: ImmutableList<CommandButton>) {
        currentButtons = buttons
        session?.setMediaButtonPreferences(buttons)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService() obliges startForeground() within seconds, whatever happens next (player
        // gone, playback failed). Post a placeholder on Media3's own channel and id; its notification replaces it.
        val foregroundRequired = intent?.getBooleanExtra(BackgroundPlayback.EXTRA_FOREGROUND_REQUIRED, false) == true
        // A stop asked for before this point was held back (stopping first would crash the app): do it now.
        val stopRequested = foregroundRequired && run {
            startForegroundPlaceholder()
            BackgroundPlayback.foregroundStart.onForeground()
        }
        if (session == null || stopRequested) { // no player was handed over, or it was cleared meanwhile
            if (!foregroundRequired) runCatching { startForegroundPlaceholder() } // Media3 may have started us in the foreground
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        // Headset/Bluetooth buttons arrive through Media3's PendingIntent.getForegroundService, and Media3 may drop the
        // key without going foreground, which kills the app (androidx/media#2768). Answer it first, then let Media3
        // redraw its own notification: the media one while playing, or out of the foreground when not.
        val mediaButton = intent?.action == Intent.ACTION_MEDIA_BUTTON
        if (mediaButton) runCatching { startForegroundPlaceholder() }
        super.onStartCommand(intent, flags, startId)
        if (mediaButton) session?.let { onUpdateNotification(it, false) }
        if (foregroundRequired) {
            scope.launch { // playback that never began leaves no media notification to take its place
                delay(PLACEHOLDER_GRACE_MS)
                val state = session?.player?.playbackState
                if (state == null || state == Player.STATE_IDLE) stopForeground(STOP_FOREGROUND_REMOVE)
            }
        }
        // The player lives in the app process, so a service restarted after the process died has nothing to play.
        return START_NOT_STICKY
    }

    private fun startForegroundPlaceholder() {
        val nm = NotificationManagerCompat.from(this)
        val channel = DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID
        if (nm.getNotificationChannel(channel) == null) {
            nm.createNotificationChannel(
                NotificationChannelCompat.Builder(channel, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName(getString(androidx.media3.session.R.string.default_notification_channel_name))
                    .build()
            )
        }
        val notification = NotificationCompat.Builder(this, channel)
            .setSmallIcon(androidx.media3.session.R.drawable.media3_notification_small_icon)
            .setOngoing(true)
            .setSilent(true)
            .build()
        startForeground(
            DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
    }

    /**
     * Ends the service. Media3 starts it in the foreground by itself when playback begins (not through
     * [BackgroundPlayback.startForeground], so the gate knows nothing of it) and owes startForeground() until its
     * first notification is up: stopping before that kills the app. A placeholder satisfies that debt first.
     */
    fun stopSafely() {
        runCatching { startForegroundPlaceholder() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /**
     * Swiped away from recents: stop the sound with it, unless audio is playing and the user chose to keep it going
     * (then nothing changes: the service stays in the foreground with its notification, and Stop still ends it).
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val playing = session?.player?.let { wantsToPlay(it.playWhenReady, it.playbackState) } == true
        if (swipeAction(isAudio, keepPlayingSwiped, playing) == SwipeAction.KEEP_PLAYING) return
        session?.player?.pause()
        stopSafely()
    }

    override fun onDestroy() {
        // The player belongs to the activity: release only the session.
        videoListener?.let { session?.player?.removeListener(it) }
        session?.let {
            removeSession(it)
            it.release()
        }
        session = null
        scope.cancel()
        if (BackgroundPlayback.service === this) BackgroundPlayback.service = null
        super.onDestroy()
    }

    /** Lets every controller (notification, lock screen, Bluetooth) use the custom button commands. */
    private inner class ButtonCallback : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().apply {
                ButtonCommands.ALL.forEach { add(it) }
            }.build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .setMediaButtonPreferences(currentButtons)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                ButtonCommands.BACK.customAction -> skip(session.player, -1)
                ButtonCommands.FORWARD.customAction -> skip(session.player, 1)
                ButtonCommands.FAVOURITE.customAction -> if (isAudio) audio.state.value.track?.takeIf { it.youtube == null }?.let { t ->
                    scope.launch { audioLists.toggleFavourite(t.id) }
                }
                ButtonCommands.SLEEP.customAction -> if (isAudio) audio.cycleSleepTimer()
                ButtonCommands.SPEED.customAction -> if (isAudio) audio.cycleSpeed()
                else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    /** Audio goes through the audio player (resume spots); video seeks the session's player, never past the end or on a live stream. */
    private fun skip(player: Player, sign: Int) {
        if (isAudio) { audio.seekBy(sign * skipMs(currentSeconds)); return }
        if (!canSkipVideo(player.isCurrentMediaItemLive, player.isCurrentMediaItemSeekable)) return
        player.seekTo(skipTarget(player.currentPosition, sign * skipMs(currentSeconds), player.duration))
    }

    @Volatile private var currentSeconds = DEFAULT_SKIP_SECONDS

    private fun videoButtons(): ImmutableList<CommandButton> {
        val p = session?.player ?: return ImmutableList.of()
        return if (canSkipVideo(p.isCurrentMediaItemLive, p.isCurrentMediaItemSeekable)) skipButtons(currentSeconds) else ImmutableList.of()
    }

    private fun audioButtons(seconds: Int, choice: NotificationButtons, st: AudioButtonState, favourite: Boolean): ImmutableList<CommandButton> {
        val skips = skipButtons(seconds)
        val out = ImmutableList.builder<CommandButton>()
        extraButtons(choice, st.canFavourite).forEach { b ->
            out.add(when (b) {
                ExtraButton.SKIP_BACK -> skips[0]
                ExtraButton.SKIP_FORWARD -> skips[1]
                ExtraButton.FAVOURITE -> button(
                    if (favourite) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED,
                    getString(if (favourite) R.string.media_fav_remove else R.string.media_fav_add), ButtonCommands.FAVOURITE
                )
                ExtraButton.SLEEP -> button(
                    CommandButton.ICON_UNDEFINED,
                    getString(if (st.sleepOn) R.string.player_sleep_timer else R.string.audio_sleep_off_cd), ButtonCommands.SLEEP,
                    if (st.sleepOn) R.drawable.ic_sleep_on else R.drawable.ic_sleep
                )
                ExtraButton.SPEED -> button(speedIcon(st.speed), getString(R.string.audio_speed_cd, speedLabel(st.speed)), ButtonCommands.SPEED)
            })
        }
        return out.build()
    }

    private fun button(icon: Int, name: String, command: SessionCommand, customIcon: Int = 0): CommandButton =
        CommandButton.Builder(icon).apply { if (customIcon != 0) setCustomIconResId(customIcon) }
            .setDisplayName(name).setSessionCommand(command).setSlots(CommandButton.SLOT_OVERFLOW).build()

    /** Also remembers [seconds] as the length the skip commands use. */
    private fun skipButtons(seconds: Int): ImmutableList<CommandButton> {
        val n = validSkipSeconds(seconds)
        currentSeconds = n
        return ImmutableList.of(
            button(skipIcon(n, forward = false), getString(R.string.audio_back_n, n), ButtonCommands.BACK),
            button(skipIcon(n, forward = true), getString(R.string.audio_forward_n, n), ButtonCommands.FORWARD)
        )
    }
}

/** What the audio buttons depend on; the rest of the audio state (position) must not redraw the notification. */
private data class AudioButtonState(val id: String?, val canFavourite: Boolean, val speed: Float, val sleepOn: Boolean)

@OptIn(UnstableApi::class)
private fun speedIcon(speed: Float): Int = when {
    speed >= 1.9f -> CommandButton.ICON_PLAYBACK_SPEED_2_0
    speed >= 1.4f -> CommandButton.ICON_PLAYBACK_SPEED_1_5
    speed >= 1.15f -> CommandButton.ICON_PLAYBACK_SPEED_1_2
    else -> CommandButton.ICON_PLAYBACK_SPEED_1_0
}

private object ButtonCommands {
    val BACK = SessionCommand("com.abhinavxt.debforge.SKIP_BACK", Bundle.EMPTY)
    val FORWARD = SessionCommand("com.abhinavxt.debforge.SKIP_FORWARD", Bundle.EMPTY)
    val FAVOURITE = SessionCommand("com.abhinavxt.debforge.FAVOURITE", Bundle.EMPTY)
    val SLEEP = SessionCommand("com.abhinavxt.debforge.SLEEP_TIMER", Bundle.EMPTY)
    val SPEED = SessionCommand("com.abhinavxt.debforge.SPEED", Bundle.EMPTY)
    val ALL = listOf(BACK, FORWARD, FAVOURITE, SLEEP, SPEED)
}

@OptIn(UnstableApi::class)
private fun skipIcon(seconds: Int, forward: Boolean): Int = when (seconds) {
    5 -> if (forward) CommandButton.ICON_SKIP_FORWARD_5 else CommandButton.ICON_SKIP_BACK_5
    15 -> if (forward) CommandButton.ICON_SKIP_FORWARD_15 else CommandButton.ICON_SKIP_BACK_15
    30 -> if (forward) CommandButton.ICON_SKIP_FORWARD_30 else CommandButton.ICON_SKIP_BACK_30
    else -> if (forward) CommandButton.ICON_SKIP_FORWARD_10 else CommandButton.ICON_SKIP_BACK_10
}

private const val PLACEHOLDER_GRACE_MS = 3_000L

/** Hand-over of the activity's player to [BackgroundPlaybackService]. */
object BackgroundPlayback {
    @Volatile var player: Player? = null

    /** What the notification opens instead of [PlayerActivity] (audio sets it). */
    @Volatile var openIntent: Intent? = null

    /** Boolean extra on that intent: open the app with "now playing" expanded. */
    const val EXTRA_EXPAND_NOW_PLAYING = "com.abhinavxt.debforge.EXPAND_NOW_PLAYING"

    /** Boolean extra on the service intent: started with startForegroundService, so it must call startForeground at once. */
    const val EXTRA_FOREGROUND_REQUIRED = "com.abhinavxt.debforge.FOREGROUND_REQUIRED"

    /** Whether a foreground start is still owed a startForeground(), and a stop held back until then. */
    val foregroundStart = ForegroundStartGate()

    /** The running service, if any: a stop goes through it so that a foreground start Media3 made is answered first. */
    @Volatile var service: BackgroundPlaybackService? = null

    /** Starts the service from the foreground (a plain start). */
    fun start(context: Context) {
        context.startService(Intent(context, BackgroundPlaybackService::class.java))
        foregroundStart.plainStartRequested()
    }

    /** Starts the service from the background: it must post a notification at once ([EXTRA_FOREGROUND_REQUIRED]). */
    fun startForeground(context: Context) {
        // Before the call: the service may answer (and a stop may arrive) as soon as it returns.
        foregroundStart.foregroundStartRequested()
        try {
            context.startForegroundService(Intent(context, BackgroundPlaybackService::class.java).putExtra(EXTRA_FOREGROUND_REQUIRED, true))
        } catch (e: RuntimeException) {
            foregroundStart.foregroundStartFailed()
            throw e
        }
    }

    /** Stops the service, or, while its foreground start is still pending, has it stop itself right after. */
    fun stop(context: Context) {
        when (stopAction(foregroundStart.requestStop(), service != null)) {
            StopAction.DEFER -> Unit
            StopAction.STOP_SERVICE -> context.stopService(Intent(context, BackgroundPlaybackService::class.java))
            StopAction.STOP_FROM_SERVICE -> service?.stopSafely() ?: context.stopService(Intent(context, BackgroundPlaybackService::class.java))
        }
    }
}

/** How [BackgroundPlayback.stop] ends the service. */
enum class StopAction { DEFER, STOP_SERVICE, STOP_FROM_SERVICE }

/** A stop waits for our own pending foreground start; a running service stops itself, after answering any start Media3 made. */
fun stopAction(mayStopNow: Boolean, serviceRunning: Boolean): StopAction = when {
    !mayStopNow -> StopAction.DEFER
    serviceRunning -> StopAction.STOP_FROM_SERVICE
    else -> StopAction.STOP_SERVICE
}
