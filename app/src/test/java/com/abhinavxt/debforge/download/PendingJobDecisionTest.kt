package com.abhinavxt.debforge.download

import android.app.job.JobScheduler
import com.abhinavxt.debforge.download.DownloadScheduler.Companion.shouldRecover
import com.abhinavxt.debforge.download.DownloadScheduler.Companion.shouldReplacePending
import com.abhinavxt.debforge.download.conditions.Block
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingJobDecisionTest {
    private val retry = JobScheduler.PENDING_JOB_REASON_UNDEFINED
    private val optimisation = JobScheduler.PENDING_JOB_REASON_JOB_SCHEDULER_OPTIMIZATION

    @Test fun replacesJobWaitingOnSystemRetry() {
        assertTrue(shouldReplacePending(retry, sessionLive = false, block = null, foreground = true))
        assertTrue(shouldReplacePending(optimisation, sessionLive = false, block = null, foreground = true))
    }

    @Test fun replacesJobWaitingOutBackoff() {
        val backoff = JobScheduler.PENDING_JOB_REASON_CONSTRAINT_MINIMUM_LATENCY
        assertTrue(shouldReplacePending(backoff, sessionLive = false, block = null, foreground = true))
        assertFalse(shouldReplacePending(backoff, sessionLive = true, block = null, foreground = true))
        assertFalse(shouldReplacePending(backoff, false, null, foreground = false))
        assertFalse(shouldReplacePending(backoff, false, Block.NO_NETWORK, true))
        assertFalse(shouldReplacePending(backoff, false, null, true, recentlyScheduled = true))
    }

    @Test fun neverReplacesExecutingOrLiveJob() {
        assertFalse(shouldReplacePending(JobScheduler.PENDING_JOB_REASON_EXECUTING, false, null, true))
        assertFalse(shouldReplacePending(retry, sessionLive = true, block = null, foreground = true))
    }

    @Test fun leavesSystemHeldReasonsAlone() {
        listOf(
            JobScheduler.PENDING_JOB_REASON_DEVICE_STATE,
            JobScheduler.PENDING_JOB_REASON_QUOTA,
            JobScheduler.PENDING_JOB_REASON_APP_STANDBY,
            JobScheduler.PENDING_JOB_REASON_BACKGROUND_RESTRICTION,
            JobScheduler.PENDING_JOB_REASON_USER,
        ).forEach { assertFalse("reason $it", shouldReplacePending(it, false, null, true)) }
    }

    @Test fun doesNotReplaceWhenBlockedBackgroundUnknownOrJustScheduled() {
        assertFalse(shouldReplacePending(retry, false, Block.NO_NETWORK, true))
        assertFalse(shouldReplacePending(retry, false, null, foreground = false))
        assertFalse(shouldReplacePending(null, false, null, true))
        assertFalse(shouldReplacePending(retry, false, null, true, recentlyScheduled = true))
    }

    @Test fun recoversOnlyWhenIdleWithWork() {
        assertTrue(shouldRecover(hostRunning = false, hasPendingWork = true))
        assertFalse(shouldRecover(hostRunning = true, hasPendingWork = true))
        assertFalse(shouldRecover(hostRunning = false, hasPendingWork = false))
    }
}
