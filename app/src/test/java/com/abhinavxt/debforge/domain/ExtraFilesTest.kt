package com.abhinavxt.debforge.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtraFilesTest {

    private val MB = 1024L * 1024

    private fun file(
        name: String,
        size: Long,
        parent: String? = "torrent:1",
        provider: ProviderId = ProviderId.TORBOX
    ) = DownloadItem(
        id = "$provider:$parent:$name",
        provider = provider,
        filename = name,
        sourceRef = name,
        downloadUrl = null,
        host = "Torrent",
        filesize = size,
        maxConnections = 8,
        addedAt = null,
        parentRef = parent
    )

    private fun names(items: List<DownloadItem>) = items.map { it.filename }

    // The case from the screenshot: a movie with promo notes and a cover.
    @Test fun movieWithPromoNotesAndCover() {
        val files = listOf(
            file("Se7en Remastered.mkv", 10_960 * MB),
            file("Downloaded from AngieTorrents.txt", 43),
            file("Downloaded from Glodls.txt", 394),
            file("Downloaded from TGx.txt", 716),
            file("Se7en.jpg", 20 * 1024)
        )
        assertEquals(listOf("Se7en Remastered.mkv"), names(ExtraFiles.hide(files)))
    }

    @Test fun nfoUrlAndSampleAreExtras() {
        val files = listOf(
            file("Show.S01E01.1080p.mkv", 1_500 * MB),
            file("Show.S01E01.nfo", 3_000),
            file("RARBG.url", 120),
            file("Sample/show.s01e01-sample.mkv", 40 * MB)
        )
        assertEquals(listOf("Show.S01E01.1080p.mkv"), names(ExtraFiles.hide(files)))
    }

    @Test fun torrentOfOnlyTextKeepsEverything() {
        val files = listOf(file("notes.txt", 500), file("readme.nfo", 900))
        assertEquals(files, ExtraFiles.hide(files))
    }

    @Test fun imagesNextToEbookAreKept() {
        val files = listOf(file("Book.epub", 3 * MB), file("cover.jpg", 200 * 1024), file("info.txt", 100))
        // Only the note goes; the cover belongs with a book.
        assertEquals(listOf("Book.epub", "cover.jpg"), names(ExtraFiles.hide(files)))
    }

    @Test fun bigTextFileIsKept() {
        val files = listOf(file("Movie.mkv", 2_000 * MB), file("Movie.srt.txt", 90 * 1024))
        assertEquals(files, ExtraFiles.hide(files))
    }

    @Test fun bigImageIsKept() {
        val files = listOf(file("Movie.mkv", 2_000 * MB), file("poster-4k.png", 8 * MB))
        assertEquals(files, ExtraFiles.hide(files))
    }

    @Test fun sampleMeansTheWordNotASubstring() {
        val files = listOf(
            file("Movie.mkv", 2_000 * MB),
            file("Samples of Love.mkv", 200 * MB),   // a real (short) film
            file("Movie.Sample.mkv", 30 * MB)
        )
        assertEquals(listOf("Movie.mkv", "Samples of Love.mkv"), names(ExtraFiles.hide(files)))
    }

    @Test fun hugeFileNamedSampleIsNotASample() {
        val files = listOf(file("Movie.mkv", 2_000 * MB), file("The Sample (2020).mkv", 1_800 * MB))
        assertEquals(files, ExtraFiles.hide(files))
    }

    @Test fun filesWithoutParentAreNeverHidden() {
        val files = listOf(file("Downloaded from X.txt", 40, parent = null), file("clip.mkv", 50 * MB, parent = null))
        assertEquals(files, ExtraFiles.hide(files))
    }

    @Test fun judgedPerTorrentAndPerProvider() {
        val movie = file("Movie.mkv", 2_000 * MB, parent = "torrent:1")
        val movieNote = file("Downloaded from X.txt", 40, parent = "torrent:1")
        // Same parentRef on another service is another torrent: text-only, kept.
        val otherNote = file("Downloaded from X.txt", 40, parent = "torrent:1", provider = ProviderId.ALL_DEBRID)
        // A text-only torrent on the same service: kept.
        val loneNote = file("notes.txt", 40, parent = "torrent:2")
        val out = ExtraFiles.hide(listOf(movie, movieNote, otherNote, loneNote))
        assertEquals(listOf(movie, otherNote, loneNote), out)
    }

    @Test fun orderIsPreserved() {
        val files = listOf(
            file("a.txt", 10), file("E02.mkv", 900 * MB), file("b.jpg", 5_000), file("E01.mkv", 900 * MB)
        )
        assertEquals(listOf("E02.mkv", "E01.mkv"), names(ExtraFiles.hide(files)))
    }

    @Test fun hideInTorrentIgnoresParentRef() {
        // Auto-remove passes one torrent's files, which may lack a parentRef.
        val files = listOf(file("Movie.mkv", 2_000 * MB, parent = null), file("x.nfo", 100, parent = null))
        assertEquals(listOf("Movie.mkv"), names(ExtraFiles.hideInTorrent(files)))
    }

    @Test fun extensionParsing() {
        assertEquals("mkv", ExtraFiles.extensionOf("Folder.v1.0/Movie.MKV"))
        assertEquals("", ExtraFiles.extensionOf("README"))
        assertEquals("", ExtraFiles.extensionOf("dir.with.dots/README"))
        assertTrue(ExtraFiles.isVideo(file("x.m2ts", 1)))
    }
}
