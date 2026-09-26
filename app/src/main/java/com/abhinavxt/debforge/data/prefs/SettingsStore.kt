package com.abhinavxt.debforge.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.abhinavxt.debforge.domain.SortOrder
import com.abhinavxt.debforge.domain.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore by preferencesDataStore(name = "debforge_settings")

/**
 * App settings. Download folder is stored as an absolute filesystem path
 * (we hold MANAGE_EXTERNAL_STORAGE, so we write via plain File I/O, not SAF).
 */
@Singleton
class SettingsStore @Inject constructor(
    private val context: Context
) {
    private val keyDownloadDir = stringPreferencesKey("download_dir")
    private val keySortOrder = intPreferencesKey("sort_order")
    private val keyThemeMode = intPreferencesKey("theme_mode")
    private val keyDynamicColor = booleanPreferencesKey("dynamic_color")
    private val keyTmdbKey = stringPreferencesKey("tmdb_api_key")
    private val keyPosterView = booleanPreferencesKey("poster_view")
    private val keyOrganize = booleanPreferencesKey("organize_library")
    private val keyWifiOnly = booleanPreferencesKey("wifi_only")
    private val keyChargingOnly = booleanPreferencesKey("charging_only")
    private val keyScheduleOn = booleanPreferencesKey("schedule_enabled")
    private val keyScheduleStart = intPreferencesKey("schedule_start_hour")
    private val keyScheduleEnd = intPreferencesKey("schedule_end_hour")
    private val keySpeedLimit = intPreferencesKey("speed_limit_kbps")
    private val keyAutoUpdate = booleanPreferencesKey("auto_update_check")
    private val keyDismissedUpdate = stringPreferencesKey("dismissed_update_tag")

    /** Defaults to the public Movies directory if unset. */
    val downloadDirFlow: Flow<String> = context.settingsDataStore.data
        .map { it[keyDownloadDir] ?: DEFAULT_DOWNLOAD_DIR }

    val sortOrderFlow: Flow<SortOrder> = context.settingsDataStore.data
        .map { prefs ->
            val ordinal = prefs[keySortOrder] ?: SortOrder.DATE_DESC.ordinal
            SortOrder.entries.getOrElse(ordinal) { SortOrder.DATE_DESC }
        }

    val themeModeFlow: Flow<ThemeMode> = context.settingsDataStore.data
        .map { prefs ->
            val ordinal = prefs[keyThemeMode] ?: ThemeMode.SYSTEM.ordinal
            ThemeMode.entries.getOrElse(ordinal) { ThemeMode.SYSTEM }
        }

    /** Material You (wallpaper) colours on Android 12+. On by default. */
    val dynamicColorFlow: Flow<Boolean> = context.settingsDataStore.data
        .map { it[keyDynamicColor] ?: true }

    /** User-supplied TMDB key; blank = fall back to the build-time key (if any). */
    val tmdbKeyFlow: Flow<String> = context.settingsDataStore.data
        // Encrypted at rest like service tokens; unreadable (e.g. restored
        // from backup on another device) reads as empty.
        .map { prefs -> prefs[keyTmdbKey]?.let(TokenCipher::decrypt).orEmpty() }

    /** Browse opens in poster view. On by default. */
    val posterViewFlow: Flow<Boolean> = context.settingsDataStore.data
        .map { it[keyPosterView] ?: true }

    /** Save into Shows/<Title>/Season NN and Movies/<Title (Year)> (Plex/Jellyfin layout). */
    val organizeLibraryFlow: Flow<Boolean> = context.settingsDataStore.data.map { it[keyOrganize] ?: false }

    /** Every download condition in one snapshot, so the engine reads them atomically. */
    val downloadRulesFlow: Flow<DownloadRules> = context.settingsDataStore.data.map { p ->
        DownloadRules(
            wifiOnly = p[keyWifiOnly] ?: false,
            chargingOnly = p[keyChargingOnly] ?: false,
            scheduleEnabled = p[keyScheduleOn] ?: false,
            scheduleStartHour = p[keyScheduleStart] ?: 1,
            scheduleEndHour = p[keyScheduleEnd] ?: 7,
            speedLimitKbps = p[keySpeedLimit] ?: 0
        )
    }

    /** Look for a new release on launch. On by default; one anonymous GitHub request. */
    val autoUpdateCheckFlow: Flow<Boolean> = context.settingsDataStore.data.map { it[keyAutoUpdate] ?: true }

    /** Release tag whose Library banner the user dismissed (Settings still shows it). */
    val dismissedUpdateFlow: Flow<String> = context.settingsDataStore.data.map { it[keyDismissedUpdate].orEmpty() }

    suspend fun setAutoUpdateCheck(v: Boolean) = context.settingsDataStore.edit { it[keyAutoUpdate] = v }
    suspend fun dismissUpdate(tag: String) = context.settingsDataStore.edit { it[keyDismissedUpdate] = tag }

    suspend fun setOrganizeLibrary(v: Boolean) = context.settingsDataStore.edit { it[keyOrganize] = v }
    suspend fun setWifiOnly(v: Boolean) = context.settingsDataStore.edit { it[keyWifiOnly] = v }
    suspend fun setChargingOnly(v: Boolean) = context.settingsDataStore.edit { it[keyChargingOnly] = v }
    suspend fun setSchedule(enabled: Boolean, startHour: Int, endHour: Int) = context.settingsDataStore.edit {
        it[keyScheduleOn] = enabled
        it[keyScheduleStart] = startHour.coerceIn(0, 23)
        it[keyScheduleEnd] = endHour.coerceIn(0, 23)
    }
    suspend fun setSpeedLimitKbps(v: Int) = context.settingsDataStore.edit { it[keySpeedLimit] = v.coerceAtLeast(0) }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.settingsDataStore.edit { it[keyDynamicColor] = enabled }
    }

    suspend fun setTmdbKey(key: String) {
        val trimmed = key.trim()
        context.settingsDataStore.edit {
            if (trimmed.isEmpty()) it.remove(keyTmdbKey) else it[keyTmdbKey] = TokenCipher.encrypt(trimmed)
        }
    }

    suspend fun setPosterView(enabled: Boolean) {
        context.settingsDataStore.edit { it[keyPosterView] = enabled }
    }

    suspend fun setDownloadDir(absolutePath: String) {
        context.settingsDataStore.edit { it[keyDownloadDir] = absolutePath }
    }

    suspend fun setSortOrder(order: SortOrder) {
        context.settingsDataStore.edit { it[keySortOrder] = order.ordinal }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsDataStore.edit { it[keyThemeMode] = mode.ordinal }
    }

    companion object {
        const val DEFAULT_DOWNLOAD_DIR = "/storage/emulated/0/Movies/DebForge"
    }
}

/**
 * User rules for when/how fast downloads run. Hours are local time; a window
 * with start > end wraps midnight (e.g. 23 -> 7). start == end means all day.
 */
data class DownloadRules(
    val wifiOnly: Boolean = false,
    val chargingOnly: Boolean = false,
    val scheduleEnabled: Boolean = false,
    val scheduleStartHour: Int = 1,
    val scheduleEndHour: Int = 7,
    /** 0 = unlimited. */
    val speedLimitKbps: Int = 0
)
