package com.abhinavxt.debforge.ui.add

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PendingAddStoreTest {
    private fun share(text: String) = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, text)

    @Test fun sharedYouTubeLinkGoesToYouTubeOnly() {
        val s = PendingAddStore()
        val text = "Some title https://youtu.be/dQw4w9WgXcQ?si=abc"
        assertTrue(s.offerFromIntent(share(text)))
        assertEquals(text, s.youtube.value)
        assertNull(s.pending.value)
        assertEquals(text, s.consumeYouTube())
        assertNull(s.youtube.value)
    }

    @Test fun playlistOnlyLinkGoesToYouTube() {
        val s = PendingAddStore()
        val text = "https://www.youtube.com/playlist?list=PL1234567890"
        s.offerFromIntent(share(text))
        assertEquals(text, s.youtube.value)
        assertNull(s.pending.value)
    }

    @Test fun magnetAndFileUrlKeepExistingPath() {
        for (t in listOf("magnet:?xt=urn:btih:abc", "https://example.com/file.mkv")) {
            val s = PendingAddStore()
            assertTrue(s.offerFromIntent(share(t)))
            assertEquals(PendingAdd.Text(t), s.pending.value)
            assertNull(s.youtube.value)
        }
    }
}
