package com.abhinavxt.debforge.player.youtube

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A server over [data]: honours Range (206 + Content-Range) unless [ignoreRange], then answers 200 with everything. */
@RunWith(RobolectricTestRunner::class)
class WindowedDataSourceTest {
    private class FakeHttp(val data: ByteArray, val ignoreRange: Boolean = false) : HttpDataSource {
        var opens = 0
        private var headers: Map<String, List<String>> = emptyMap()
        private var pos = 0
        private var end = 0
        private var spec: DataSpec? = null

        override fun open(dataSpec: DataSpec): Long {
            opens++
            spec = dataSpec
            val start = dataSpec.position.toInt()
            if (start >= data.size && start > 0) throw HttpDataSource.InvalidResponseCodeException(416, null, null, emptyMap(), dataSpec, ByteArray(0))
            if (ignoreRange) {
                headers = emptyMap()
                pos = start
                // like DefaultHttpDataSource: a bounded spec still truncates the 200 body
                end = if (dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(data.size, start + dataSpec.length.toInt()) else data.size
            } else {
                pos = start
                end = if (dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(data.size, start + dataSpec.length.toInt()) else data.size
                headers = mapOf("Content-Range" to listOf("bytes $start-${end - 1}/${data.size}"))
            }
            return (end - pos).toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (pos >= end) return C.RESULT_END_OF_INPUT
            val n = minOf(length, end - pos)
            System.arraycopy(data, pos, buffer, offset, n)
            pos += n
            return n
        }

        override fun close() {}
        override fun getUri(): Uri? = spec?.uri
        override fun getResponseHeaders() = headers
        override fun addTransferListener(transferListener: TransferListener) {}
        override fun getResponseCode() = if (ignoreRange) 200 else 206
        override fun setRequestProperty(name: String, value: String) {}
        override fun clearRequestProperty(name: String) {}
        override fun clearAllRequestProperties() {}
    }

    private fun readAll(src: WindowedDataSource, spec: DataSpec): ByteArray {
        val declared = src.open(spec)
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(7)
        while (true) {
            val n = src.read(buf, 0, buf.size)
            if (n == C.RESULT_END_OF_INPUT) break
            out.write(buf, 0, n)
        }
        if (declared != C.LENGTH_UNSET.toLong()) assertEquals(declared, out.size().toLong())
        src.close()
        return out.toByteArray()
    }

    private fun spec(position: Long = 0, length: Long = C.LENGTH_UNSET.toLong()) =
        DataSpec.Builder().setUri(Uri.parse("https://example.com/v")).setPosition(position).setLength(length).build()

    private val data = ByteArray(100) { it.toByte() }

    @Test fun readsEverythingAcrossWindows() {
        val http = FakeHttp(data)
        assertTrue(data.contentEquals(readAll(WindowedDataSource(http, 30), spec())))
    }

    @Test fun windowEndingExactlyAtEndOfFileEndsCleanly() {
        val http = FakeHttp(data)
        // 100 bytes in windows of 25: the last window ends exactly at EOF, no further request is needed.
        assertTrue(data.contentEquals(readAll(WindowedDataSource(http, 25), spec())))
        assertEquals(4, http.opens)
    }

    @Test fun boundedReadStopsAtItsLength() {
        val http = FakeHttp(data)
        assertTrue(data.copyOfRange(10, 70).contentEquals(readAll(WindowedDataSource(http, 25), spec(10, 60))))
    }

    @Test fun serverIgnoringRangeIsReadWholeNotTruncated() {
        val http = FakeHttp(data, ignoreRange = true)
        assertTrue(data.copyOfRange(20, 100).contentEquals(readAll(WindowedDataSource(http, 25), spec(20))))
    }
}
