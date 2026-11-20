package com.abhinavxt.debforge.player.session

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.abhinavxt.debforge.player.tracks.SubtitleStyle
import com.abhinavxt.debforge.player.tracks.TrackPicker
import kotlinx.coroutines.flow.first

/** Choosing audio and subtitle tracks for [VideoSession]'s file, and how subtitles look. */
@OptIn(UnstableApi::class)
internal class VideoTracks(private val s: VideoSession) {

    /**
     * Audio and subtitles for a file that just started: last time's choice
     * for this show, else Settings' languages (skipping commentary and
     * "signs & songs"), else the file's own defaults. Also last time's speed.
     */
    suspend fun applyChoices(tracks: Tracks, memory: TrackPicker.Remembered?) {
        val p = s.player ?: return
        val audioLanguage = s.settings.audioLanguageFlow.first()
        val subtitleSetting = s.settings.subtitleLanguageFlow.first()
        fun options(type: Int) = tracks.groups.filter { it.type == type }.flatMap { g ->
            (0 until g.length).filter { g.isTrackSupported(it) }.map { i -> g to i }
        }
        fun candidate(f: androidx.media3.common.Format) = TrackPicker.Candidate(
            language = f.language,
            label = f.label,
            isDefault = f.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0,
            isForced = f.selectionFlags and C.SELECTION_FLAG_FORCED != 0,
            commentary = f.roleFlags and (C.ROLE_FLAG_COMMENTARY or C.ROLE_FLAG_DESCRIBES_VIDEO) != 0
        )
        val audio = options(C.TRACK_TYPE_AUDIO)
        val text = options(C.TRACK_TYPE_TEXT)
        // A fresh start for this file: the last file's picks don't carry over.
        val params = p.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        val audioChoice = TrackPicker.pickAudio(audio.map { (g, i) -> candidate(g.getTrackFormat(i)) }, audioLanguage, memory)
        if (audioChoice is TrackPicker.Choice.Pick) {
            val (g, i) = audio[audioChoice.index]
            params.setOverrideForType(androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, i))
        }
        when (val c = TrackPicker.pickText(text.map { (g, i) -> candidate(g.getTrackFormat(i)) }, subtitleSetting, memory)) {
            TrackPicker.Choice.Off -> params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            is TrackPicker.Choice.Pick -> {
                val (g, i) = text[c.index]
                params.setOverrideForType(androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, i))
            }
            TrackPicker.Choice.Leave -> Unit
        }
        p.trackSelectionParameters = params.build()
        s.autoSubs.consider(userTurnedSubtitlesOff = memory?.textOff == true)
        if (s.handoffStart == null) memory?.speed?.let { if (it != p.playbackParameters.speed) p.setPlaybackSpeed(it) }
    }

    /** After an OpenSubtitles file was added: turn it on as soon as it's listed. */
    fun selectPending(tracks: Tracks) {
        val label = s.pendingTextLabel
        val id = s.pendingTextId
        if (label == null && id == null) return
        val p = s.player ?: return
        val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        fun find(match: (androidx.media3.common.Format) -> Boolean) = textGroups.firstNotNullOfOrNull { g ->
            (0 until g.length).firstOrNull { match(g.getTrackFormat(it)) }?.let { g to it }
        }
        val target = id?.let { want -> find { it.id == want } } ?: label?.let { want -> find { it.label == want } } ?: return
        s.pendingTextLabel = null
        s.pendingTextId = null
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(androidx.media3.common.TrackSelectionOverride(target.first.mediaTrackGroup, target.second))
            .build()
    }

    /** Size, colours and height of subtitles (the Subtitles panel). */
    fun applySubtitleStyle() {
        val view = s.playerView?.subtitleView ?: return
        val style = s.controls.subStyle
        view.setFractionalTextSize(style.size.fraction)
        view.setBottomPaddingFraction(style.position.bottomFraction)
        view.setApplyEmbeddedStyles(style.fileStyling)
        // Our size always: files' own sizes are often tiny or huge on a phone.
        view.setApplyEmbeddedFontSizes(false)
        val outline = androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE
        view.setStyle(
            when (style.look) {
                SubtitleStyle.Look.OUTLINE -> androidx.media3.ui.CaptionStyleCompat(
                    android.graphics.Color.WHITE, android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT,
                    outline, android.graphics.Color.BLACK, android.graphics.Typeface.DEFAULT_BOLD
                )
                SubtitleStyle.Look.BOX -> androidx.media3.ui.CaptionStyleCompat(
                    android.graphics.Color.WHITE, 0xCC000000.toInt(), android.graphics.Color.TRANSPARENT,
                    androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_NONE, android.graphics.Color.TRANSPARENT, android.graphics.Typeface.DEFAULT
                )
                SubtitleStyle.Look.YELLOW -> androidx.media3.ui.CaptionStyleCompat(
                    0xFFFFEB3B.toInt(), android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT,
                    outline, android.graphics.Color.BLACK, android.graphics.Typeface.DEFAULT_BOLD
                )
            }
        )
    }
}
