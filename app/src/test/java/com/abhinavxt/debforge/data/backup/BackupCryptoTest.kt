package com.abhinavxt.debforge.data.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BackupCryptoTest {

    // Few iterations keep the test fast; the format is identical.
    private val n = 1_000

    @Test fun roundTrip() {
        val plain = """{"tokens":{"TORBOX":{"token":"abc"}}}""".toByteArray()
        val sealed = BackupCrypto.seal(plain, "correct horse".toCharArray(), n)
        assertArrayEquals(plain, BackupCrypto.open(sealed, "correct horse".toCharArray()))
    }

    @Test(expected = BackupCrypto.WrongPasswordException::class)
    fun wrongPassword() {
        val sealed = BackupCrypto.seal("secret".toByteArray(), "right".toCharArray(), n)
        BackupCrypto.open(sealed, "wrong".toCharArray())
    }

    @Test(expected = BackupCrypto.WrongPasswordException::class)
    fun tamperedData() {
        val sealed = BackupCrypto.seal("secret".toByteArray(), "pw".toCharArray(), n)
        val bytes = java.util.Base64.getDecoder().decode(sealed.data)
        bytes[0] = (bytes[0].toInt() xor 1).toByte()
        BackupCrypto.open(sealed.copy(data = java.util.Base64.getEncoder().encodeToString(bytes)), "pw".toCharArray())
    }

    @Test fun freshSaltAndIvEachTime() {
        val a = BackupCrypto.seal("x".toByteArray(), "pw".toCharArray(), n)
        val b = BackupCrypto.seal("x".toByteArray(), "pw".toCharArray(), n)
        assertNotEquals(a.salt, b.salt)
        assertNotEquals(a.iv, b.iv)
    }
}
