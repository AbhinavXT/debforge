package com.abhinavxt.debforge.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.abhinavxt.debforge.data.update.InstallSource
import com.abhinavxt.debforge.domain.SortOrder
import com.abhinavxt.debforge.domain.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
    private val keyPosterPromptDismissed = booleanPreferencesKey("poster_prompt_dismissed")
    private val keyRemoveAfter = booleanPreferencesKey("remove_after_download")
    private val keyAppTheme = stringPreferencesKey("app_theme")
    private val keyPureBlack = booleanPreferencesKey("pure_black")
    private val keyShowExtras = booleanPreferencesKey("show_extra_files")
    private val keyAutoDeleteDays = intPreferencesKey("auto_delete_days")
    private val keyInternalPlayer = booleanPreferencesKey("internal_player")
    private val keyAutoplayNext = booleanPreferencesKey("autoplay_next")
    private val keyPip = booleanPreferencesKey("picture_in_picture")
    private val keyAutoSkipIntro = booleanPreferencesKey("auto_skip_intro")
    private val keyNightMode = booleanPreferencesKey("player_night_mode")
    private val keyPlayerGestures = booleanPreferencesKey("player_gestures")
    private val keyPlayerShowRemaining = booleanPreferencesKey("player_show_remaining")
    private val keyAudioLanguage = stringPreferencesKey("player_audio_language")
    private val keySubtitleLanguage = stringPreferencesKey("player_subtitle_language")
    private val keySubtitleStyle = stringPreferencesKey("player_subtitle_style")
    private val keyPlayerBackground = booleanPreferencesKey("player_background")
    private val keyClipboardOffer = booleanPreferencesKey("clipboard_offer")
    private val keyClipboardSeen = androidx.datastore.preferences.core.longPreferencesKey("clipboard_seen_at")

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

    /** Material You (wallpaper) colours on Android 12+. Off by default: the app's own themes lead. */
    val dynamicColorFlow: Flow<Boolean> = context.settingsDataStore.data
        .map { it[keyDynamicColor] ?: false }

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

    /**
     * Show torrent extras (release-group .txt/.nfo, covers, samples) in the
     * library and include them in auto-downloads. Off by default.
     */
    val showExtraFilesFlow: Flow<Boolean> = context.settingsDataStore.data.map { it[keyShowExtras] ?: false }

    /** Offer a magnet / .torrent link found on the clipboard when the app opens. On by default. */
    val clipboardOfferFlow: Flow<Boolean> = context.settingsDataStore.data.map { it[keyClipboardOffer] ?: true }
    suspend fun setClipboardOffer(v: Boolean) = context.settingsDataStore.edit { it[keyClipboardOffer] = v }

    /** Timestamp of the last clip looked at, so each copy is offered once. Per device. */
    val clipboardSeenFlow: Flow<Long> = context.settingsDataStore.data.map { it[keyClipboardSeen] ?: 0L }
    suspend fun setClipboardSeen(at: Long) = context.settingsDataStore.edit { it[keyClipboardSeen] = at }

    /**
     * Delete files DebForge downloaded this many days after they finished;
     * 0 = never (default). Only files still listed under Downloads.
     */
    val autoDeleteDaysFlow: Flow<Int> = context.settingsDataStore.data.map { it[keyAutoDeleteDays] ?: 0 }
    suspend fun setAutoDeleteDays(days: Int) = context.settingsDataStore.edit { it[keyAutoDeleteDays] = days.coerceAtLeast(0) }

    /** Play in DebForge's own player (default) rather than VLC & co. */
    val internalPlayerFlow: Flow<Boolean> = context.settingsDataStore.data.map { it[keyInternalPlayer] ?: true }
    suspend fun setInternalPlayer(v: Boolean) = context.settingsDataStore.edit { it[keyInternalPlayer] = v }

    /** Next episode starts by itself after the countdown. On by default. */
    val autoplayNextFlow: Flow<Boolean> = context.settingsDataStore.data.map { it[keyAutoplayNext] ?: true }
    suspend fun setAutoplayNext(v: Boolean) = context.settingsDataStore.edit { it[keyAutoplayNext] = v }

    /** Leaving the player while a video plays keeps it in a floating window. On by default. */
    val pipFlow: Flow<Boolean> = context.settingsDataStore.data.map { it[keyPip] ?: true }
    suspend fun setPip(v: Boolean) = context.settingsDataStore.edit { it[keyPip] = v }

    /** Player: jump over chapters marked intro / recap as they start, instead of offering a button. Off by default. */
    val autoSkipIntroFlow: Flow<Boolean> = context.settingsDataStore.data.map { it[keyAutoSkipIntro] ?: false }
    suspend fun setAutoSkipIntro(v: Boolean) = context.settingsDataStore.edit { it[keyAutoSkipIntro] = v }

    /** Player → More: night mode (dynamic range compression). Off by default. */
    val nightModeFlow: Flow<Boolean> = context.settingsDataStore.data.map { it[keyNightMode] ?: false }
    suspend fun setNightMode(v: Boolean) = context.settingsDataStore.edit { it[keyNightMode] = v }

    /** Player: double-tap seek, swipe brightness/volume, scrub, hold for speed, pinch zoom. */
    val playerGesturesFlow: Flow<Boolean> = context.settingsDataStore.data.map { it[keyPlayerGestures] ?: true }
    suspend fun setPlayerGestures(v: Boolean) = context.settingsDataStore.edit { it[keyPlayerGestures] = v }

    /** Player: the right-hand time shows what's left instead of the length (tap it to switch). */
    val playerShowRemainingFlow: Flow<Boolean> = context.settingsDataStore.data.map { it[keyPlayerShowRemaining] ?: false }
    suspend fun setPlayerShowRemaining(v: Boolean) = context.settingsDataStore.edit { it[keyPlayerShowRemaining] = v }

    /** Preferred audio language ("" = the file's default), e.g. "ja". */
    val audioLanguageFlow: Flow<String> = context.settingsDataStore.data.map { it[keyAudioLanguage] ?: "" }
    suspend fun setAudioLanguage(v: String) = context.settingsDataStore.edit { it[keyAudioLanguage] = v }

    /** Subtitles: "" = the file's default, "off", or a language code. */
    val subtitleLanguageFlow: Flow<String> = context.settingsDataStore.data.map { it[keySubtitleLanguage] ?: "" }
    suspend fun setSubtitleLanguage(v: String) = context.settingsDataStore.edit { it[keySubtitleLanguage] = v }

    /** [com.abhinavxt.debforge.player.tracks.SubtitleStyle.encode]d; null = defaults. */
    val subtitleStyleFlow: Flow<String?> = context.settingsDataStore.data.map { it[keySubtitleStyle] }
    suspend fun setSubtitleStyle(v: String) = context.settingsDataStore.edit { it[keySubtitleStyle] = v }

    /** Player → More: keep the sound playing after leaving the player. */
    val playerBackgroundFlow: Flow<Boolean> = context.settingsDataStore.data.map { it[keyPlayerBackground] ?: false }
    suspend fun setPlayerBackground(v: Boolean) = context.settingsDataStore.edit { it[keyPlayerBackground] = v }

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

    /**
     * Look for a new release on launch (one anonymous GitHub request). Defaults
     * to on for sideloaded installs and off when F-Droid, Obtainium or another
     * updater installed the app, since that already handles updates.
     */
    val autoUpdateCheckFlow: Flow<Boolean> = context.settingsDataStore.data.map {
        it[keyAutoUpdate] ?: !InstallSource.managedByUpdater(context)
    }

    /** "Turn on posters" banner in the Library was closed. Per device, not backed up. */
    val posterPromptDismissedFlow: Flow<Boolean> = context.settingsDataStore.data.map { it[keyPosterPromptDismissed] ?: false }
    suspend fun dismissPosterPrompt() = context.settingsDataStore.edit { it[keyPosterPromptDismissed] = true }

    /** Release tag whose Library banner the user dismissed (Settings still shows it). */
    val dismissedUpdateFlow: Flow<String> = context.settingsDataStore.data.map { it[keyDismissedUpdate].orEmpty() }

    suspend fun setAutoUpdateCheck(v: Boolean) = context.settingsDataStore.edit { it[keyAutoUpdate] = v }
    suspend fun dismissUpdate(tag: String) = context.settingsDataStore.edit { it[keyDismissedUpdate] = tag }

    /** Colour theme name (see ui.theme.AppTheme); used when Material You is off. */
    val appThemeFlow: Flow<String?> = context.settingsDataStore.data.map { it[keyAppTheme] }
    suspend fun setAppTheme(name: String) = context.settingsDataStore.edit { it[keyAppTheme] = name }

    /** Dark mode on true black (#000) for OLED screens. Off by default. */
    val pureBlackFlow: Flow<Boolean> = context.settingsDataStore.data.map { it[keyPureBlack] ?: false }
    suspend fun setPureBlack(v: Boolean) = context.settingsDataStore.edit { it[keyPureBlack] = v }

    /** Delete a torrent from the service once all of its files are downloaded. Off by default. */
    val removeAfterDownloadFlow: Flow<Boolean> = context.settingsDataStore.data.map { it[keyRemoveAfter] ?: false }
    suspend fun setRemoveAfterDownload(v: Boolean) = context.settingsDataStore.edit { it[keyRemoveAfter] = v }

    suspend fun setOrganizeLibrary(v: Boolean) = context.settingsDataStore.edit { it[keyOrganize] = v }
    suspend fun setShowExtraFiles(v: Boolean) = context.settingsDataStore.edit { it[keyShowExtras] = v }
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
        // Quotes, "Bearer " and line breaks that come along with a paste.
        val trimmed = com.abhinavxt.debforge.data.metadata.TmdbKey.normalize(key)
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

    // --- backup / restore ---------------------------------------------------

    /**
     * Settings that make sense on another phone. Left out on purpose: the
     * TMDB key (restored only through the password-protected part of a
     * backup), and per-device state such as the dismissed update banner.
     */
    private val backupBooleans get() = listOf(
        keyDynamicColor, keyPosterView, keyOrganize, keyWifiOnly, keyChargingOnly,
        keyScheduleOn, keyAutoUpdate, keyRemoveAfter, keyPureBlack, keyShowExtras, keyClipboardOffer,
        keyInternalPlayer, keyAutoplayNext, keyPip, keyAutoSkipIntro, keyNightMode
    )
    private val backupInts get() = listOf(keySortOrder, keyThemeMode, keyScheduleStart, keyScheduleEnd, keySpeedLimit, keyAutoDeleteDays)

    /** Only values the user actually set; defaults stay defaults on the new phone. */
    suspend fun backupValues(): Map<String, Any> {
        val prefs = context.settingsDataStore.data.first()
        val out = LinkedHashMap<String, Any>()
        backupBooleans.forEach { k -> prefs[k]?.let { out[k.name] = it } }
        backupInts.forEach { k -> prefs[k]?.let { out[k.name] = it } }
        prefs[keyDownloadDir]?.let { out[keyDownloadDir.name] = it }
        prefs[keyAppTheme]?.let { out[keyAppTheme.name] = it }
        return out
    }

    /**
     * Applies values from a backup; unknown keys and wrong types are ignored.
     * A folder the system picker granted (content://) can't be carried to
     * another phone, so it's skipped and reported via [RestoreOutcome].
     */
    suspend fun restoreValues(values: Map<String, Any?>): RestoreOutcome {
        var applied = 0
        var skippedFolder = false
        context.settingsDataStore.edit { e ->
            backupBooleans.forEach { k ->
                (values[k.name] as? Boolean)?.let { e[k] = it; applied++ }
            }
            backupInts.forEach { k ->
                // JSON numbers arrive as Double.
                (values[k.name] as? Number)?.let { e[k] = it.toInt(); applied++ }
            }
            (values[keyAppTheme.name] as? String)?.takeIf { it.isNotBlank() }?.let { e[keyAppTheme] = it; applied++ }
            (values[keyDownloadDir.name] as? String)?.takeIf { it.isNotBlank() }?.let { dir ->
                if (dir.startsWith("content://")) skippedFolder = true else { e[keyDownloadDir] = dir; applied++ }
            }
        }
        return RestoreOutcome(applied, skippedFolder)
    }

    data class RestoreOutcome(val applied: Int, val skippedFolder: Boolean)

    /** Decrypted TMDB key for the protected part of a backup; null if unset. */
    suspend fun tmdbKeyForBackup(): String? = tmdbKeyFlow.first().takeIf { it.isNotBlank() }

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
