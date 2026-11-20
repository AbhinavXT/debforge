package com.abhinavxt.debforge.ui.video

import android.content.pm.ActivityInfo

/** Why the screen's orientation is held. */
enum class OrientationLock {
    /** Not held: the system decides (the app's normal behaviour). */
    NONE,
    /** Full screen was entered with the button on a landscape picture: landscape until the phone is really held sideways. */
    LANDSCAPE,
    /** Full screen was left while held sideways: portrait until the phone is really held upright (no bounce back). */
    PORTRAIT,
}

/** The mode and the orientation to ask the activity for. */
data class OrientationPlan(val mode: VideoMode, val requested: Int)

/** Tablets (and anything this wide) never follow the phone rules. */
internal const val TABLET_SMALLEST_WIDTH_DP = 600

/**
 * Pure: how the device's orientation and the settings drive the video, for a phone (sw < 600) or a tablet.
 *  - Phone, system auto-rotate on, no lock, a landscape picture: turning sideways opens full screen, turning back returns to the watch page.
 *  - Auto-rotate off, tablets, portrait pictures: rotation never changes the mode.
 *  - [lock] keeps the orientation the button or Back asked for until the sensor agrees ([lockAfterSensor]).
 */
fun orientationPlan(
    mode: VideoMode,
    smallestWidthDp: Int,
    autoRotate: Boolean,
    videoPortrait: Boolean,
    landscape: Boolean,
    lock: OrientationLock = OrientationLock.NONE,
    /** The picture's size is known: until then nothing is forced (a portrait picture must not start landscape). */
    sizeKnown: Boolean = true,
): OrientationPlan {
    val phone = smallestWidthDp < TABLET_SMALLEST_WIDTH_DP
    val next = when {
        !phone || !autoRotate || videoPortrait || lock != OrientationLock.NONE -> mode
        mode == VideoMode.WATCH && landscape -> VideoMode.FULL
        mode == VideoMode.FULL && !landscape -> VideoMode.WATCH
        else -> mode
    }
    val requested = when {
        lock == OrientationLock.LANDSCAPE && next == VideoMode.FULL && sizeKnown && !videoPortrait -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        lock == OrientationLock.PORTRAIT && next != VideoMode.FULL -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    return OrientationPlan(next, requested)
}

/** Pure: the lock to start when full screen is entered by the button ([landscapeNow]: the screen is already sideways). */
fun lockOnEnterFull(videoPortrait: Boolean, landscapeNow: Boolean): OrientationLock =
    if (videoPortrait || landscapeNow) OrientationLock.NONE else OrientationLock.LANDSCAPE

/** Pure: the lock to start when full screen is left ([landscapeNow]: the screen is sideways, on a phone it must not bounce back in). */
fun lockOnExitFull(smallestWidthDp: Int, landscapeNow: Boolean): OrientationLock =
    if (smallestWidthDp < TABLET_SMALLEST_WIDTH_DP && landscapeNow) OrientationLock.PORTRAIT else OrientationLock.NONE

/** How the phone is physically held, from the sensor's angle (degrees, or -1 when flat). */
enum class Hold { PORTRAIT, LANDSCAPE, OTHER }

fun holdOf(degrees: Int): Hold = when {
    degrees < 0 -> Hold.OTHER
    degrees <= 30 || degrees >= 330 || degrees in 150..210 -> Hold.PORTRAIT
    degrees in 60..120 || degrees in 240..300 -> Hold.LANDSCAPE
    else -> Hold.OTHER
}

/**
 * Pure: the lock after the sensor reports how the phone is held. A landscape lock ends once the phone is
 * really sideways (only with auto-rotate on: with it off, landscape stays until full screen is left); a
 * portrait lock ends once it is really upright.
 */
fun lockAfterSensor(lock: OrientationLock, hold: Hold, autoRotate: Boolean): OrientationLock = when {
    lock == OrientationLock.LANDSCAPE && hold == Hold.LANDSCAPE && autoRotate -> OrientationLock.NONE
    lock == OrientationLock.PORTRAIT && hold == Hold.PORTRAIT -> OrientationLock.NONE
    else -> lock
}
