package com.abhinavxt.debforge.ui.settings

import androidx.annotation.StringRes
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.ui.settings.SettingsCategory.*

/** The Settings categories, in display order. [key] is what survives rotation and process death. */
enum class SettingsCategory(val key: String, @StringRes val title: Int) {
    ACCOUNT("account", R.string.settings_cat_account),
    APPEARANCE("appearance", R.string.set_appearance),
    HOME("home", R.string.settings_cat_home),
    DOWNLOADS("downloads", R.string.settings_cat_downloads),
    VIDEO("video", R.string.settings_cat_video),
    AUDIO("audio", R.string.settings_cat_audio),
    YOUTUBE("youtube", R.string.settings_cat_youtube),
    CONNECTIONS("connections", R.string.settings_cat_connections),
    DATA("data", R.string.settings_cat_data),
    ABOUT("about", R.string.about_title);

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key }

        /** The category a [SettingsTarget] section lives in; null for an unknown target. */
        fun forTarget(target: String?) = when (target) {
            SettingsTarget.SEARCH_SOURCES -> CONNECTIONS
            else -> null
        }
    }
}

/** One searchable setting: its title, optional detail text, and the category that holds it. */
class SettingEntry(@StringRes val title: Int, @StringRes val detail: Int?, val category: SettingsCategory)

private fun e(c: SettingsCategory, title: Int, detail: Int? = null) = SettingEntry(title, detail, c)

/** Every setting by its title; search runs over this, so a new setting is added here too. */
val SETTINGS_INDEX: List<SettingEntry> = run {
    listOf(
        e(ACCOUNT, R.string.set_service), e(ACCOUNT, R.string.set_sign_out), e(ACCOUNT, R.string.set_expiry_reminder),
        e(APPEARANCE, R.string.set_theme), e(APPEARANCE, R.string.set_pure_black, R.string.set_pure_black_detail),
        e(APPEARANCE, R.string.set_language),
        e(HOME, R.string.set_default_sort), e(HOME, R.string.set_show_extras, R.string.set_show_extras_detail),
        e(HOME, R.string.set_following), e(HOME, R.string.set_posters, R.string.set_tmdb_note),
        e(HOME, R.string.set_clipboard_offer, R.string.set_clipboard_offer_detail),
        e(DOWNLOADS, R.string.set_download_folder), e(DOWNLOADS, R.string.set_import_local, R.string.set_import_local_detail),
        e(DOWNLOADS, R.string.set_organise, R.string.set_organise_detail),
        e(DOWNLOADS, R.string.set_remove_after, R.string.set_remove_after_detail),
        e(DOWNLOADS, R.string.set_storage_free_unknown),
        e(DOWNLOADS, R.string.set_auto_delete, R.string.set_auto_delete_detail),
        e(DOWNLOADS, R.string.set_wifi_only, R.string.set_wifi_only_detail), e(DOWNLOADS, R.string.set_charging_only),
        e(DOWNLOADS, R.string.set_schedule, R.string.set_schedule_detail),
        e(DOWNLOADS, R.string.set_speed_limit, R.string.set_speed_detail),
        e(DOWNLOADS, R.string.set_storage_limit, R.string.set_storage_limit_detail),
        e(VIDEO, R.string.set_player), e(VIDEO, R.string.set_autoplay_next, R.string.set_autoplay_next_detail),
        e(VIDEO, R.string.set_auto_skip_intro, R.string.set_auto_skip_intro_detail),
        e(VIDEO, R.string.set_player_gestures, R.string.set_player_gestures_detail),
        e(VIDEO, R.string.set_video_full_screen, R.string.set_video_full_screen_detail),
        e(VIDEO, R.string.set_pip, R.string.set_pip_detail),
        e(VIDEO, R.string.set_audio_language), e(VIDEO, R.string.set_subtitle_language),
        e(VIDEO, R.string.set_stream_previews, R.string.set_stream_previews_detail),
        e(AUDIO, R.string.set_skip_length, R.string.set_skip_length_detail),
        e(AUDIO, R.string.set_even_out_volume, R.string.set_even_out_volume_detail),
        e(AUDIO, R.string.set_player_art_color, R.string.set_player_art_color_detail),
        e(YOUTUBE, R.string.set_yt_quality, R.string.set_yt_quality_detail),
        e(YOUTUBE, R.string.set_sponsorblock, R.string.set_sponsorblock_detail),
        e(YOUTUBE, R.string.set_yt_autoplay, R.string.set_yt_autoplay_detail),
        e(CONNECTIONS, R.string.trakt_title, R.string.trakt_detail),
        e(CONNECTIONS, R.string.os_title), e(CONNECTIONS, R.string.os_fetch_on_download, R.string.os_fetch_on_download_detail),
        e(CONNECTIONS, R.string.sources_title, R.string.sources_detail),
        e(CONNECTIONS, R.string.release_avoid), e(CONNECTIONS, R.string.release_prefer),
        e(DATA, R.string.usage_title), e(DATA, R.string.backup_title, R.string.backup_detail),
        e(ABOUT, R.string.about_auto_update, R.string.about_auto_update_detail),
        e(ABOUT, R.string.about_source), e(ABOUT, R.string.about_whats_new, R.string.about_whats_new_detail),
        e(ABOUT, R.string.about_licences),
    )
}

/**
 * Entries whose title or detail contains every word of [query] (case-insensitive), in index order.
 * [text] resolves a string resource, so this stays testable without Android.
 */
fun searchSettings(query: String, index: List<SettingEntry> = SETTINGS_INDEX, text: (Int) -> String): List<SettingEntry> {
    val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return emptyList()
    return index.filter { entry ->
        val hay = (text(entry.title) + " " + (entry.detail?.let(text) ?: "")).lowercase()
        words.all { it in hay }
    }
}
