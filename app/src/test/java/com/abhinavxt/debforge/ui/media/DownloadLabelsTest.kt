package com.abhinavxt.debforge.ui.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadLabelsTest {
    @Test fun webaIsOpus() = assertEquals("Opus", extensionLabel("Song.weba"))
    @Test fun otherExtensionsUpperCased() = assertEquals("M4A", extensionLabel("Song.m4a"))
    @Test fun noExtension() = assertNull(extensionLabel("Song"))
}
