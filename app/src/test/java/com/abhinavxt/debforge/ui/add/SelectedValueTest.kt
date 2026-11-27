package com.abhinavxt.debforge.ui.add

import com.abhinavxt.debforge.domain.TorrentEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SelectedValueTest {
    private val files = listOf(TorrentEntry("a/1.mkv", 1), TorrentEntry("a/2.mkv", 2), TorrentEntry("a/3.nfo", 3))

    @Test fun allTickedIsNull() = assertNull(selectedValue(files, emptySet()))
    @Test fun noPreviewIsNull() = assertNull(selectedValue(null, setOf("x")))
    @Test fun staleUntickedIgnored() = assertNull(selectedValue(files, setOf("gone")))
    @Test fun subsetJoinsTickedPaths() = assertEquals("a/1.mkv\na/2.mkv", selectedValue(files, setOf("a/3.nfo")))
    @Test fun noneTickedIsEmptyString() = assertEquals("", selectedValue(files, files.map { it.path }.toSet()))
}
