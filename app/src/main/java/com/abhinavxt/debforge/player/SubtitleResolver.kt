package com.abhinavxt.debforge.player

import android.content.Context
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.data.repository.DownloadsRepository
import com.abhinavxt.debforge.domain.DataResult
import com.abhinavxt.debforge.domain.SubtitleLink
import com.abhinavxt.debforge.domain.Subtitles
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns subtitle files found in a torrent into playable links with labels.
 * Links are fetched in parallel; one that fails is left out and never
 * blocks playback.
 */
@Singleton
class SubtitleResolver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: DownloadsRepository
) {
    /** Fresh links for subtitles attached earlier; ones that can't be refreshed are dropped. */
    suspend fun refresh(links: List<SubtitleLink>): List<SubtitleLink> = coroutineScope {
        links.map { link ->
            async {
                val provider = link.provider
                val ref = link.sourceRef
                if (provider == null || ref == null) return@async link
                (repository.resolveLink(provider, ref) as? DataResult.Success)?.data?.url?.let { link.copy(url = it) }
            }
        }.awaitAll().filterNotNull()
    }

    suspend fun resolve(found: List<Subtitles.Found>): List<SubtitleLink> {
        if (found.isEmpty()) return emptyList()
        val display = Locale.getDefault()
        val forcedWord = context.getString(R.string.subtitle_forced)
        return coroutineScope {
            found.map { f ->
                async {
                    val url = f.item.downloadUrl
                        ?: (repository.resolveLink(f.item.provider, f.item.sourceRef) as? DataResult.Success)?.data?.url
                    url?.let {
                        SubtitleLink(
                            url = it,
                            mimeType = f.mimeType,
                            language = f.language,
                            label = Subtitles.label(f, display, forcedWord),
                            forced = f.forced,
                            provider = f.item.provider,
                            sourceRef = f.item.sourceRef
                        )
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }
}
