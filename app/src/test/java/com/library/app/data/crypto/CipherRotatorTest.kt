package com.library.app.data.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 迁移的核心契约：整批成功，或者一条都不改。 */
class CipherRotatorTest {

    /** 假的旧格式：base64("v1:" + 明文)；假的新格式：base64("v2:" + 明文)。 */
    private fun fakeDecrypt(legacy: String): String {
        val decoded = String(java.util.Base64.getDecoder().decode(legacy))
        require(decoded.startsWith("v1:")) { "不是旧格式" }
        return decoded.removePrefix("v1:")
    }

    private fun fakeEncrypt(plain: String): String =
        java.util.Base64.getEncoder().encodeToString(("v2:$plain").toByteArray())

    private fun legacyOf(plain: String): String =
        java.util.Base64.getEncoder().encodeToString(("v1:$plain").toByteArray())

    private fun plainOfNew(ct: String): String {
        val decoded = String(java.util.Base64.getDecoder().decode(ct))
        require(decoded.startsWith("v2:")) { "不是新格式" }
        return decoded.removePrefix("v2:")
    }

    /** a. 迁移测试：每一行都能还原出原始明文，条数一致。 */
    @Test
    fun rewritesEveryRowAndKeepsPlaintext() {
        val originals = (1..200).map { "密码-$it-abcXYZ" }
        val rows = originals.mapIndexed { i, plain -> CipherRotator.Row(i + 1L, legacyOf(plain)) }

        val rewritten = CipherRotator.rewrite(rows, ::fakeDecrypt, ::fakeEncrypt)

        assertEquals(rows.size, rewritten.size)
        rewritten.forEachIndexed { i, (id, ct) ->
            assertEquals(rows[i].id, id)
            assertEquals(originals[i], plainOfNew(ct))
        }
    }

    /** b. 中断测试：中途有一行坏了 → 抛异常，调用方拿不到任何「已改好」的结果。 */
    @Test
    fun failsWholeBatchWhenOneRowIsBroken() {
        val rows = listOf(
            CipherRotator.Row(1, legacyOf("ok-1")),
            CipherRotator.Row(2, legacyOf("ok-2")),
            CipherRotator.Row(3, "这不是密文"),
            CipherRotator.Row(4, legacyOf("ok-4")),
        )

        var result: List<Pair<Long, String>>? = null
        val error = runCatching { result = CipherRotator.rewrite(rows, ::fakeDecrypt, ::fakeEncrypt) }
            .exceptionOrNull()

        assertTrue(error is CipherRotator.Failed)
        assertEquals(3L, (error as CipherRotator.Failed).entryId)
        assertTrue("失败时不应该返回部分结果", result == null)
    }

    @Test
    fun emptyListIsFine() {
        assertTrue(CipherRotator.rewrite(emptyList(), ::fakeDecrypt, ::fakeEncrypt).isEmpty())
    }

    /** 密文里绝不能出现明文，这是最基本的一条。 */
    @Test
    fun ciphertextDoesNotContainPlaintext() {
        val plain = "super-secret-token"
        val ct = fakeEncrypt(plain)
        assertFalse(ct.contains(plain))
        assertArrayEquals(plain.toByteArray(), plainOfNew(ct).toByteArray())
    }
}
