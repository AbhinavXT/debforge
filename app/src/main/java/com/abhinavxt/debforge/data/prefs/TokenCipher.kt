package com.abhinavxt.debforge.data.prefs

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts API tokens at rest with an AES-256-GCM key that lives in the
 * Android Keystore (hardware-backed where the device supports it, and never
 * exportable). What DataStore holds is "v1:" + base64(iv || ciphertext+tag).
 *
 *  - Values without the "v1:" prefix are legacy plaintext from older
 *    versions; [decrypt] returns them as-is and TokenStore re-encrypts them.
 *  - If the Keystore key is gone (e.g. data restored onto another device)
 *    decryption fails and the token reads as absent — the user simply signs
 *    in again rather than the app crashing.
 */
object TokenCipher {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "debforge_tokens_v1"
    private const val PREFIX = "v1:"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    @Volatile private var cachedKey: SecretKey? = null

    /**
     * JVM tests only (Robolectric has no AndroidKeyStore): a software key used
     * instead of the Keystore. Never set in the app.
     */
    @androidx.annotation.VisibleForTesting
    @Volatile var testKey: SecretKey? = null

    /**
     * Decrypted values keyed by ciphertext. Tokens are read on every API
     * request (interceptors); Keystore operations cost a few ms each, so
     * this keeps requests fast without keeping anything on disk.
     */
    private val plainCache = ConcurrentHashMap<String, String>()

    fun isEncrypted(stored: String): Boolean = stored.startsWith(PREFIX)

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val out = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val stored = PREFIX + Base64.encodeToString(out, Base64.NO_WRAP)
        plainCache[stored] = plain
        return stored
    }

    /** Plaintext, or null if [stored] can't be decrypted with this device's key. */
    fun decrypt(stored: String): String? {
        if (!isEncrypted(stored)) return stored // legacy plaintext
        plainCache[stored]?.let { return it }
        return try {
            val bytes = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            val plain = String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
            plainCache[stored] = plain
            plain
        } catch (e: Exception) {
            null
        }
    }

    private fun key(): SecretKey {
        testKey?.let { return it }
        cachedKey?.let { return it }
        synchronized(this) {
            cachedKey?.let { return it }
            val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            val existing = (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
            val key = existing ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
                init(
                    KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
                generateKey()
            }
            cachedKey = key
            return key
        }
    }
}
