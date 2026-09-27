package com.abhinavxt.debforge.data.backup

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password encryption for the optional "sign-ins" part of a backup.
 * PBKDF2-HMAC-SHA256 (salted, [ITERATIONS] rounds) derives an AES-256 key;
 * AES-GCM encrypts and authenticates, so a wrong password or a tampered file
 * fails cleanly instead of producing garbage.
 */
object BackupCrypto {

    const val KDF = "PBKDF2WithHmacSHA256"
    const val ITERATIONS = 310_000
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128

    data class Sealed(val salt: String, val iv: String, val iterations: Int, val data: String)

    class WrongPasswordException : Exception("Wrong password, or the backup file is damaged")

    fun seal(plain: ByteArray, password: CharArray, iterations: Int = ITERATIONS): Sealed {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also(rnd::nextBytes)
        val iv = ByteArray(12).also(rnd::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt, iterations), GCMParameterSpec(TAG_BITS, iv))
        val b64 = Base64.getEncoder()
        return Sealed(b64.encodeToString(salt), b64.encodeToString(iv), iterations, b64.encodeToString(cipher.doFinal(plain)))
    }

    fun open(sealed: Sealed, password: CharArray): ByteArray {
        val b64 = Base64.getDecoder()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(password, b64.decode(sealed.salt), sealed.iterations),
            GCMParameterSpec(TAG_BITS, b64.decode(sealed.iv))
        )
        return try {
            cipher.doFinal(b64.decode(sealed.data))
        } catch (e: AEADBadTagException) {
            throw WrongPasswordException()
        }
    }

    private fun key(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        try {
            val bytes = SecretKeyFactory.getInstance(KDF).generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
