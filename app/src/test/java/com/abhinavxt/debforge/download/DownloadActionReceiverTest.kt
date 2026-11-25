package com.abhinavxt.debforge.download

import com.abhinavxt.debforge.domain.DownloadProgress
import com.abhinavxt.debforge.domain.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadActionReceiverTest {
    @Test fun buttonsMapToSteps() {
        assertEquals(NotifStep.PAUSE_ALL, DownloadActionReceiver.stepFor(DownloadActionReceiver.ACTION_PAUSE))
        assertEquals(NotifStep.ASK_CANCEL, DownloadActionReceiver.stepFor(DownloadActionReceiver.ACTION_CANCEL_ASK))
        assertEquals(NotifStep.CANCEL_RUNNING, DownloadActionReceiver.stepFor(DownloadActionReceiver.ACTION_CANCEL_YES))
        assertEquals(NotifStep.KEEP, DownloadActionReceiver.stepFor(DownloadActionReceiver.ACTION_KEEP))
    }

    @Test fun firstCancelTapOnlyAsks() {
        // Only the confirmation's Yes may discard progress.
        assertEquals(NotifStep.ASK_CANCEL, DownloadActionReceiver.stepFor(DownloadActionReceiver.ACTION_CANCEL_ASK))
    }

    @Test fun unknownOrMissingActionIsIgnored() {
        assertEquals(NotifStep.IGNORE, DownloadActionReceiver.stepFor(null))
        assertEquals(NotifStep.IGNORE, DownloadActionReceiver.stepFor("android.intent.action.VIEW"))
    }

    private val D = DownloadState.DOWNLOADING

    @Test fun cancelsAskedStillRunning() =
        assertEquals(listOf("a", "b"), idsToCancel(listOf("a", "b"), mapOf("a" to D, "b" to DownloadState.QUEUED)))

    @Test fun finishedSinceAskingIsLeftAlone() =
        assertEquals(listOf("b"), idsToCancel(listOf("a", "b"), mapOf("a" to DownloadState.COMPLETED, "b" to D)))

    @Test fun userPausedSinceAskingIsLeftAlone() =
        assertEquals(emptyList<String>(), idsToCancel(listOf("a"), mapOf("a" to DownloadState.PAUSED)))

    @Test fun otherDownloadThatStartedLaterIsNotInTheAskedList() =
        // "a" finished, "c" took over: only asked ids are ever considered.
        assertEquals(emptyList<String>(), idsToCancel(listOf("a"), mapOf("a" to DownloadState.COMPLETED, "c" to D)))

    @Test fun deletedRowIsSkipped() =
        assertEquals(emptyList<String>(), idsToCancel(listOf("a"), mapOf("a" to null)))

    @Test fun reAskedUsesOnlyTheNewIds() =
        assertEquals(listOf("c"), idsToCancel(listOf("c"), mapOf("a" to D, "c" to D)))

    @Test fun resumeOnlyWhatThisPauseHeld() {
        // "x" was paused earlier on purpose (never marked); "a" was resumed in-app, "b" finished, "d" deleted.
        val states = mapOf("a" to D, "b" to DownloadState.COMPLETED, "c" to DownloadState.PAUSED, "d" to null, "x" to DownloadState.PAUSED)
        assertEquals(listOf("c"), idsToResume(listOf("a", "b", "c", "d"), states))
    }

    private fun p(fraction: Float, finishing: Boolean = false, wait: Int? = null) = DownloadProgress(
        "a", (fraction * 100).toLong(), 100, 1, DownloadState.DOWNLOADING, wait, finishing
    )

    @Test fun throttleLetsFirstFinalAndStateChangesThrough() {
        val n = DownloadNotifications
        assertEquals(true, n.shouldPost(null, 10, p(0.1f)))
        assertEquals(true, n.shouldPost(0, 10, null))
        assertEquals(true, n.shouldPost(0, 10, p(1f)))
        assertEquals(true, n.shouldPost(0, 10, p(0.5f, finishing = true)))
        assertEquals(true, n.shouldPost(0, 10, p(0.5f, wait = 1)))
    }

    @Test fun throttleDropsTicksWithinASecond() {
        assertEquals(false, DownloadNotifications.shouldPost(1000, 1999, p(0.5f)))
        assertEquals(true, DownloadNotifications.shouldPost(1000, 2000, p(0.5f)))
    }

    private val ask = CancelAsk(listOf("a"), listOf("A.mkv"))

    @Test fun questionHoldsWhileAskedFileIsActive() =
        assertEquals(ask, DownloadNotifications.effectiveAsk(ask, "a"))

    @Test fun questionVanishesWhenAnotherFileTakesOver() =
        assertEquals(null, DownloadNotifications.effectiveAsk(ask, "b"))

    @Test fun questionVanishesWhenNothingIsActiveOrAsked() {
        assertEquals(null, DownloadNotifications.effectiveAsk(ask, null))
        assertEquals(null, DownloadNotifications.effectiveAsk(null, "a"))
    }
}
