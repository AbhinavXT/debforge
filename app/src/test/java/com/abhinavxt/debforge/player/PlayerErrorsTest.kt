package com.abhinavxt.debforge.player

import androidx.media3.common.PlaybackException
import com.abhinavxt.debforge.player.PlayerErrors.YouTubeFailure
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerErrorsTest {
    private val bad = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS

    @Test fun refusedLinkIs403And410Only() {
        assertEquals(YouTubeFailure.LINK_REFUSED, PlayerErrors.youTubeFailure(bad, 403))
        assertEquals(YouTubeFailure.LINK_REFUSED, PlayerErrors.youTubeFailure(bad, 410))
        assertEquals(YouTubeFailure.NETWORK, PlayerErrors.youTubeFailure(bad, 503))
        assertEquals(YouTubeFailure.NETWORK, PlayerErrors.youTubeFailure(bad, null))
    }

    @Test fun lostConnectionIsNetworkNotRefusal() {
        assertEquals(YouTubeFailure.NETWORK, PlayerErrors.youTubeFailure(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, null))
        assertEquals(YouTubeFailure.NETWORK, PlayerErrors.youTubeFailure(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, 403))
    }

    @Test fun decoderErrorsAreDecoder() {
        assertEquals(YouTubeFailure.DECODER, PlayerErrors.youTubeFailure(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, null))
        assertEquals(YouTubeFailure.DECODER, PlayerErrors.youTubeFailure(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, null))
    }

    @Test fun otherStaysOther() =
        assertEquals(YouTubeFailure.OTHER, PlayerErrors.youTubeFailure(PlaybackException.ERROR_CODE_UNSPECIFIED, null))
}
