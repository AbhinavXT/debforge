package com.abhinavxt.debforge.download

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
}
