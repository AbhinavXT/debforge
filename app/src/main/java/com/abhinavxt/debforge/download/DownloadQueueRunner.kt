package com.abhinavxt.debforge.download

import com.abhinavxt.debforge.data.local.ChunkDao
import com.abhinavxt.debforge.data.local.DownloadDao
import com.abhinavxt.debforge.data.local.DownloadEntity
import com.abhinavxt.debforge.domain.DownloadState
import com.abhinavxt.debforge.download.conditions.DownloadConditions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs the download queue, one file at a time, independent of WHICH Android
 * component keeps the process alive. Two hosts exist:
 *  - [DownloadJobService]: a user-initiated data transfer job (Android 14+),
 *    which is not subject to Android 15's 6-hour dataSync limit.
 *  - [DownloadService]: a dataSync foreground service, used on Android 11-13
 *    and as a fallback when the job can't be scheduled.
 *
 * A host calls [start] and gets a [Session]; the runner calls the session's
 * `onIdle` when the queue drains or the host asked it to [stop]. The runner
 * owns its own scope, so a host being destroyed never interrupts cleanup.
 *
 * Concurrency model (unchanged from the original service):
 *  - One loop: pull oldest QUEUED -> mark DOWNLOADING -> run as child
 *    [currentJob] -> handle outcome -> repeat.
 *  - Pause/cancel/yield of the ACTIVE item cancels only [currentJob]; the
 *    [interrupt] field tells the loop what the cancellation meant.
 *  - Non-active pause/cancel is a direct DB edit.
 *  - Work is created LAZY so [currentJob] is published BEFORE [currentId]; a
 *    cancel that matches currentId always finds a cancellable job.
 */
@Singleton
class DownloadQueueRunner @Inject constructor(
    private val downloader: ChunkedDownloader,
    private val downloadDao: DownloadDao,
    private val chunkDao: ChunkDao,
    private val progressTracker: DownloadProgressTracker,
    private val conditions: DownloadConditions,
    private val completionNotifier: CompletionNotifier,
    private val serviceCleaner: ServiceCleaner
) {
    /**
     * Why a session ended. DEFERRED = a rule (the schedule) says "not now";
     * the host should shut down and let [DownloadScheduler] set a wake-up.
     */
    enum class StopCause { QUEUE_EMPTY, DEFERRED, HOST_STOPPED }

    /** A host's handle on the running queue. */
    class Session internal constructor(internal val onIdle: (StopCause) -> Unit) {
        @Volatile internal var stopRequested = false
        internal var loop: Job? = null
    }

    /** What the notification shows. Null while idle. */
    data class ActiveFile(val id: String, val filename: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val lock = Any()
    private var session: Session? = null   // guarded by lock
    private var kicked = false             // guarded by lock

    @Volatile private var currentJob: Job? = null
    @Volatile private var currentId: String? = null
    @Volatile private var interrupt: Interrupt? = null

    /**
     * PAUSE / CANCEL come from the user. YIELD means the host is going away
     * (job stopped, foreground-service time limit): the file goes back to
     * QUEUED so it resumes automatically next time.
     */
    private enum class Interrupt { PAUSE, CANCEL, YIELD }

    private val _active = MutableStateFlow<ActiveFile?>(null)
    val active: StateFlow<ActiveFile?> = _active.asStateFlow()

    /**
     * Starts the queue loop for a host. Returns null if a live session is
     * already running (the host should then stand down). If the previous
     * session is still winding down after [stop], the new loop waits for it.
     */
    fun start(onIdle: (StopCause) -> Unit): Session? = synchronized(lock) {
        val prev = session
        if (prev != null && !prev.stopRequested) return null
        val s = Session(onIdle)
        session = s
        kicked = false
        s.loop = scope.launch {
            prev?.loop?.join()
            runLoop(s)
        }
        s
    }

    /**
     * Called after new work is queued. If a live session exists, it's told to
     * look again before exiting (so an enqueue racing with "queue empty" is
     * never lost) and this returns true. False means a host must be started.
     */
    fun offerWork(): Boolean = synchronized(lock) {
        val s = session
        if (s != null && !s.stopRequested) {
            kicked = true
            true
        } else {
            false
        }
    }

    /**
     * Host is going away. The active file is interrupted with progress saved
     * and left QUEUED; the loop exits and calls onIdle(HOST_STOPPED). Returns
     * immediately — cleanup continues on the runner's own scope.
     */
    fun stop(s: Session) {
        synchronized(lock) {
            if (session !== s || s.stopRequested) return
            s.stopRequested = true
        }
        if (currentId != null) {
            interrupt = Interrupt.YIELD
            currentJob?.cancel()
        }
    }

    fun pause(id: String) {
        if (interruptIfActive(id, Interrupt.PAUSE)) return
        scope.launch {
            downloadDao.updateState(id, DownloadState.PAUSED)
            // The loop may have picked this file up while the write was in
            // flight (after its "was it paused?" check): stop it now rather
            // than let it download until the next tap.
            interruptIfActive(id, Interrupt.PAUSE)
        }
    }

    fun cancel(id: String) {
        if (interruptIfActive(id, Interrupt.CANCEL)) return
        scope.launch {
            val entity = downloadDao.getById(id)
            chunkDao.deleteForDownload(id)
            downloadDao.delete(id)
            DownloadFiles.deletePart(entity?.partFilePath)
            progressTracker.clear(id)
            interruptIfActive(id, Interrupt.CANCEL) // same race as pause
        }
    }

    /** Interrupts [id] if it's the file downloading now. */
    private fun interruptIfActive(id: String, why: Interrupt): Boolean {
        if (id != currentId) return false
        interrupt = why
        currentJob?.cancel()
        return true
    }

    // --- loop --------------------------------------------------------------

    private suspend fun runLoop(s: Session) {
        var cause = StopCause.QUEUE_EMPTY
        try {
            // Recover items interrupted by process death / a previous host.
            downloadDao.requeueOrphans()
            while (true) {
                if (s.stopRequested) {
                    cause = StopCause.HOST_STOPPED
                    break
                }
                // Outside the download schedule: don't start the next file.
                if (conditions.currentBlock()?.waitInPlace == false) {
                    cause = StopCause.DEFERRED
                    break
                }
                val next = downloadDao.nextInState(DownloadState.QUEUED)
                if (next == null) {
                    val exit = synchronized(lock) {
                        if (kicked) {
                            kicked = false
                            false
                        } else {
                            if (session === s) session = null
                            true
                        }
                    }
                    if (exit) break else continue
                }
                if (runOne(s, next) is DownloadOutcome.Deferred) {
                    cause = StopCause.DEFERRED
                    break
                }
            }
        } finally {
            withContext(NonCancellable) {
                synchronized(lock) { if (session === s) session = null }
                _active.value = null
                s.onIdle(cause)
            }
        }
    }

    /** Returns the engine's outcome (null if it never ran / was interrupted). */
    private suspend fun runOne(s: Session, entity: DownloadEntity): DownloadOutcome? {
        interrupt = null

        val result = arrayOfNulls<DownloadOutcome>(1)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            result[0] = try {
                downloader.download(entity)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                DownloadOutcome.Failure(t.message ?: "Unexpected error")
            }
        }

        // Publish order: currentJob FIRST, currentId LAST (see class doc).
        currentJob = job
        currentId = entity.id
        _active.value = ActiveFile(entity.id, entity.filename)

        // Guards: a non-active cancel/pause may have raced ahead and deleted
        // or paused this row, or the host may have asked us to stop.
        val fresh = downloadDao.getById(entity.id)
        if (fresh == null || fresh.state == DownloadState.PAUSED || s.stopRequested) {
            job.cancel()
            unpublish()
            progressTracker.clear(entity.id)
            return null
        }

        downloadDao.updateState(entity.id, DownloadState.DOWNLOADING)
        progressTracker.update(
            entity.id, entity.bytesDownloaded, entity.filesize, DownloadState.DOWNLOADING
        )

        job.start()
        try {
            job.join()
        } finally {
            currentJob = null
        }

        val outcome = result[0]
        // An interrupt that lands after the file already completed must not
        // flip a COMPLETED row back to PAUSED/QUEUED.
        val completed = outcome is DownloadOutcome.Success
        withContext(NonCancellable) {
            when (interrupt) {
                Interrupt.PAUSE -> if (!completed) {
                    downloadDao.updateState(entity.id, DownloadState.PAUSED)
                }
                Interrupt.YIELD -> if (!completed) {
                    downloadDao.updateState(entity.id, DownloadState.QUEUED)
                }
                Interrupt.CANCEL -> {
                    chunkDao.deleteForDownload(entity.id)
                    downloadDao.delete(entity.id)
                    DownloadFiles.deletePart(entity.partFilePath)
                    // Honour an explicit cancel even if the file was already promoted.
                    if (completed) DownloadFiles.deleteFinal(entity.finalFilePath)
                }
                null -> when (outcome) {
                    is DownloadOutcome.Failure -> downloadDao.setFailed(entity.id, outcome.message)
                    // Not an error: back in line, resumes in the next window.
                    is DownloadOutcome.Deferred -> downloadDao.updateState(entity.id, DownloadState.QUEUED)
                    is DownloadOutcome.Success -> {
                        completionNotifier.notifyCompleted(entity)
                        serviceCleaner.onCompleted(entity)
                    }
                    null -> Unit
                }
            }
            if (!completed || interrupt == Interrupt.CANCEL) progressTracker.clear(entity.id)
        }
        unpublish()
        return if (interrupt == null) outcome else null
    }

    private fun unpublish() {
        currentJob = null
        currentId = null
        _active.value = null
    }
}
