package com.abhinavxt.debforge.download

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
}
