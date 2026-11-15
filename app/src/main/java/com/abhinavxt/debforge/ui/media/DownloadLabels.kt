package com.abhinavxt.debforge.ui.media

import com.abhinavxt.debforge.data.local.DownloadEntity
import com.abhinavxt.debforge.domain.YouTubeLinks
import com.abhinavxt.debforge.ui.components.ReleaseLabels
import com.abhinavxt.debforge.ui.components.releaseLabels

private val ytQuality = Regex("""\[(\d{3,4}p(?:\d{2,3})?)](?:\.[A-Za-z0-9]{1,5})?$""")

/** "360p" / "1080p60" from a YouTube file name's trailing "[360p]" suffix, else null (audio-only files have none). */
fun youTubeQuality(filename: String): String? = ytQuality.find(filename)?.groupValues?.get(1)

/** Title and quality tags for a row; YouTube takes the quality from the file name, since the title may say "4K". */
fun DownloadEntity.releaseLabelsFor(): ReleaseLabels =
    if (YouTubeLinks.isSourceRef(sourceRef)) ReleaseLabels(displayTitle(), youTubeQuality(filename).orEmpty())
    else releaseLabels(filename)

/** "360p · MP4", "M4A": tells apart several downloads of the same title. */
fun DownloadEntity.formatLabel(): String {
    val quality = if (YouTubeLinks.isSourceRef(sourceRef)) youTubeQuality(filename)
    else com.abhinavxt.debforge.domain.ReleaseNameParser.parse(filename).resolution.takeIf { com.abhinavxt.debforge.domain.kindOf(filename) == com.abhinavxt.debforge.domain.FileKind.Video }
    return listOfNotNull(quality, extensionLabel(filename)).joinToString(" · ")
}

/** Upper-cased extension; ".weba" is Opus audio, named as the YouTube format list does. */
fun extensionLabel(filename: String): String? =
    filename.substringAfterLast('.', "").let { if (it.equals("weba", true)) "Opus" else it.uppercase() }.takeIf { it.isNotEmpty() }
