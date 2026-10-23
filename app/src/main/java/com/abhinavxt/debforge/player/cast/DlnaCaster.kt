package com.abhinavxt.debforge.player.cast

import android.content.Context
import android.net.wifi.WifiManager
import com.abhinavxt.debforge.di.ApiHttpClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.IOException
import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.xml.parsers.DocumentBuilderFactory

/** A TV (or other UPnP / DLNA media renderer) that can play a link. */
data class Renderer(val name: String, val controlUrl: String, val serviceType: String)

/**
 * The UPnP pieces that don't touch the network: SSDP search and replies,
 * device descriptions, SOAP envelopes and times. Unit-tested in UpnpTest.
 */
object Upnp {
    const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
    const val SSDP_HOST = "239.255.255.250"
    const val SSDP_PORT = 1900

    /** Renderers answer to either; asking for both finds more TVs. */
    val SEARCH_TARGETS = listOf(AV_TRANSPORT, "urn:schemas-upnp-org:device:MediaRenderer:1")

    fun mSearch(target: String): String =
        "M-SEARCH * HTTP/1.1\r\nHOST: $SSDP_HOST:$SSDP_PORT\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $target\r\n\r\n"

    /** The LOCATION header of an SSDP reply: where the device describes itself. */
    fun location(reply: String): String? =
        reply.lineSequence().firstOrNull { it.startsWith("location:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.takeIf { it.isNotEmpty() }

    /** A device description's renderer, if it has an AV transport; [location] resolves relative links. */
    fun renderer(location: String, xml: String): Renderer? {
        val root = parse(xml)
        val name = root.firstText("friendlyName") ?: return null
        val base = (root.firstText("URLBase") ?: location).toHttpUrlOrNull() ?: return null
        val services = root.getElementsByTagName("service")
        for (i in 0 until services.length) {
            val s = services.item(i) as Element
            val type = s.firstText("serviceType") ?: continue
            if (!type.startsWith("urn:schemas-upnp-org:service:AVTransport:")) continue
            val control = s.firstText("controlURL")?.let { base.resolve(it) } ?: continue
            return Renderer(name, control.toString(), type)
        }
        return null
    }

    /** A SOAP call to the AV transport; [args] in order (renderers can be strict about it). */
    fun envelope(serviceType: String, action: String, args: List<Pair<String, String>>): String =
        buildString {
            append("""<?xml version="1.0" encoding="utf-8"?>""")
            append("""<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>""")
            append("""<u:$action xmlns:u="$serviceType">""")
            args.forEach { (k, v) -> append("<$k>").append(escape(v)).append("</$k>") }
            append("</u:$action></s:Body></s:Envelope>")
        }

    /** DIDL-Lite for SetAVTransportURI: some TVs refuse a link without a title and type. */
    fun metadata(url: String, title: String): String {
        val mime = when (url.substringBefore('?').substringAfterLast('.', "").lowercase()) {
            "mkv" -> "video/x-matroska"
            "mp4", "m4v" -> "video/mp4"
            "avi" -> "video/x-msvideo"
            "webm" -> "video/webm"
            "ts", "m2ts" -> "video/mp2t"
            else -> "video/*"
        }
        return """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">""" +
            """<item id="0" parentID="-1" restricted="1"><dc:title>${escape(title)}</dc:title>""" +
            """<upnp:class>object.item.videoItem</upnp:class><res protocolInfo="http-get:*:$mime:*">${escape(url)}</res></item></DIDL-Lite>"""
    }

    /** 3_723_000 → "1:02:03". */
    fun time(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d:%02d".format(Locale.ROOT, s / 3600, s / 60 % 60, s % 60)
    }

    /** "1:02:03" or "01:02:03.500" → millis; null for "NOT_IMPLEMENTED" and the like. */
    fun millis(time: String): Long? {
        val parts = time.trim().split(':')
        if (parts.size != 3) return null
        val h = parts[0].toLongOrNull() ?: return null
        val m = parts[1].toLongOrNull() ?: return null
        val sec = parts[2].toDoubleOrNull() ?: return null
        return (h * 3600 + m * 60) * 1000 + (sec * 1000).toLong()
    }

    /** The text of the first [tag] in a SOAP reply. */
    fun replyValue(xml: String, tag: String): String? = parse(xml).firstText(tag)

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun parse(xml: String): Element {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            // No DTDs or external entities from a device we don't control.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        }
        return factory.newDocumentBuilder().parse(InputSource(StringReader(xml.trim()))).documentElement
    }

    private fun Element.firstText(local: String): String? {
        val nodes = getElementsByTagNameNS("*", local)
        return if (nodes.length == 0) null else nodes.item(0).textContent?.trim()?.takeIf { it.isNotEmpty() }
    }
}

/**
 * Casts a stream to a TV on the same network over DLNA / UPnP: the TV is
 * handed the service's link and fetches it itself, so the phone can sleep.
 * No Google Play services (unlike Chromecast), so it works on every build.
 */
@Singleton
class DlnaCaster @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApiHttpClient api: OkHttpClient
) {
    private val http = api.newBuilder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()

    /** Renderers that answer within [waitMs]; empty when there are none (or no Wi-Fi). */
    suspend fun discover(waitMs: Long = 3_000): List<Renderer> = withContext(Dispatchers.IO) {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        // Android drops multicast replies to save battery unless an app holds this.
        val lock = wifi?.createMulticastLock("debforge-cast")?.apply { setReferenceCounted(false); acquire() }
        try {
            val locations = LinkedHashSet<String>()
            DatagramSocket().use { socket ->
                socket.soTimeout = 250
                val group = InetAddress.getByName(Upnp.SSDP_HOST)
                // Twice: it's UDP, and a TV waking from standby can miss the first.
                repeat(2) {
                    Upnp.SEARCH_TARGETS.forEach { st ->
                        val msg = Upnp.mSearch(st).toByteArray()
                        socket.send(DatagramPacket(msg, msg.size, group, Upnp.SSDP_PORT))
                    }
                }
                val buf = ByteArray(4096)
                val end = System.currentTimeMillis() + waitMs
                while (System.currentTimeMillis() < end) {
                    ensureActive()
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        socket.receive(p)
                        Upnp.location(String(buf, 0, p.length))?.let(locations::add)
                    } catch (_: SocketTimeoutException) {
                    }
                }
            }
            coroutineScope {
                locations.map { async { describe(it) } }.awaitAll().filterNotNull().distinctBy { it.controlUrl }
            }
        } finally {
            lock?.release()
        }
    }

    private fun describe(location: String): Renderer? = runCatching {
        http.newCall(Request.Builder().url(location).build()).execute().use { r ->
            if (!r.isSuccessful) null else Upnp.renderer(location, r.body?.string().orEmpty())
        }
    }.getOrNull()

    /** Starts [url] on [to], at [startMs] when the TV can seek. */
    suspend fun play(to: Renderer, url: String, title: String, startMs: Long) {
        // A TV still playing something else may refuse a new link.
        runCatching { call(to, "Stop") }
        call(to, "SetAVTransportURI", "CurrentURI" to url, "CurrentURIMetaData" to Upnp.metadata(url, title))
        call(to, "Play", "Speed" to "1")
        if (startMs < 5_000) return
        // Most TVs only seek once the stream has opened: keep trying for a bit.
        repeat(6) {
            delay(1_500)
            if (runCatching { call(to, "Seek", "Unit" to "REL_TIME", "Target" to Upnp.time(startMs)) }.isSuccess) return
        }
    }

    suspend fun pause(on: Renderer) { call(on, "Pause") }
    suspend fun resume(on: Renderer) { call(on, "Play", "Speed" to "1") }
    suspend fun stop(on: Renderer) { call(on, "Stop") }

    /** Where the TV is in the video; null if it won't say. */
    suspend fun positionMs(on: Renderer): Long? =
        runCatching { Upnp.replyValue(call(on, "GetPositionInfo"), "RelTime")?.let(Upnp::millis) }.getOrNull()

    private suspend fun call(to: Renderer, action: String, vararg args: Pair<String, String>): String =
        withContext(Dispatchers.IO) {
            val body = Upnp.envelope(to.serviceType, action, listOf("InstanceID" to "0") + args)
            val request = Request.Builder()
                .url(to.controlUrl)
                .header("SOAPACTION", "\"${to.serviceType}#$action\"")
                .post(body.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType()))
                .build()
            http.newCall(request).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw IOException("$action: HTTP ${r.code}")
                text
            }
        }
}
