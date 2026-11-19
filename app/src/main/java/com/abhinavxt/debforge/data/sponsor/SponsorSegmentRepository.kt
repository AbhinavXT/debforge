package com.abhinavxt.debforge.data.sponsor

import com.abhinavxt.debforge.data.prefs.DEFAULT_SPONSOR_CATEGORIES
import com.abhinavxt.debforge.data.prefs.SettingsStore
import com.abhinavxt.debforge.domain.SponsorSegment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SponsorBlock segments of a YouTube video, cached in Room. UI-free: used by
 * the video player, the audio player and download completion.
 *
 * The cache holds every default and enabled category as fetched; the enabled
 * ones are filtered at read time. An entry older than [MAX_AGE_MS] is refetched
 * but never deleted: if the refetch fails, the stale copy still serves (offline).
 */
@Singleton
class SponsorSegmentRepository(
    private val lookup: SponsorLookup,
    private val dao: SponsorSegmentDao,
    private val enabled: suspend () -> Boolean,
    private val categories: suspend () -> Set<String>,
    private val now: () -> Long = System::currentTimeMillis
) {
    @Inject constructor(lookup: SponsorBlockClient, dao: SponsorSegmentDao, settings: SettingsStore) : this(
        lookup, dao,
        enabled = { settings.sponsorBlockEnabledFlow.first() },
        categories = { settings.sponsorBlockCategoriesFlow.first() }
    )

    /** Segments to skip in [videoId]: empty when SponsorBlock is off, the video unknown, or nothing is cached and offline. */
    suspend fun segmentsFor(videoId: String): List<SponsorSegment> {
        if (videoId.isBlank() || !enabled()) return emptyList()
        val wanted = categories()
        return load(videoId, wanted).filter { it.category in wanted }
    }

    /** Fills the cache (e.g. when a download completes) so the video skips offline. Best effort: never throws. */
    suspend fun prefetch(videoId: String) {
        try {
            if (videoId.isBlank() || !enabled()) return
            load(videoId, categories())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private suspend fun load(videoId: String, wanted: Set<String>): List<SponsorSegment> {
        val cached = dao.get(videoId)
        if (cached != null && now() - cached.fetchedAt < MAX_AGE_MS) return lookup.fromJson(cached.json)
        return try {
            val fresh = lookup.segments(videoId, DEFAULT_SPONSOR_CATEGORIES + wanted)
            dao.upsert(SponsorSegmentEntity(videoId, lookup.toJson(fresh), now()))
            fresh
        } catch (e: CancellationException) {
            throw e
        } catch (_: java.io.IOException) {
            cached?.let { lookup.fromJson(it.json) }.orEmpty()
        }
    }

    companion object {
        const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    }
}
