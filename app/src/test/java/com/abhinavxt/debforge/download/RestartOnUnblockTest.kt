package com.abhinavxt.debforge.download

import com.abhinavxt.debforge.download.DownloadScheduler.Companion.shouldRestartOnUnblock
import com.abhinavxt.debforge.download.conditions.Block
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestartOnUnblockTest {
    @Test fun restartsWhenBlockerClears() {
        assertTrue(shouldRestartOnUnblock(Block.NO_NETWORK, null))
        assertTrue(shouldRestartOnUnblock(Block.NEEDS_WIFI, null))
        assertTrue(shouldRestartOnUnblock(Block.OUTSIDE_SCHEDULE, null))
    }

    @Test fun doesNothingOtherwise() {
        assertFalse(shouldRestartOnUnblock(null, null))
        assertFalse(shouldRestartOnUnblock(null, Block.NO_NETWORK))
        assertFalse(shouldRestartOnUnblock(Block.NO_NETWORK, Block.NEEDS_WIFI))
    }
}
