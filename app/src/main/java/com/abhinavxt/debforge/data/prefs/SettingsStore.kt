package com.abhinavxt.debforge.data.prefs

import android.content.Context
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
