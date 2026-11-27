package com.abhinavxt.debforge.domain

import java.security.MessageDigest

/** One file inside a torrent: slash-joined [path] and its [size] in bytes. */
data class TorrentEntry(val path: String, val size: Long)

/**
 * BitTorrent v1 info-hashes (40 lowercase hex chars), used to ask a service
 * whether a torrent is already cached before adding it.
 */
object TorrentHash {

    private val BTIH = Regex("""xt=urn:btih:([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)

    /** From a magnet URI's `xt=urn:btih:` (hex or base32 form), else null. */
    fun fromMagnet(magnet: String): String? {
        val raw = BTIH.find(magnet)?.groupValues?.get(1) ?: return null
        return when {
            raw.length == 40 && raw.all { it.isHexDigit() } -> raw.lowercase()
            raw.length == 32 -> base32ToHex(raw.uppercase())
            else -> null
        }
    }

    /** SHA-1 of the bencoded `info` dictionary of a .torrent file, else null. */
    fun fromTorrentFile(bytes: ByteArray): String? = runCatching {
        val (start, end) = infoSpan(bytes) ?: return null
        val digest = MessageDigest.getInstance("SHA-1").apply { update(bytes, start, end - start) }.digest()
        digest.joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /**
     * Tracker URLs of a .torrent (`announce`, then `announce-list`), deduped,
     * http/https/udp only. Never throws: a malformed file yields what was
     * read before the damage, or an empty list.
     */
    fun trackersFromTorrentFile(bytes: ByteArray): List<String> {
        val out = LinkedHashSet<String>()
        fun add(raw: String) {
            val url = String(raw.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8).trim()
            if (TRACKER_SCHEMES.any { url.startsWith(it, ignoreCase = true) }) out += url
        }
        runCatching {
            if (bytes.isEmpty() || bytes[0] != 'd'.code.toByte()) return emptyList()
            var i = 1
            while (i < bytes.size && bytes[i] != 'e'.code.toByte()) {
                val (key, afterKey) = readString(bytes, i) ?: break
                when (key) {
                    "announce" -> readString(bytes, afterKey)?.first?.let(::add)
                    "announce-list" -> readTrackerTiers(bytes, afterKey, ::add)
                }
                i = skip(bytes, afterKey) ?: break
            }
        }
        return out.toList()
    }

    /**
     * Files listed in a .torrent's info dict (single- or multi-file), without
     * BEP-47 padding. Never throws: malformed input yields an empty list.
     */
    fun filesFromTorrentFile(bytes: ByteArray): List<TorrentEntry> = runCatching {
        val (start, _) = infoSpan(bytes) ?: return emptyList()
        val info = parseValue(bytes, start, 0)?.first as? Map<*, *> ?: return emptyList()
        fun utf8(latin1: String) = String(latin1.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
        val name = ((info["name.utf-8"] ?: info["name"]) as? String)?.let(::utf8) ?: ""
        val files = info["files"] as? List<*>
        if (files == null) {
            val len = info["length"] as? Long ?: return emptyList()
            return if (name.isEmpty() || len < 0) emptyList() else listOf(TorrentEntry(name, len))
        }
        val out = ArrayList<TorrentEntry>()
        for (f in files) {
            if (out.size >= MAX_FILES) break
            val m = f as? Map<*, *> ?: continue
            val len = m["length"] as? Long ?: continue
            val parts = ((m["path.utf-8"] ?: m["path"]) as? List<*>)?.map { (it as? String)?.let(::utf8) }
            if (len < 0 || parts.isNullOrEmpty() || parts.any { it.isNullOrEmpty() }) continue
            if ((m["attr"] as? String)?.contains('p') == true || parts[0] == ".pad") continue
            out += TorrentEntry((listOfNotNull(name.ifEmpty { null }) + parts.filterNotNull()).joinToString("/"), len)
        }
        out
    }.getOrDefault(emptyList())

    private const val MAX_FILES = 10_000
    private const val MAX_DEPTH = 16

    /** Bencode value at [i] as String (latin-1), Long, List or Map, with the index after it. */
    private fun parseValue(b: ByteArray, i: Int, depth: Int): Pair<Any, Int>? {
        if (i >= b.size || depth > MAX_DEPTH) return null
        when (b[i].toInt().toChar()) {
            'i' -> {
                val e = (i + 1 until b.size).firstOrNull { b[it] == 'e'.code.toByte() } ?: return null
                val n = String(b, i + 1, e - i - 1, Charsets.ISO_8859_1).toLongOrNull() ?: return null
                return n to e + 1
            }
            'l' -> {
                val list = ArrayList<Any>()
                var j = i + 1
                while (j < b.size && b[j] != 'e'.code.toByte()) {
                    val (v, n) = parseValue(b, j, depth + 1) ?: return null
                    list += v
                    j = n
                }
                return if (j < b.size) list to j + 1 else null
            }
            'd' -> {
                val map = HashMap<String, Any>()
                var j = i + 1
                while (j < b.size && b[j] != 'e'.code.toByte()) {
                    val (k, afterKey) = readString(b, j) ?: return null
                    val (v, n) = parseValue(b, afterKey, depth + 1) ?: return null
                    map[k] = v
                    j = n
                }
                return if (j < b.size) map to j + 1 else null
            }
            else -> return readString(b, i)
        }
    }

    /** Feeds every string of a list-of-lists at [i] to [add]; stops quietly at bad data. */
    private fun readTrackerTiers(b: ByteArray, i: Int, add: (String) -> Unit) {
        if (i >= b.size || b[i] != 'l'.code.toByte()) return
        var j = i + 1
        while (j < b.size && b[j] != 'e'.code.toByte()) {
            if (b[j] == 'l'.code.toByte()) {
                var k = j + 1
                while (k < b.size && b[k] != 'e'.code.toByte()) {
                    val (s, next) = readString(b, k) ?: return
                    add(s)
                    k = next
                }
            }
            j = skip(b, j) ?: return
        }
    }

    private val TRACKER_SCHEMES = listOf("http://", "https://", "udp://")

    private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun base32ToHex(s: String): String? {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var buffer = 0L
        var bits = 0
        val out = StringBuilder()
        for (c in s) {
            val v = alphabet.indexOf(c)
            if (v < 0) return null
            buffer = (buffer shl 5) or v.toLong()
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.append("%02x".format(((buffer shr bits) and 0xFF).toInt()))
            }
        }
        return out.toString().takeIf { it.length == 40 }
    }

    // --- minimal bencode walker ------------------------------------------------

    /** Byte range [start, end) of the value stored under "info" in the top-level dict. */
    private fun infoSpan(b: ByteArray): Pair<Int, Int>? {
        if (b.isEmpty() || b[0] != 'd'.code.toByte()) return null
        var i = 1
        while (i < b.size && b[i] != 'e'.code.toByte()) {
            val (key, afterKey) = readString(b, i) ?: return null
            val valueEnd = skip(b, afterKey) ?: return null
            if (key == "info") return afterKey to valueEnd
            i = valueEnd
        }
        return null
    }

    /** Reads "<len>:<bytes>" at [i]; returns the text and the index after it. */
    private fun readString(b: ByteArray, i: Int): Pair<String, Int>? {
        var j = i
        var len = 0
        while (j < b.size && b[j] in '0'.code.toByte()..'9'.code.toByte()) {
            len = len * 10 + (b[j] - '0'.code.toByte())
            if (len < 0) return null
            j++
        }
        if (j >= b.size || b[j] != ':'.code.toByte() || j == i) return null
        val start = j + 1
        val end = start + len
        if (end > b.size) return null
        return String(b, start, len, Charsets.ISO_8859_1) to end
    }

    /** Index just past the bencoded value starting at [i]. */
    private fun skip(b: ByteArray, i: Int): Int? {
        if (i >= b.size) return null
        return when (b[i].toInt().toChar()) {
            'i' -> {
                var j = i + 1
                while (j < b.size && b[j] != 'e'.code.toByte()) j++
                if (j < b.size) j + 1 else null
            }
            'l', 'd' -> {
                var j = i + 1
                while (j < b.size && b[j] != 'e'.code.toByte()) {
                    j = skip(b, j) ?: return null
                }
                if (j < b.size) j + 1 else null
            }
            in '0'..'9' -> readString(b, i)?.second
            else -> null
        }
    }
}
