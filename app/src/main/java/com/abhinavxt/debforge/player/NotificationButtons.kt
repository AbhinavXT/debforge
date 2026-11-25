package com.abhinavxt.debforge.player

/** Which two extra buttons the audio media notification shows (Settings > Audio player). Names are persisted. */
enum class NotificationButtons { SKIP, FAVOURITE_SLEEP, FAVOURITE_SPEED, SLEEP_SPEED }

enum class ExtraButton { SKIP_BACK, SKIP_FORWARD, FAVOURITE, SLEEP, SPEED }

fun notificationButtonsOf(name: String?): NotificationButtons =
    NotificationButtons.entries.firstOrNull { it.name == name } ?: NotificationButtons.SKIP

/** The buttons for [choice]; Favourite is left out when the track cannot be favourited (streamed YouTube). */
fun extraButtons(choice: NotificationButtons, canFavourite: Boolean): List<ExtraButton> = when (choice) {
    NotificationButtons.SKIP -> listOf(ExtraButton.SKIP_BACK, ExtraButton.SKIP_FORWARD)
    NotificationButtons.FAVOURITE_SLEEP -> listOf(ExtraButton.FAVOURITE, ExtraButton.SLEEP)
    NotificationButtons.FAVOURITE_SPEED -> listOf(ExtraButton.FAVOURITE, ExtraButton.SPEED)
    NotificationButtons.SLEEP_SPEED -> listOf(ExtraButton.SLEEP, ExtraButton.SPEED)
}.filter { canFavourite || it != ExtraButton.FAVOURITE }

val SLEEP_STEPS = listOf(15, 30, 60)

/** Off -> 15 -> 30 -> 60 -> off. [lastStep] is the minutes the timer was set to ("End of track" or off count as past the end). */
fun nextSleepStep(active: Boolean, lastStep: Int): Int =
    if (!active) SLEEP_STEPS.first() else SLEEP_STEPS.firstOrNull { it > lastStep }.takeIf { lastStep > 0 } ?: 0

val SPEED_STEPS = listOf(1f, 1.25f, 1.5f, 2f)

/** 1x -> 1.25x -> 1.5x -> 2x -> 1x; an odd speed (set in the app) moves up to the next step. */
fun nextSpeed(speed: Float): Float = SPEED_STEPS.firstOrNull { it > speed + 0.01f } ?: SPEED_STEPS.first()

enum class SwipeAction { STOP, KEEP_PLAYING }

/** Swiped from recents: only audio that is playing may carry on, and only when the user allowed it; video's surface dies with the task. */
fun swipeAction(isAudio: Boolean, keepPlaying: Boolean, playing: Boolean): SwipeAction =
    if (isAudio && keepPlaying && playing) SwipeAction.KEEP_PLAYING else SwipeAction.STOP

/** Skip buttons for video: not for live streams or anything that cannot seek. */
fun canSkipVideo(isLive: Boolean, seekable: Boolean): Boolean = seekable && !isLive
