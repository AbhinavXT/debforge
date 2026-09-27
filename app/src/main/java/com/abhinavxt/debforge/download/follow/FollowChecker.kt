package com.abhinavxt.debforge.download.follow

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.abhinavxt.debforge.data.follow.FollowStore
import com.abhinavxt.debforge.data.local.DownloadDao
import com.abhinavxt.debforge.data.prefs.SettingsStore
import com.abhinavxt.debforge.data.repository.DownloadsRepository
import com.abhinavxt.debforge.domain.DataResult
import com.abhinavxt.debforge.domain.DownloadItem
import com.abhinavxt.debforge.domain.ExtraFiles
import com.abhinavxt.debforge.domain.Follow
import com.abhinavxt.debforge.domain.FollowMatcher
import com.abhinavxt.debforge.domain.ReleaseNameParser
import com.abhinavxt.debforge.download.DownloadController
import com.abhinavxt.debforge.download.DownloadNotifications
import com.abhinavxt.debforge.download.StorageAccess
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Queues new episodes of followed shows. DebForge only watches the account:
 * the episodes still have to reach the service (added by hand, or by the
 * service's own automation such as TorBox RSS feeds).
 *
 * Two ways in:
 *  - [checkLoaded]: the Library just loaded a page; no extra request.
 *  - [checkAccounts]: every couple of hours in the background
 *    ([FollowJobService]), page 1 of each service with follows.
 *
 * Matching rules are in [FollowMatcher]. Queued downloads follow the usual
 * rules (Wi-Fi only, schedule, space check). Started from the background,
 * they begin when Android allows it or when DebForge is opened; the
 * notification says so.
 */
@Singleton
class FollowChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: FollowStore,
    private val repository: DownloadsRepository,
    private val controller: DownloadController,
    private val downloadDao: DownloadDao,
    private val settings: SettingsStore
) {
    private val lock = Mutex()

    /** Checks files already loaded by the Library. Returns what was queued ("The Bear · S03E02"). */
    suspend fun checkLoaded(items: List<DownloadItem>): List<String> = lock.withLock {
        val follows = store.all()
        if (follows.isEmpty()) return emptyList()
        queue(follows, items)
    }

    /** Background check of every service that has follows. */
    suspend fun checkAccounts(fromBackground: Boolean): List<String> = lock.withLock {
        val follows = store.all()
        if (follows.isEmpty()) return emptyList()
        val queued = mutableListOf<String>()
        for (provider in follows.map { it.provider }.distinct()) {
            val page = repository.getDownloadsPage(provider, 1)
            if (page is DataResult.Success) queued += queue(follows, page.data.items)
        }
        if (fromBackground && queued.isNotEmpty()) DownloadNotifications.notifyFollowed(context, queued)
        queued
    }

    private suspend fun queue(follows: List<Follow>, items: List<DownloadItem>): List<String> {
        // Nowhere to save to (folder access revoked): try again next time
        // rather than marking episodes as handled.
        if (!StorageAccess.canWrite(context, settings.downloadDirFlow.first())) return emptyList()
        val matches = FollowMatcher.match(
            follows = follows,
            // Judged without the release group's .txt, covers and samples.
            items = ExtraFiles.hide(items),
            parse = { ReleaseNameParser.parse(it.filename) },
            trackedIds = downloadDao.allIds().toHashSet()
        )
        if (matches.isEmpty()) return emptyList()
        matches.forEach { controller.enqueue(it.item) }
        matches.groupBy { it.follow }.forEach { (f, ms) ->
            store.markSeen(f.provider, f.showKey, ms.map { it.episodeKey })
        }
        return matches.map { it.label }
    }
}

/** Every couple of hours while at least one show is followed. */
@AndroidEntryPoint
class FollowJobService : JobService() {

    @Inject lateinit var checker: FollowChecker

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        work = scope.launch {
            try {
                runCatching { checker.checkAccounts(fromBackground = true) }
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val JOB_ID = 4203

        /**
         * Two hours: new episodes aren't urgent, and TorBox rate-limits its
         * (uncached) list call per key.
         */
        fun schedule(context: Context) {
            val js = context.getSystemService(JobScheduler::class.java) ?: return
            if (js.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, FollowJobService::class.java))
                .setPeriodic(TimeUnit.HOURS.toMillis(2))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build()
            runCatching { js.schedule(job) }
        }

        fun cancel(context: Context) {
            context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
        }

        /** Runs the job only while something is followed. */
        fun sync(context: Context, anyFollows: Boolean) =
            if (anyFollows) schedule(context) else cancel(context)
    }
}
