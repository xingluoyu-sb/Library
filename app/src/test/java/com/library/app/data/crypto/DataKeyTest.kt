package com.library.app.data.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 钉住 [DataKey.key] 的决策规则：**有行就绝不再写**。
 *
 * 这里只测纯函数 [DataKey.resolve]，不碰 Android Keystore / SQLite ——
 * 规则本身跟运行环境无关，而「换机后静默覆盖旧 DEK」这种事故正好发生在规则写错的时候。
 *
 * [DataKey.Stored] 只有三种形态（找到 / 根本没有 / 有但解不开），和两个版本号
 * 组合成 6 个格子，下面每个格子一个用例，另外单独钉「create 最多被调用一次」。
 */
class DataKeyTest {

    private val stored = ByteArray(32) { it.toByte() }

    /** 新建出来的假密钥，和库里那条能区分开。 */
    private fun fresh(): ByteArray = ByteArray(32) { 0x7F }

    private class Outcome(val dek: ByteArray?, val createCalls: Int, val error: DataKey.DekUnavailable?)

    /** 跑一次 [DataKey.resolve]，把「返回值 / create 调用次数 / 抛了什么」一次收齐。 */
    private fun run(version: Int, state: DataKey.Stored): Outcome {
        var calls = 0
        val dek = try {
            DataKey.resolve(version, state) {
                calls++
                fresh()
            }
        } catch (e: DataKey.DekUnavailable) {
            return Outcome(null, calls, e)
        }
        return Outcome(dek, calls, null)
    }

    // ---- 找到可用密钥：两个版本都直接用，绝不新建 ----

    @Test
    fun foundKeyIsUsedOnLegacyLibrary() {
        val out = run(DataKey.VERSION_LEGACY, DataKey.Stored.Found(stored))
        assertArrayEquals(stored, out.dek)
        assertEquals("读到密钥就不该新建", 0, out.createCalls)
        assertEquals(null, out.error)
    }

    @Test
    fun foundKeyIsUsedOnDekLibrary() {
        val out = run(DataKey.VERSION_DEK, DataKey.Stored.Found(stored))
        assertArrayEquals(stored, out.dek)
        assertEquals("读到密钥就不该新建", 0, out.createCalls)
        assertEquals(null, out.error)
    }

    // ---- 表里没有这一行：只有版本 1 允许新建 ----

    @Test
    fun absentRowOnLegacyLibraryCreates() {
        val out = run(DataKey.VERSION_LEGACY, DataKey.Stored.Absent)
        assertArrayEquals(fresh(), out.dek)
        assertEquals("该新建且只该新建一次", 1, out.createCalls)
        assertEquals(null, out.error)
    }

    @Test
    fun absentRowOnDekLibraryRefusesToCreate() {
        val out = run(DataKey.VERSION_DEK, DataKey.Stored.Absent)
        assertEquals("版本是 2 就没有「补一个」的余地", 0, out.createCalls)
        assertEquals(null, out.dek)
        assertTrue("错误信息要指向备份", out.error!!.message!!.contains("备份"))
    }

    // ---- 有行但解不开：两个版本都不许覆盖 ----

    @Test
    fun unreadableRowOnLegacyLibraryIsKept() {
        val out = run(DataKey.VERSION_LEGACY, DataKey.Stored.Unreadable)
        assertEquals("那一行留着，不许删了换新的", 0, out.createCalls)
        assertEquals(null, out.dek)
        assertTrue(out.error != null)
    }

    @Test
    fun unreadableRowOnDekLibraryIsKept() {
        val out = run(DataKey.VERSION_DEK, DataKey.Stored.Unreadable)
        assertEquals("解不开也不能覆盖", 0, out.createCalls)
        assertEquals(null, out.dek)
        assertTrue("错误信息要指向备份", out.error!!.message!!.contains("备份"))
    }

    /** 单独钉一次：允许新建的那条路上，create 也只能被调用一次。 */
    @Test
    fun createIsCalledAtMostOnce() {
        val out = run(DataKey.VERSION_LEGACY, DataKey.Stored.Absent)
        assertEquals(1, out.createCalls)
        assertEquals(DataKey.DEK_SIZE, out.dek!!.size)
    }

    // ---- 恢复备份时写回的版本号 ----

    /**
     * 源库版本 1（迁移没跑完时导出的备份）恢复后仍要是 1，否则：
     * 读取路径要求 `v2:` 前缀 → 全部报「密文是旧格式」，迁移入口又因「已是 2」直接返回。
     */
    @Test
    fun adoptedVersionFollowsSource() {
        assertEquals(DataKey.VERSION_LEGACY, DataKey.adoptedVersion(DataKey.VERSION_LEGACY))
        assertEquals(DataKey.VERSION_DEK, DataKey.adoptedVersion(DataKey.VERSION_DEK))
    }

    @Test
    fun adoptedVersionRejectsUnknownVersion() {
        try {
            DataKey.adoptedVersion(7)
            fail("不认识的版本号也放过去了")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("7"))
        }
    }
}
