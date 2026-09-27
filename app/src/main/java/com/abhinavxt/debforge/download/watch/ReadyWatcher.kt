package com.abhinavxt.debforge.download.watch

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.abhinavxt.debforge.data.repository.DownloadsRepository
import com.abhinavxt.debforge.data.prefs.SettingsStore
import com.abhinavxt.debforge.domain.DataResult
import com.abhinavxt.debforge.domain.ExtraFiles
import com.abhinavxt.debforge.domain.ProviderId
import com.abhinavxt.debforge.download.DownloadController
import com.abhinavxt.debforge.download.DownloadNotifications
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Download when ready". Keeps a list of jobs the service is still fetching
 * and, when one finishes, queues all its files.
 *
 * Checked from two places:
 *  - the Browse screen, every ~20 s while it's visible;
 *  - [ReadyWatchJobService], a periodic background job (Android's minimum is
 *    15 min) that runs only while there is something to watch.
 *
 * Starting the actual download from the background is restricted by Android,
 * so a background hit queues the files and posts a notification; the queue
 * starts immediately if the app is open, or when the user taps it.
 */
@Singleton
class ReadyWatcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: WatchDao,
    private val repository: DownloadsRepository,
    private val controller: DownloadController,
    private val settings: SettingsStore
) {
    private val mutex = Mutex()

    val watches: Flow<List<WatchEntity>> = dao.observeAll()

    suspend fun watch(provider: ProviderId, jobRef: String, name: String) {
        dao.upsert(WatchEntity(provider, jobRef, name))
        ReadyWatchJobService.schedule(context)
    }

    suspend fun unwatch(provider: ProviderId, jobRef: String) {
        dao.delete(provider, jobRef)
        if (dao.count() == 0) ReadyWatchJobService.cancel(context)
    }

    /**
     * Checks every watch once. Returns the names that became ready (and were
     * queued). [fromBackground] controls the notification.
     */
    suspend fun checkNow(fromBackground: Boolean): List<String> = mutex.withLock {
        val ready = mutableListOf<String>()
        val now = System.currentTimeMillis()
        for (w in dao.getAll()) {
            when (val r = repository.filesForJob(w.provider, w.jobRef)) {
                is DataResult.Success -> {
                    val files = r.data ?: continue // still processing
                    // Skip the release group's .txt, covers and samples unless
                    // the user asked to see them.
                    val wanted = if (settings.showExtraFilesFlow.first()) files else ExtraFiles.hideInTorrent(files)
                    wanted.forEach { controller.enqueue(it) }
                    dao.delete(w.provider, w.jobRef)
                    ready += w.name
                }
                is DataResult.Error -> {
                    // Deleted on the service, or failing for days: stop watching.
                    if (now - w.createdAt > GIVE_UP_AFTER_MS) dao.delete(w.provider, w.jobRef)
                }
            }
        }
        if (dao.count() == 0) ReadyWatchJobService.cancel(context)
        if (fromBackground && ready.isNotEmpty()) DownloadNotifications.notifyReady(context, ready)
        ready
    }

    private companion object {
        const val GIVE_UP_AFTER_MS = 3L * 24 * 60 * 60 * 1000
    }
}

/** Periodic background check for [ReadyWatcher]. Scheduled only while watches exist. */
@AndroidEntryPoint
class ReadyWatchJobService : JobService() {

    @Inject lateinit var watcher: ReadyWatcher

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        work = scope.launch {
            try {
                watcher.checkNow(fromBackground = true)
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        return true // periodic: let the system run it again later
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val JOB_ID = 4202

        fun schedule(context: Context) {
            val js = context.getSystemService(JobScheduler::class.java) ?: return
            if (js.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, ReadyWatchJobService::class.java))
                .setPeriodic(TimeUnit.MINUTES.toMillis(15))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true) // survives reboot (RECEIVE_BOOT_COMPLETED)
                .build()
            runCatching { js.schedule(job) }
        }

        fun cancel(context: Context) {
            context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
        }
    }
}
