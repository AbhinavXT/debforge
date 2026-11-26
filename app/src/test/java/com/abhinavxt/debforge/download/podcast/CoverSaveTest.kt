package com.abhinavxt.debforge.download.podcast

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.abhinavxt.debforge.download.artworkFile
import com.abhinavxt.debforge.ui.youtube.saveYouTubeThumbnail
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/** The cover save PodcastEnqueuer relies on: writes the artwork file the player and Media list read, never throws. */
@RunWith(RobolectricTestRunner::class)
class CoverSaveTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun client(block: (Interceptor.Chain) -> Response) = OkHttpClient.Builder().addInterceptor(block).build()

    @Test fun savesTheCoverToTheDownloadsArtworkFile() = runBlocking {
        val bytes = byteArrayOf(1, 2, 3)
        val http = client { c ->
            Response.Builder().request(c.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(bytes.toResponseBody("image/jpeg".toMediaType())).build()
        }
        saveYouTubeThumbnail(context, http, "https://example.com/c.jpg", "ep1")
        assertArrayEquals(bytes, artworkFile(context, "ep1").readBytes())
    }

    @Test fun aFailedFetchLeavesNoArtworkAndDoesNotThrow() = runBlocking {
        saveYouTubeThumbnail(context, client { throw IOException("offline") }, "https://example.com/c.jpg", "ep2")
        assertFalse(artworkFile(context, "ep2").exists())
    }
}
