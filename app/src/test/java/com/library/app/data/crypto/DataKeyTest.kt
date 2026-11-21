package com.library.app.data.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * 钉住 [DataKey.key] 的决策规则：**有行就绝不再写**。
 *
 * 这里只测纯函数 [DataKey.resolve]，不碰 Android Keystore —— 规则本身跟运行环境无关，
 * 而「换机后静默覆盖旧 DEK」这种事故正好发生在规则写错的时候。
 */
class DataKeyTest {

    private val dek = ByteArray(32) { it.toByte() }

    private fun created(): ByteArray = ByteArray(32) { 0x7F }

    @Test
    fun readableKeyIsUsedAsIs() {
        var called = false
        val got = DataKey.resolve(DataKey.VERSION_DEK, DataKey.Stored.Found(dek)) {
            called = true
            created()
        }
        assertArrayEquals(dek, got)
        assertEquals("有可用的密钥就不该再新建", false, called)
    }

    @Test
    fun absentRowInLegacyLibraryMayCreate() {
        val got = DataKey.resolve(DataKey.VERSION_LEGACY, DataKey.Stored.Absent) { created() }
        assertArrayEquals(created(), got)
    }

    @Test
    fun absentRowAfterMigrationRefusesToCreate() {
        var called = false
        try {
            DataKey.resolve(DataKey.VERSION_DEK, DataKey.Stored.Absent) {
                called = true
                created()
            }
            fail("版本已经是 2、又没有密钥，居然还新建了一个")
        } catch (e: DataKey.DekUnavailable) {
            assertEquals("不能凭空造密钥", false, called)
            assertEquals(true, e.message!!.contains("备份"))
        }
    }

    /** 最要命的一条：有行但解不开（换机 / Keystore 作废），绝不能覆盖。 */
    @Test
    fun unreadableRowIsNeverOverwritten() {
        var called = false
        try {
            DataKey.resolve(DataKey.VERSION_DEK, DataKey.Stored.Unreadable) {
                called = true
                created()
            }
            fail("解不开旧 DEK 时居然新建覆盖了")
        } catch (e: DataKey.DekUnavailable) {
            assertEquals(false, called)
            assertEquals(true, e.message!!.contains("备份"))
        }
    }

    /** 版本 1 也一样：那一行留着，不许删了换新的。 */
    @Test
    fun unreadableRowIsKeptEvenBeforeMigration() {
        var called = false
        try {
            DataKey.resolve(DataKey.VERSION_LEGACY, DataKey.Stored.Unreadable) {
                called = true
                created()
            }
            fail("版本 1 也把旧行覆盖了")
        } catch (e: DataKey.DekUnavailable) {
            assertEquals(false, called)
        }
    }
}
