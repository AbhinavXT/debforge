package com.abhinavxt.debforge.download

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.abhinavxt.debforge.data.local.DownloadEntity
import com.abhinavxt.debforge.data.prefs.SettingsStore
import com.abhinavxt.debforge.data.subtitles.OpenSubtitlesRepository
import com.abhinavxt.debforge.domain.AutoPickResult
import com.abhinavxt.debforge.domain.AutoSubsRules
import com.abhinavxt.debforge.domain.ExtraFiles
import com.abhinavxt.debforge.domain.OnlineSubsWhenNone
import com.abhinavxt.debforge.domain.ReleaseNameParser
import com.abhinavxt.debforge.domain.SubtitleAutoPick
import com.abhinavxt.debforge.domain.Subtitles
import com.abhinavxt.debforge.domain.TextTrackInfo
import com.abhinavxt.debforge.player.MovieHasher
import com.abhinavxt.debforge.player.OnlineSubtitleLanguages
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** The pure decisions of [SubtitleFetcher]. Unit-tested in SubtitleFetchRulesTest. */
object SubtitleFetchRules {

    /** Whether to look at the file at all (cheap checks before any disk or network). */
    fun wanted(settingOn: Boolean, hasKey: Boolean, filename: String): Boolean =
        settingOn && hasKey && ExtraFiles.isVideoName(filename)

    /** Subtitle languages of sidecar files in [siblingNames] that belong to the video called [videoName]. */
    fun sidecarTracks(videoName: String, siblingNames: Collection<String>): List<TextTrackInfo> {
        val base = videoName.substringBeforeLast('.')
        return siblingNames
            .filter { it.startsWith("$base.", ignoreCase = true) }
            .map { name ->
                val (lang, forced, _) = Subtitles.describe("video" + name.substring(base.length)) // only the part after the video's name says the language
                TextTrackInfo(lang, forced)
            }
    }

    /** True when neither sidecars nor embedded tracks already cover a preferred language. */
    fun needsFetch(sidecars: List<TextTrackInfo>, embedded: List<TextTrackInfo>, languages: List<String>): Boolean =
        (sidecars + embedded).let { tracks ->
            !AutoSubsRules.hasUntaggedFullTrack(tracks) && SubtitleAutoPick.needsSubtitles(tracks, languages)
        }

    private val ISO3 by lazy { Locale.getISOLanguages().associateBy { Locale(it).isO3Language } }

    /** "eng" or "en" to "en"; null for blank or "und". */
    fun normalizeLanguage(tag: String?): String? {
        val t = tag?.lowercase()?.substringBefore('-')?.substringBefore('_')?.takeIf { it.isNotBlank() && it != "und" } ?: return null
        return if (t.length == 3) ISO3[t] ?: t else t
    }
}

/**
 * When a video download finishes and the user opted in (Settings, with an OpenSubtitles key), fetches one subtitle
 * in their language and saves it next to the video, where the player finds it as a sidecar. Quietly does nothing
 * when the language is already covered, and everything fails silently. Runs in the caller's background scope.
 */
@Singleton
class SubtitleFetcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsStore,
    private val openSubtitles: OpenSubtitlesRepository,
    private val hasher: MovieHasher,
    private val importer: SubtitleImporter
) {
    /** Epoch day on which OpenSubtitles said the daily limit is reached; no more tries that day. */
    @Volatile private var quotaDay = -1L

    suspend fun fetchFor(entity: DownloadEntity) {
        try {
            val today = System.currentTimeMillis() / 86_400_000L
            if (quotaDay == today) return
            if (!SubtitleFetchRules.wanted(settings.fetchSubsOnDownloadFlow.first(), openSubtitles.enabled.first(), entity.filename)) return
            val path = entity.finalFilePath?.takeIf { LocalFiles.exists(it) } ?: return
            val languages = OnlineSubtitleLanguages.forSetting(settings.subtitleLanguageFlow.first(), Locale.getDefault())
            val (sidecars, embedded, hash) = withContext(Dispatchers.IO) {
                val names = LocalFiles.subtitleSiblings(context, path).keys +
                    SubtitleImporter.appStorageSubtitles(context, entity.id).keys
                Triple(
                    SubtitleFetchRules.sidecarTracks(LocalFiles.name(path), names),
                    embeddedTracks(path),
                    LocalFiles.uriFor(context, path)?.let { hasher.hashOf(it) }
                )
            }
            if (!SubtitleFetchRules.needsFetch(sidecars, embedded, languages)) return
            val info = ReleaseNameParser.parse(LocalFiles.name(path))
            val results = openSubtitles.search(info, hash, languages)
            val pick = SubtitleAutoPick.pick(results, languages, OnlineSubsWhenNone.EXACT, SubtitleAutoPick.Context.DOWNLOAD_COMPLETE)
            val sub = (pick as? AutoPickResult.Add)?.subtitle ?: return
            val file = openSubtitles.download(sub)
            importer.save(entity.id, "srt", withContext(Dispatchers.IO) { file.readBytes() }, sub.language?.substringBefore('-'))
        } catch (e: CancellationException) {
            throw e
        } catch (e: OpenSubtitlesRepository.QuotaException) {
            quotaDay = System.currentTimeMillis() / 86_400_000L
        } catch (e: Exception) {
            Log.w("SubtitleFetcher", "No subtitle fetched for ${entity.id}: ${e.message}")
        }
    }

    /** Text tracks inside the video file; empty when it can't be read. Blocking. */
    private fun embeddedTracks(path: String): List<TextTrackInfo> {
        val ex = MediaExtractor()
        return try {
            val uri = LocalFiles.uriFor(context, path) ?: return emptyList()
            ex.setDataSource(context, uri, null)
            (0 until ex.trackCount).mapNotNull { i ->
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
                if (!mime.startsWith("text/") && mime != "application/x-subrip" && !mime.contains("ssa") &&
                    !mime.contains("vtt") && !mime.contains("ttml") && !mime.contains("cea")) return@mapNotNull null
                val lang = SubtitleFetchRules.normalizeLanguage(
                    if (f.containsKey(MediaFormat.KEY_LANGUAGE)) f.getString(MediaFormat.KEY_LANGUAGE) else null
                )
                val forced = f.containsKey(MediaFormat.KEY_IS_FORCED_SUBTITLE) && f.getInteger(MediaFormat.KEY_IS_FORCED_SUBTITLE) != 0
                TextTrackInfo(lang, forced)
            }
        } catch (e: Exception) {
            emptyList()
        } finally {
            runCatching { ex.release() }
        }
    }
}
