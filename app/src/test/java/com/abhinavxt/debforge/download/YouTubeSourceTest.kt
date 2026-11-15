package com.abhinavxt.debforge.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeSourceTest {
    private fun ranges(length: Long, piece: Long) =
        YouTubeSource.planPieces("u", length, piece).map { it.offset!! to it.length!! }

    @Test fun lastPieceIsShort() =
        assertEquals(listOf(0L to 4L, 4L to 4L, 8L to 2L), ranges(10, 4))

    @Test fun exactMultipleHasNoEmptyPiece() =
        assertEquals(listOf(0L to 4L, 4L to 4L), ranges(8, 4))

    @Test fun smallerThanOnePiece() = assertEquals(listOf(0L to 3L), ranges(3, 4))

    @Test fun zeroOrUnknownLengthHasNoPieces() {
        assertTrue(ranges(0, 4).isEmpty())
        assertTrue(ranges(-1, 4).isEmpty())
    }

    @Test fun piecesCoverTheWholeLength() {
        val p = YouTubeSource.planPieces("u", 12_345_678)
        assertEquals(12_345_678L, p.sumOf { it.length!! })
        assertEquals(4, p.size)
    }

    @Test fun spaceNeedsTwiceTheSize() {
        assertNull(YouTubeSource.spaceError(100, 200))
        assertNull(YouTubeSource.spaceError(100, null))
        assertTrue(YouTubeSource.spaceError(100, 199)!!.startsWith("Not enough space"))
    }

    @Test fun spaceCountsBytesAlreadyOnDisk() {
        // 1 GB stream, 900 MB down: needs 100 MB left + 1 GB for the joined file.
        assertNull(YouTubeSource.spaceError(1000, 1100, 900))
        assertTrue(YouTubeSource.spaceError(1000, 1099, 900)!!.startsWith("Not enough space"))
        assertNull(YouTubeSource.spaceError(1000, 1000, 1000))
    }

    @Test fun outputFormatFollowsExtension() {
        assertEquals(StreamMuxer.Output.WEBM, StreamMuxer.Output.of("/a/b.webm.part".removeSuffix(".part")))
        assertEquals(StreamMuxer.Output.WEBM, StreamMuxer.Output.of("x.WEBA"))
        assertEquals(StreamMuxer.Output.MP4, StreamMuxer.Output.of("x.m4a"))
        assertEquals(StreamMuxer.Output.MP4, StreamMuxer.Output.of("x.mp4"))
    }
}
