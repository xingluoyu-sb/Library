package com.library.app.data.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class KdfTest {

    private val salt = ByteArray(16) { it.toByte() }

    @Test
    fun sameInputsGiveSameKey() {
        val a = Kdf.derive("passphrase-123", salt)
        val b = Kdf.derive("passphrase-123", salt)
        assertArrayEquals(a, b)
        assertEquals(32, a.size)
    }

    @Test
    fun differentSaltGivesDifferentKey() {
        val other = ByteArray(16) { (it + 1).toByte() }
        assertFalse(Kdf.derive("passphrase-123", salt).contentEquals(Kdf.derive("passphrase-123", other)))
    }

    @Test
    fun differentPassphraseGivesDifferentKey() {
        assertFalse(
            Kdf.derive("passphrase-123", salt).contentEquals(Kdf.derive("passphrase-124", salt)),
        )
    }

    /** 轮数必须真的生效，否则「提高轮数」只是心理安慰。 */
    @Test
    fun iterationCountChangesResult() {
        val low = Kdf.derive("passphrase-123", salt, iterations = 1_000)
        val high = Kdf.derive("passphrase-123", salt, iterations = 2_000)
        assertFalse(low.contentEquals(high))
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptySaltRejected() {
        Kdf.derive("passphrase-123", ByteArray(0))
    }
}
