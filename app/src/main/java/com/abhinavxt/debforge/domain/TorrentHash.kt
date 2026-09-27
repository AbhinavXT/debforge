package com.abhinavxt.debforge.domain

import java.security.MessageDigest

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
