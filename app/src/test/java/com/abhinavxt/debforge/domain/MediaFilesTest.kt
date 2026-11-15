package com.abhinavxt.debforge.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaFilesTest {
    @Test fun audioVideoAndOther() {
        assertEquals(FileKind.Audio, kindOf("song.m4a"))
        assertEquals(FileKind.Audio, kindOf("a.b.opus"))
        assertEquals(FileKind.Video, kindOf("Movie.2010.1080p.mkv"))
        assertEquals(FileKind.Video, kindOf("clip.ts"))
        assertEquals(FileKind.File, kindOf("book.pdf"))
    }
    @Test fun upperCaseExtension() {
        assertEquals(FileKind.Video, kindOf("CLIP.MP4"))
        assertEquals(FileKind.Audio, kindOf("Song.FLAC"))
    }
    @Test fun noExtension() {
        assertEquals(FileKind.File, kindOf("README"))
        assertEquals(FileKind.File, kindOf("mp4"))
        assertEquals(FileKind.File, kindOf(""))
    }
}
