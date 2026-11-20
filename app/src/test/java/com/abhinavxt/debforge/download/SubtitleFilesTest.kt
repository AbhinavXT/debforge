package com.abhinavxt.debforge.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleFilesTest {
    @Test fun extensionFromName() {
        assertEquals("srt", SubtitleFiles.extensionFor("Movie.EN.SRT", "application/octet-stream"))
        assertEquals("vtt", SubtitleFiles.extensionFor("x.vtt", null))
        assertEquals("ssa", SubtitleFiles.extensionFor("x.ssa", null))
    }

    @Test fun extensionFallsBackToMime() {
        assertEquals("srt", SubtitleFiles.extensionFor("download", "application/x-subrip"))
        assertEquals("vtt", SubtitleFiles.extensionFor(null, "text/vtt"))
    }

    @Test fun otherFilesAreRejected() {
        assertNull(SubtitleFiles.extensionFor("movie.mkv", "video/x-matroska"))
        assertNull(SubtitleFiles.extensionFor("notes.txt", "text/plain"))
        assertNull(SubtitleFiles.extensionFor(null, null))
    }

    @Test fun contentCheck() {
        assertTrue(SubtitleFiles.looksLike("srt", "﻿1\n00:00:01,000 --> 00:00:02,000\nHi"))
        assertFalse(SubtitleFiles.looksLike("srt", "MZ binary junk"))
        assertTrue(SubtitleFiles.looksLike("vtt", "WEBVTT\n\n00:01.000 --> 00:02.000"))
        assertFalse(SubtitleFiles.looksLike("vtt", "1\n00:00:01,000 --> 00:00:02,000"))
        assertTrue(SubtitleFiles.looksLike("ass", "[Script Info]\nTitle: x"))
        assertFalse(SubtitleFiles.looksLike("ass", "hello"))
    }

    @Test fun targetNameIsBasePlusExtension() {
        assertEquals("Movie.srt", SubtitleFiles.targetName("Movie", "srt", listOf("Movie.mkv", "Other.srt")))
    }

    @Test fun targetNameAvoidsCollisions() {
        assertEquals("Movie.1.srt", SubtitleFiles.targetName("Movie", "srt", listOf("movie.SRT")))
        assertEquals("Movie.3.srt", SubtitleFiles.targetName("Movie", "srt", listOf("Movie.srt", "Movie.1.srt", "Movie.2.srt")))
        assertEquals("Movie.vtt", SubtitleFiles.targetName("Movie", "vtt", listOf("Movie.srt")))
    }

    private val SRT = "1\n00:00:01,000 --> 00:00:02,000\nHi"

    @Test fun decodeHeadUtf16WithBom() {
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + SRT.toByteArray(Charsets.UTF_16LE)
        val be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + SRT.toByteArray(Charsets.UTF_16BE)
        assertTrue(SubtitleFiles.looksLike("srt", SubtitleFiles.decodeHead(le)))
        assertTrue(SubtitleFiles.looksLike("srt", SubtitleFiles.decodeHead(be)))
    }

    @Test fun decodeHeadUtf16WithoutBom() {
        assertTrue(SubtitleFiles.looksLike("srt", SubtitleFiles.decodeHead(SRT.toByteArray(Charsets.UTF_16LE))))
        assertTrue(SubtitleFiles.looksLike("srt", SubtitleFiles.decodeHead(SRT.toByteArray(Charsets.UTF_16BE))))
    }

    @Test fun decodeHeadUtf8AndJunk() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + SRT.toByteArray()
        assertTrue(SubtitleFiles.looksLike("srt", SubtitleFiles.decodeHead(bom)))
        assertFalse(SubtitleFiles.looksLike("srt", SubtitleFiles.decodeHead("MZ binary junk".toByteArray())))
        assertEquals("", SubtitleFiles.decodeHead(ByteArray(0)))
    }
}
