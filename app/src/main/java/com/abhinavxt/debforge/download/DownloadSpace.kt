package com.abhinavxt.debforge.download

import android.content.Context
import com.abhinavxt.debforge.data.local.DownloadDao
import com.abhinavxt.debforge.data.prefs.SettingsStore
import com.abhinavxt.debforge.domain.DownloadItem
import com.abhinavxt.debforge.domain.Housekeeping
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * "Not enough space" before queueing: what was about to be queued, what
 * it (plus everything already waiting) needs, and what's free there.
 */
data class SpaceWarning(
    val items: List<DownloadItem>,
    val needed: Long,
    val available: Long,
    val folder: String
)

/** The free-space check every "Download" button makes before queueing (Library, the watch page). */
@Singleton
class DownloadSpace @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: SettingsStore,
    private val downloadDao: DownloadDao,
) {
    /**
     * A warning when [items] (those not already in the downloads) plus what is still waiting would not fit
     * in the download folder; null when they fit. [tracked]: ids already in the downloads, when the caller
     * knows them (else each item is looked up).
     */
    suspend fun warningFor(items: List<DownloadItem>, tracked: Set<String>? = null): SpaceWarning? {
        val fresh = if (tracked != null) items.filter { it.id !in tracked }
        else items.filter { downloadDao.getById(it.id) == null }
        if (fresh.isEmpty()) return null
        val dir = settings.downloadDirFlow.first()
        val available = withContext(Dispatchers.IO) { StorageSpace.availableBytes(context, dir) }
        // Everything already waiting goes to the same place first.
        val needed = fresh.sumOf { it.filesize.coerceAtLeast(0) } + downloadDao.remainingPendingBytes()
        Housekeeping.shortfall(needed, available) ?: return null
        return SpaceWarning(fresh, needed, available ?: 0, StorageAccess.label(context, dir))
    }
}
