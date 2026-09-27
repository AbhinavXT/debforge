package com.abhinavxt.debforge.download

import android.util.Log
import com.abhinavxt.debforge.data.local.DownloadDao
import com.abhinavxt.debforge.data.local.DownloadEntity
import com.abhinavxt.debforge.data.prefs.SettingsStore
import com.abhinavxt.debforge.data.repository.DownloadsRepository
import com.abhinavxt.debforge.domain.DataResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Remove from service after downloading": frees torrent slots on the debrid
 * service once the phone has everything.
 *
 * A torrent is removed only when EVERY file in it (except its extras, see
 * [com.abhinavxt.debforge.domain.ExtraFiles]) has finished downloading here
 * (or its whole-torrent zip has) and nothing else from it is still queued,
 * so downloading one episode of a season pack never deletes the rest.
 * Single-file entries (a Real-Debrid download, a Premiumize file) go as soon
 * as they finish. Failures are silent: the item simply stays on the service.
 */
@Singleton
class ServiceCleaner @Inject constructor(
    private val settings: SettingsStore,
    private val repository: DownloadsRepository,
    private val downloadDao: DownloadDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Several files of one torrent can finish at once; check them one at a time.
    private val lock = Mutex()

    fun onCompleted(entity: DownloadEntity) {
        scope.launch {
            runCatching { lock.withLock { maybeRemove(entity) } }
                .onFailure { Log.w(TAG, "Auto-remove check failed", it) }
        }
    }

    private suspend fun maybeRemove(entity: DownloadEntity) {
        if (!settings.removeAfterDownloadFlow.first()) return
        val provider = entity.provider
        val key = repository.removalKey(provider, entity.id) ?: return

        // Anything from the same torrent still queued/paused/failed? Keep it.
        val unfinished = downloadDao.unfinishedIds(provider)
        if (unfinished.any { it != entity.id && repository.removalKey(provider, it) == key }) return

        val whole = entity.id.startsWith("tb:zip:")
        if (!whole) {
            // Extras (release-group .txt, covers, samples) don't count: they're
            // hidden in the library and skipped by auto-download, so waiting
            // for them would keep the torrent on the service forever.
            val ids = (repository.wantedIdsIn(provider, key) as? DataResult.Success)?.data ?: return
            if (ids.isEmpty()) return
            val done = ids.chunked(500).flatMap { downloadDao.completedIds(provider, it) }.toSet()
            if (!done.containsAll(ids)) return
        }
        when (val r = repository.remove(provider, key)) {
            is DataResult.Success -> Log.i(TAG, "Removed $key from ${provider.name} after download")
            is DataResult.Error -> Log.w(TAG, "Couldn't remove $key: ${r.message}")
        }
    }

    private companion object {
        const val TAG = "ServiceCleaner"
    }
}
