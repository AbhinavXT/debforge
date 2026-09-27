package com.abhinavxt.debforge.download

import android.util.Log
import com.abhinavxt.debforge.data.local.DownloadDao
import com.abhinavxt.debforge.data.prefs.SettingsStore
import com.abhinavxt.debforge.domain.Housekeeping
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Delete downloads after N days" (Settings → Download folder, off by
 * default). Runs when the app comes to the front, so nothing is deleted
 * behind the user's back while DebForge isn't in use.
 *
 * Only touches files DebForge downloaded that are still listed under
 * Downloads (a finished row with a path). A row is forgotten only once its
 * file is really gone. If deleting fails (the folder's access was revoked,
 * the file is open elsewhere), the row stays and it's tried again next time.
 */
@Singleton
class Housekeeper @Inject constructor(
    private val settings: SettingsStore,
    private val downloadDao: DownloadDao
) {
    private val lock = Mutex()

    /** Returns how many files were deleted. */
    suspend fun run(now: Long = System.currentTimeMillis()): Int = lock.withLock {
        val days = settings.autoDeleteDaysFlow.first()
        if (days <= 0) return 0
        val finished = downloadDao.completed().map {
            Housekeeping.Finished(it.id, it.finalFilePath, finishedAt = it.updatedAt)
        }
        var deleted = 0
        withContext(Dispatchers.IO) {
            for (f in Housekeeping.expired(finished, now, days)) {
                val path = f.path
                if (path != null) {
                    DownloadFiles.deleteFinal(path)
                    if (DownloadFiles.exists(path)) continue // couldn't delete: keep the row
                }
                downloadDao.delete(f.id)
                deleted++
            }
        }
        if (deleted > 0) Log.i(TAG, "Auto-deleted $deleted finished download(s) older than $days days")
        deleted
    }

    private companion object {
        const val TAG = "Housekeeper"
    }
}
