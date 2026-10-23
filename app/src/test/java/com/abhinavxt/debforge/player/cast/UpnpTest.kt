package com.abhinavxt.debforge.player.cast

import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.InetSocketAddress
import java.util.Collections

@RunWith(RobolectricTestRunner::class)
class UpnpTest {

    private val description = """
        <?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0">
          <device>
            <friendlyName>Living Room TV</friendlyName>
            <serviceList>
              <service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><controlURL>/rc</controlURL></service>
              <service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>upnp/control/AVTransport1</controlURL></service>
            </serviceList>
          </device>
        </root>
    """.trimIndent()

    @Test fun readsSsdpLocation() {
        val reply = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nLocation: http://192.168.1.20:9197/dmr\r\nST: x\r\n\r\n"
        assertEquals("http://192.168.1.20:9197/dmr", Upnp.location(reply))
        assertNull(Upnp.location("HTTP/1.1 200 OK\r\n\r\n"))
    }

    @Test fun findsTheAvTransportAndResolvesItsLink() {
        val r = Upnp.renderer("http://192.168.1.20:9197/dmr/desc.xml", description)!!
        assertEquals("Living Room TV", r.name)
        assertEquals("http://192.168.1.20:9197/dmr/upnp/control/AVTransport1", r.controlUrl)
        assertEquals(Upnp.AV_TRANSPORT, r.serviceType)
        assertNull(Upnp.renderer("http://x/", description.replace("AVTransport", "ConnectionManager")))
    }

    @Test fun escapesLinksAndTitles() {
        val env = Upnp.envelope(Upnp.AV_TRANSPORT, "SetAVTransportURI", listOf("CurrentURI" to "https://h/a.mkv?x=1&y=2"))
        assertTrue(env.contains("<CurrentURI>https://h/a.mkv?x=1&amp;y=2</CurrentURI>"))
        val meta = Upnp.metadata("https://h/Tom & Jerry.mkv?t=1", "Tom & Jerry")
        assertTrue(meta.contains("<dc:title>Tom &amp; Jerry</dc:title>"))
        assertTrue(meta.contains("video/x-matroska"))
    }

    @Test fun timesBothWays() {
        assertEquals("1:02:03", Upnp.time(3_723_000))
        assertEquals("0:00:00", Upnp.time(-5))
        assertEquals(3_723_500L, Upnp.millis("01:02:03.500"))
        assertNull(Upnp.millis("NOT_IMPLEMENTED"))
    }

    /** The whole exchange with a fake TV: describe, play, seek, ask the position, stop. */
    @Test fun drivesARenderer() = runBlocking {
        val actions = Collections.synchronizedList(mutableListOf<String>())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/desc.xml") { ex ->
            val body = description.toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
        }
        server.createContext("/upnp/control/AVTransport1") { ex ->
            val action = ex.requestHeaders.getFirst("SOAPACTION").substringAfter('#').trimEnd('"')
            val sent = ex.requestBody.readBytes().decodeToString()
            actions += if (action == "Seek") "Seek " + sent.substringAfter("<Target>").substringBefore("<") else action
            val reply = if (action == "GetPositionInfo") "<s:Envelope xmlns:s=\"s\"><s:Body><u:R xmlns:u=\"u\"><RelTime>0:20:00</RelTime></u:R></s:Body></s:Envelope>"
            else "<ok/>"
            val body = reply.toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val caster = DlnaCaster(ApplicationProvider.getApplicationContext(), OkHttpClient())
            val location = "http://127.0.0.1:${server.address.port}/desc.xml"
            val tv = Upnp.renderer(location, description)!!
            caster.play(tv, "https://cdn.example/x.mkv", "X", startMs = 600_000)
            assertEquals(1_200_000L, caster.positionMs(tv))
            caster.stop(tv)
            assertEquals(listOf("Stop", "SetAVTransportURI", "Play", "Seek 0:10:00", "GetPositionInfo", "Stop"), actions)
        } finally {
            server.stop(0)
        }
    }
}
