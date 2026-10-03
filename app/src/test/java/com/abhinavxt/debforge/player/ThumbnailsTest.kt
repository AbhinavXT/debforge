package com.abhinavxt.debforge.player

import org.junit.Assert.assertEquals
import org.junit.Test

class ThumbnailsTest {

    @Test fun shortVideosUseTwoSecondSpots() {
        assertEquals(0L, Thumbnails.bucket(1_999, 300_000))
        assertEquals(2_000L, Thumbnails.bucket(2_500, 300_000))
    }

    @Test fun longVideosHaveAboutThreeHundredSpots() {
        val twoHours = 2 * 60 * 60_000L // 24 s apart
        assertEquals(24_000L, Thumbnails.bucket(30_000, twoHours))
        assertEquals(Thumbnails.bucket(48_000, twoHours), Thumbnails.bucket(71_999, twoHours))
    }

    @Test fun unknownLengthAndNegativePositions() {
        assertEquals(4_000L, Thumbnails.bucket(5_000, 0))
        assertEquals(0L, Thumbnails.bucket(-500, 60_000))
    }
}
