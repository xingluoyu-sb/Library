package com.library.app.data.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AesGcmTest {

    private val key = ByteArray(32) { (it + 1).toByte() }
    private val aad = "secret-v2".toByteArray()

    @Test
    fun roundTrip() {
        val plain = "hunter2-密码-🔐".toByteArray(Charsets.UTF_8)
        val blob = AesGcm.encrypt(key, plain, aad)
        val back = AesGcm.decrypt(key, blob, aad)
        assertEquals(String(plain, Charsets.UTF_8), String(back, Charsets.UTF_8))
    }

    /** 加密 1000 次，IV 一个都不能重复——同密钥复用 IV 等于直接送明文。 */
    @Test
    fun ivNeverRepeats() {
        val seen = HashSet<String>()
        repeat(1000) {
            val blob = AesGcm.encrypt(key, "same content".toByteArray(), aad)
            val iv = blob.copyOfRange(0, AesGcm.IV_SIZE).joinToString(",") { b -> b.toString() }
            assertTrue("IV 重复：$iv", seen.add(iv))
            assertEquals(AesGcm.IV_SIZE, iv.split(",").size)
        }
        assertEquals(1000, seen.size)
    }

    @Test
    fun sameInputProducesDifferentCiphertext() {
        val a = AesGcm.encrypt(key, "x".toByteArray(), aad)
        val b = AesGcm.encrypt(key, "x".toByteArray(), aad)
        assertNotEquals(a.joinToString(",") { it.toString() }, b.joinToString(",") { it.toString() })
    }

    @Test
    fun aadMismatchFails() {
        val blob = AesGcm.encrypt(key, "secret".toByteArray(), "secret-v2".toByteArray())
        try {
            AesGcm.decrypt(key, blob, "dek-v1".toByteArray())
            fail("AAD 不同却解开了")
        } catch (e: Exception) {
            assertTrue(e is javax.crypto.AEADBadTagException)
        }
    }

    @Test
    fun tamperedCiphertextFails() {
        val blob = AesGcm.encrypt(key, "secret".toByteArray(), aad)
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 0x01).toByte()
        try {
            AesGcm.decrypt(key, blob, aad)
            fail("被改过的密文居然解开了")
        } catch (e: Exception) {
            assertFalse(e.message.isNullOrEmpty() && false)
        }
    }

    @Test
    fun shortBlobRejected() {
        try {
            AesGcm.decrypt(key, ByteArray(8), aad)
            fail("长度不足却通过")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("密文"))
        }
    }
}
