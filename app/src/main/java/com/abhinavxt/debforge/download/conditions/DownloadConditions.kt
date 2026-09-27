package com.abhinavxt.debforge.download.conditions

import androidx.annotation.StringRes
import com.abhinavxt.debforge.R
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import com.abhinavxt.debforge.data.prefs.DownloadRules
import com.abhinavxt.debforge.data.prefs.SettingsStore
import com.abhinavxt.debforge.download.NetworkMonitor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Why the engine may not transfer right now.
 * [waitInPlace] = the host stays alive and resumes by itself when it clears
 * (short, likely-to-change conditions). Otherwise the file goes back to the
 * queue and the host shuts down until an alarm/job restarts it.
 */
enum class Block(val message: String, @StringRes val labelRes: Int, val waitInPlace: Boolean) {
    NO_NETWORK("Waiting for network", R.string.wait_network, true),
    NEEDS_WIFI("Waiting for Wi-Fi", R.string.wait_wifi, true),
    NEEDS_CHARGER("Waiting for charger", R.string.wait_charger, true),
    OUTSIDE_SCHEDULE("Waiting for scheduled hours", R.string.wait_schedule, false)
}

/** Plugged-in state, tracked via sticky battery broadcasts. */
@Singleton
class PowerMonitor @Inject constructor(@ApplicationContext private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val charging: StateFlow<Boolean> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                trySend(isPlugged(intent))
            }
        }
        // ACTION_BATTERY_CHANGED is sticky: registering returns the current
        // state immediately, and it fires on plug/unplug.
        val sticky = ContextCompat.registerReceiver(
            context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        trySend(sticky?.let(::isPlugged) ?: false)
        awaitClose { context.unregisterReceiver(receiver) }
    }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, false)

    private fun isPlugged(intent: Intent): Boolean =
        intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
}

/**
 * Single source of truth for "may we download right now?", combining the
 * user's rules (Settings → Downloads) with live network/power/clock state.
 */
@Singleton
class DownloadConditions @Inject constructor(
    private val settings: SettingsStore,
    private val network: NetworkMonitor,
    private val power: PowerMonitor
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val rules: StateFlow<DownloadRules> = settings.downloadRulesFlow
        .stateIn(scope, SharingStarted.Eagerly, DownloadRules())

    /**
     * Re-evaluates the schedule on every minute boundary (not every 60 s from
     * whenever the process started), so a window that ends at 07:00 is seen
     * at 07:00:00 rather than up to a minute later.
     */
    private val minuteTicks = flow {
        while (true) {
            val now = System.currentTimeMillis()
            emit(now)
            delay(60_000 - now % 60_000 + 50)
        }
    }

    /** Current blocker, or null when downloading is allowed. */
    val blocked: StateFlow<Block?> = combine(
        rules, network.online, network.unmetered, power.charging, minuteTicks
    ) { r, online, unmetered, charging, now ->
        evaluate(r, online, unmetered, charging, now)
    }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, null)

    fun currentBlock(): Block? = blocked.value

    /**
     * Like [currentBlock], but re-evaluated right now from the latest saved
     * settings and the current clock. [blocked] can lag: the schedule is only
     * re-checked once a minute, and a setting saved a moment ago may not have
     * reached [rules] yet. Use this for start/stop decisions (alarm at window
     * start, a rule just changed in Settings).
     */
    suspend fun freshBlock(now: Long = System.currentTimeMillis()): Block? =
        evaluate(settings.downloadRulesFlow.first(), network.online.value, network.unmetered.value, power.charging.value, now)

    /** Latest saved rules (not the possibly-lagging [rules] snapshot). */
    suspend fun freshRules(): DownloadRules = settings.downloadRulesFlow.first()

    /** Is [now] inside the user's download window? Always true when the schedule is off. */
    fun inWindow(now: Long = System.currentTimeMillis()): Boolean = inWindow(rules.value, now)

    /** Millis of the next window start (for the wake-up alarm). */
    fun nextWindowStart(r: DownloadRules = rules.value, now: Long = System.currentTimeMillis()): Long =
        nextWindowStart(r.scheduleStartHour, now)

    companion object {
        fun nextWindowStart(startHour: Int, now: Long): Long {
            val cal = Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, startHour)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 5)
                set(Calendar.MILLISECOND, 0)
            }
            if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
            return cal.timeInMillis
        }

        fun evaluate(r: DownloadRules, online: Boolean, unmetered: Boolean, charging: Boolean, now: Long): Block? = when {
            r.scheduleEnabled && !inWindow(r, now) -> Block.OUTSIDE_SCHEDULE
            !online -> Block.NO_NETWORK
            r.wifiOnly && !unmetered -> Block.NEEDS_WIFI
            r.chargingOnly && !charging -> Block.NEEDS_CHARGER
            else -> null
        }

        fun inWindow(r: DownloadRules, now: Long): Boolean {
            if (!r.scheduleEnabled || r.scheduleStartHour == r.scheduleEndHour) return true
            val hour = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
            return if (r.scheduleStartHour < r.scheduleEndHour) {
                hour >= r.scheduleStartHour && hour < r.scheduleEndHour
            } else {
                // Wraps midnight, e.g. 23 -> 7.
                hour >= r.scheduleStartHour || hour < r.scheduleEndHour
            }
        }
    }
}

/**
 * Global token-bucket speed limit shared by all chunks of all downloads.
 * [reserve] returns how long the caller should sleep before continuing.
 */
@Singleton
class RateLimiter @Inject constructor(private val conditions: DownloadConditions) {
    private val lock = Any()
    private var nextFreeAtNanos = 0L

    fun reserve(bytes: Int): Long {
        val kbps = conditions.rules.value.speedLimitKbps
        if (kbps <= 0) return 0L
        val bytesPerSec = kbps * 1024L
        val costNanos = bytes * 1_000_000_000L / bytesPerSec
        synchronized(lock) {
            val now = System.nanoTime()
            // Don't bank more than ~1 s of idle credit (avoids a burst).
            val start = maxOf(nextFreeAtNanos, now - 1_000_000_000L)
            nextFreeAtNanos = start + costNanos
            val waitNanos = nextFreeAtNanos - now
            return if (waitNanos > 0) waitNanos / 1_000_000L else 0L
        }
    }
}
