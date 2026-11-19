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
 * The cache holds every default and enabled category as fetched (the fetched
 * set is stored in the payload; a newly enabled category triggers a refetch);
 * the enabled ones are filtered at read time. An entry older than [MAX_AGE_MS] is refetched
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
        if (wanted.isEmpty()) return emptyList()
        return load(videoId, wanted).filter { it.category in wanted }
    }

    /** Fills the cache (e.g. when a download completes) so the video skips offline. Best effort: never throws. */
    suspend fun prefetch(videoId: String) {
        try {
            if (videoId.isBlank() || !enabled()) return
            val wanted = categories()
            if (wanted.isEmpty()) return
            load(videoId, wanted)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private suspend fun load(videoId: String, wanted: Set<String>): List<SponsorSegment> {
        val cached = dao.get(videoId)
        val parsed = cached?.let { decode(it.json) }
        if (cached != null && parsed != null) {
            val ttl = if (parsed.segments.isEmpty()) EMPTY_MAX_AGE_MS else MAX_AGE_MS
            if (now() - cached.fetchedAt < ttl && parsed.fetched.containsAll(wanted)) return parsed.segments
        }
        val fetched = DEFAULT_SPONSOR_CATEGORIES + wanted
        return try {
            val fresh = lookup.segments(videoId, fetched)
            dao.upsert(SponsorSegmentEntity(videoId, encode(fetched, lookup.toJson(fresh)), now()))
            fresh
        } catch (e: CancellationException) {
            throw e
        } catch (_: java.io.IOException) {
            parsed?.segments.orEmpty()
        }
    }

    private class Payload(val fetched: Set<String>, val segments: List<SponsorSegment>)

    /** Payload: `#cats:a,b,c` line, then the segments JSON. Older payloads are bare JSON, fetched with the default categories. */
    private fun decode(json: String): Payload {
        if (!json.startsWith(HEADER)) return Payload(DEFAULT_SPONSOR_CATEGORIES.toSet(), lookup.fromJson(json))
        val nl = json.indexOf('\n')
        if (nl < 0) return Payload(DEFAULT_SPONSOR_CATEGORIES.toSet(), emptyList())
        val cats = json.substring(HEADER.length, nl).split(',').filter { it.isNotBlank() }.toSet()
        return Payload(cats, lookup.fromJson(json.substring(nl + 1)))
    }

    private fun encode(fetched: Collection<String>, segmentsJson: String): String =
        HEADER + fetched.toSortedSet().joinToString(",") + "\n" + segmentsJson

    companion object {
        const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
        /** Videos with no segments are rechecked sooner: new uploads get submissions within hours. */
        const val EMPTY_MAX_AGE_MS = 24L * 60 * 60 * 1000
        private const val HEADER = "#cats:"
    }
}
