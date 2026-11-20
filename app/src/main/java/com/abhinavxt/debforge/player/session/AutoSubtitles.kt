package com.abhinavxt.debforge.player.session

import android.net.Uri
import android.provider.DocumentsContract
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.data.subtitles.OnlineSubtitle
import com.abhinavxt.debforge.data.subtitles.OpenSubtitlesRepository
import com.abhinavxt.debforge.domain.AutoPickResult
import com.abhinavxt.debforge.domain.AutoSubsChip
import com.abhinavxt.debforge.domain.AutoSubsEffect
import com.abhinavxt.debforge.domain.AutoSubsEvent
import com.abhinavxt.debforge.domain.AutoSubsMachine
import com.abhinavxt.debforge.domain.AutoSubsRules
import com.abhinavxt.debforge.domain.ReleaseNameParser
import com.abhinavxt.debforge.domain.SubtitleAutoPick
import com.abhinavxt.debforge.domain.SubtitleLink
import com.abhinavxt.debforge.domain.TextTrackInfo
import com.abhinavxt.debforge.download.SubtitleFetchRules
import com.abhinavxt.debforge.player.OnlineSubtitleLanguages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * When a video starts with no subtitles in the user's language, looks on OpenSubtitles (once per video; searching
 * is free) and either adds the exact match or offers one in a small chip. Downloads only when adding or when the
 * user taps. Also the shared "save, attach and select" step of the Subtitles panel's online picks.
 */
@OptIn(UnstableApi::class)
internal class AutoSubtitles(private val s: VideoSession) {

    /** A subtitle that was added: [savedUri] is its copy next to the video (null for a stream); [createdFile]: this add made that copy. */
    class Attached(val link: SubtitleLink, val savedUri: Uri?, val createdFile: Boolean)

    private var started = false
    private var searchJob: Job? = null
    private var downloadJob: Job? = null
    private var timer: Job? = null
    private var added: Attached? = null
    private var paramsBefore: TrackSelectionParameters? = null

    fun reset() {
        searchJob?.cancel()
        downloadJob?.cancel()
        timer?.cancel()
        started = false
        added = null
        paramsBefore = null
        s.controls.autoSubs = AutoSubsChip.Hidden
    }

    /** After the track choices were applied to a file that just started. [userTurnedSubtitlesOff]: last time they were switched off. */
    fun consider(userTurnedSubtitlesOff: Boolean) {
        if (started) return
        started = true
        searchJob = s.scope.launch {
            // Side-loaded and embedded subtitles can be listed a moment after the first tracks.
            delay(TRACKS_SETTLE_MS)
            val p = s.player ?: return@launch
            val textTracks = p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.flatMap { g ->
                (0 until g.length).filter { g.isTrackSupported(it) }.map { i ->
                    val f = g.getTrackFormat(i)
                    TextTrackInfo(SubtitleFetchRules.normalizeLanguage(f.language), f.selectionFlags and C.SELECTION_FLAG_FORCED != 0)
                }
            }
            val languages = OnlineSubtitleLanguages.forSetting(s.settings.subtitleLanguageFlow.first(), Locale.getDefault())
            val mode = s.settings.onlineSubsWhenNoneFlow.first()
            val hasKey = s.openSubtitles.enabled.first()
            if (!AutoSubsRules.shouldSearch(mode, hasKey, s.isTv, userTurnedSubtitlesOff, textTracks, languages)) return@launch
            val info = ReleaseNameParser.parse(s.identity?.filename?.takeIf { it.isNotBlank() } ?: s.title)
            val results = try {
                val hash = if (s.youtube != null) null else movieHash()
                s.openSubtitles.search(info, hash, languages)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@launch // Quietly: nobody asked.
            }
            val pick = SubtitleAutoPick.pick(results, languages, mode, SubtitleAutoPick.Context.PLAYER_START)
            val sub = when (pick) {
                is AutoPickResult.Add -> pick.subtitle
                is AutoPickResult.Offer -> pick.subtitle
                AutoPickResult.Nothing -> return@launch
            }
            dispatch(AutoSubsEvent.Searched(pick, languageName(sub)))
        }
    }

    fun add() = dispatch(AutoSubsEvent.Add)

    fun undo() = dispatch(AutoSubsEvent.Undo)

    private suspend fun movieHash(): String? {
        // Not a child of this search: the panel's search shares the hash.
        val source = s.uri
        return (s.movieHash ?: s.scope.async { s.movieHasher.hashOf(source) }.also { s.movieHash = it }).await()
    }

    private fun languageName(sub: OnlineSubtitle): String =
        sub.language?.let { Locale.forLanguageTag(it).displayName.ifBlank { it } } ?: s.string(R.string.player_subtitles)

    private fun dispatch(event: AutoSubsEvent) {
        val before = s.controls.autoSubs
        val step = AutoSubsMachine.reduce(before, event)
        s.controls.autoSubs = step.chip
        if (step.chip != before) {
            timer?.cancel()
            val ms = when (step.chip) {
                is AutoSubsChip.Offer -> AutoSubsMachine.OFFER_MS
                is AutoSubsChip.Added -> AutoSubsMachine.ADDED_MS
                else -> null
            }
            if (ms != null) timer = s.scope.launch {
                delay(ms)
                dispatch(AutoSubsEvent.Expired)
            }
        }
        when (val fx = step.effect) {
            is AutoSubsEffect.Download -> download(fx.sub)
            is AutoSubsEffect.Tell -> s.showMessage(s.string(if (fx.quota) R.string.player_subs_quota else R.string.player_subs_download_failed))
            AutoSubsEffect.RemoveAdded -> removeAdded()
            null -> Unit
        }
    }

    private fun download(sub: OnlineSubtitle) {
        downloadJob = s.scope.launch {
            try {
                val file = s.openSubtitles.download(sub)
                val p = s.player ?: return@launch
                paramsBefore = p.trackSelectionParameters
                added = attach(sub, file, resumePlaying = p.playWhenReady)
                dispatch(AutoSubsEvent.Attached)
            } catch (e: CancellationException) {
                throw e
            } catch (e: OpenSubtitlesRepository.QuotaException) {
                dispatch(AutoSubsEvent.Failed(quota = true))
            } catch (e: Exception) {
                dispatch(AutoSubsEvent.Failed(quota = false))
            }
        }
    }

    /**
     * Undo: the track goes away and the choices from before come back. The copy this add saved next to the video
     * is deleted too (a copy that was already there is kept), so Undo leaves nothing behind that would bring the
     * subtitle back the next time the video plays.
     */
    private fun removeAdded() {
        val a = added ?: return
        added = null
        s.subtitles = s.subtitles.filterNot { it.url == a.link.url }
        s.pendingTextLabel = null
        s.pendingTextId = null
        val p = s.player
        if (p != null) {
            paramsBefore?.let { p.trackSelectionParameters = it }
            s.reloadMediaItem(p.currentPosition, play = p.playWhenReady)
        }
        paramsBefore = null
        val uri = a.savedUri
        if (uri != null && a.createdFile) s.scope.launch(Dispatchers.IO) { deleteSaved(uri) }
    }

    private fun deleteSaved(uri: Uri) {
        runCatching {
            when {
                uri.scheme == "file" -> uri.path?.let { File(it).delete() }
                DocumentsContract.isDocumentUri(s.context, uri) -> DocumentsContract.deleteDocument(s.context.contentResolver, uri)
                else -> s.context.contentResolver.delete(uri, null, null)
            }
        }
    }

    /**
     * Adds the downloaded [file] for [sub] to the video (saved next to it when it is a downloaded one, so it is found
     * again next time), then reloads at the same second and switches to it. [resumePlaying]: keep playing afterwards.
     */
    suspend fun attach(sub: OnlineSubtitle, file: File, resumePlaying: Boolean): Attached {
        val language = sub.language?.let { Locale.forLanguageTag(it).displayName.ifBlank { it } }
        val label = s.string(R.string.player_subs_online_label, language ?: s.string(R.string.player_subtitles))
        // One online subtitle per label: picking another English one replaces the last.
        var link = SubtitleLink(
            url = Uri.fromFile(file).toString(),
            mimeType = "application/x-subrip",
            language = sub.language?.substringBefore('-'),
            label = label,
            forced = false,
            osFileId = sub.fileId
        )
        // A downloaded video keeps the pick: saved next to it, it is found as a sidecar on the next play. It plays
        // from that copy now too, so its timing key (the file's name) is the same now and then.
        var savedUri: Uri? = null
        var created = false
        val id = s.identity?.itemId
        if (id != null && s.isLocalFile() && s.youtube == null) {
            try {
                val known = s.subtitles.map { it.url }
                val saved = s.subtitleImporter.save(id, "srt", file.readBytes(), link.language)
                val copy = link.copy(url = saved.uri.toString(), osFileId = null)
                s.migrateSubtitleTiming(sub.fileId, copy)
                link = copy
                savedUri = saved.uri
                created = copy.url !in known
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keeps playing from the cache copy.
            }
        }
        s.subtitles = s.subtitles.filterNot { it.label == label || it.url == link.url } + link
        s.pendingTextLabel = label
        s.pendingTextId = null
        s.controls.panel = null
        s.player?.let { p -> s.reloadMediaItem(p.currentPosition, play = resumePlaying) }
        return Attached(link, savedUri, created)
    }

    private companion object {
        const val TRACKS_SETTLE_MS = 1_500L
    }
}
