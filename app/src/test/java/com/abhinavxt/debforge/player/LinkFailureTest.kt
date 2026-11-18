package com.abhinavxt.debforge.player

import com.abhinavxt.debforge.data.youtube.YouTubeException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

class LinkFailureTest {
    @Test fun noConnectionKeepsTheTrackWhateverTheError() {
        assertEquals(LinkFailure.OFFLINE, linkFailure(YouTubeException.Unavailable(), online = false))
        assertEquals(LinkFailure.OFFLINE, linkFailure(null, online = false))
    }

    @Test fun aNetworkFailureKeepsTheTrackEvenWhenOnline() {
        assertEquals(LinkFailure.OFFLINE, linkFailure(YouTubeException.Network(UnknownHostException()), online = true))
        assertEquals(LinkFailure.OFFLINE, linkFailure(RuntimeException(IOException("timeout")), online = true))
    }

    @Test fun aGoneVideoIsDropped() {
        assertEquals(LinkFailure.BROKEN, linkFailure(YouTubeException.Unavailable(), online = true))
        assertEquals(LinkFailure.BROKEN, linkFailure(YouTubeException.Failed(IllegalStateException()), online = true))
        assertEquals(LinkFailure.BROKEN, linkFailure(null, online = true))
    }
}
