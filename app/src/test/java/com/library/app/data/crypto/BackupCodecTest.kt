package com.library.app.data.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class BackupCodecTest {

    private val passphrase = "correct horse battery"
    private val dek = ByteArray(32) { (it * 7 + 3).toByte() }

    private fun tempDir(): File = File(System.getProperty("java.io.tmpdir"), "libbak-test-${System.nanoTime()}")
        .also { it.mkdirs() }

    private fun fakeDatabase(bytes: Int = 64 * 1024): ByteArray =
        ByteArray(bytes) { i -> ((i * 31) % 251).toByte() }

    private fun export(dir: File, db: ByteArray, pass: String = passphrase): File {
        val source = File(dir, "library.db").apply { writeBytes(db) }
        val container = File(dir, "backup.libbak")
        FileOutputStream(container).use { BackupCodec.encodeTo(it, source, dek, pass) }
        return container
    }

    /** d. 导出 → 正确口令恢复：数据一字不差，DEK 也回来了。 */
    @Test
    fun roundTripRestoresExactly() {
        val dir = tempDir()
        val db = fakeDatabase()
        val container = export(dir, db)

        val out = File(dir, "restored.db")
        val recoveredDek = FileInputStream(container).use { BackupCodec.decodeTo(it, out, passphrase) }

        assertArrayEquals(db, out.readBytes())
        assertArrayEquals(dek, recoveredDek)
        // 容器里不能出现明文
        val raw = container.readBytes()
        assertFalse(raw.containsSubsequence(db.copyOfRange(0, 64)))
        dir.deleteRecursively()
    }

    /** d. 错误口令必须失败，且不留下任何解密出来的文件。 */
    @Test
    fun wrongPassphraseFailsAndLeavesNoPlaintext() {
        val dir = tempDir()
        val container = export(dir, fakeDatabase())
        val out = File(dir, "restored.db")

        try {
            FileInputStream(container).use { BackupCodec.decodeTo(it, out, "wrong passphrase") }
            fail("错误口令居然解开了")
        } catch (e: BackupCodec.WrongPassphrase) {
            assertFalse("失败后不能留下明文库", out.exists())
        }
        dir.deleteRecursively()
    }

    @Test
    fun corruptedHeaderIsRejected() {
        val dir = tempDir()
        val container = export(dir, fakeDatabase())
        val bytes = container.readBytes()
        bytes[20] = (bytes[20].toInt() xor 0xFF).toByte()
        File(container.path).writeBytes(bytes)

        try {
            FileInputStream(container).use { BackupCodec.decodeTo(it, File(dir, "x.db"), passphrase) }
            fail("头部被改过却解开了")
        } catch (e: Exception) {
            assertTrue(e is BackupCodec.WrongPassphrase || e is BackupCodec.Damaged)
        }
        dir.deleteRecursively()
    }

    @Test
    fun nonBackupFileIsRejectedByMagic() {
        val dir = tempDir()
        val fake = File(dir, "random.bin").apply { writeBytes(ByteArray(4096) { 9 }) }
        try {
            FileInputStream(fake).use { BackupCodec.decodeTo(it, File(dir, "x.db"), passphrase) }
            fail("随便一个文件也被当成备份了")
        } catch (e: BackupCodec.NotBackupFile) {
            assertTrue(true)
        }
        dir.deleteRecursively()
    }

    @Test
    fun truncatedFileIsRejected() {
        val dir = tempDir()
        val container = export(dir, fakeDatabase())
        val bytes = container.readBytes()
        container.writeBytes(bytes.copyOfRange(0, bytes.size - 1024))

        try {
            FileInputStream(container).use { BackupCodec.decodeTo(it, File(dir, "x.db"), passphrase) }
            fail("被截断的备份居然解开了")
        } catch (e: Exception) {
            assertTrue(e is BackupCodec.WrongPassphrase || e is BackupCodec.Damaged)
        }
        dir.deleteRecursively()
    }

    @Test
    fun iterationCountIsStoredInHeader() {
        val dir = tempDir()
        val container = export(dir, fakeDatabase())
        val bytes = container.readBytes()
        val iter = ((bytes[6].toInt() and 0xFF) shl 24) or
            ((bytes[7].toInt() and 0xFF) shl 16) or
            ((bytes[8].toInt() and 0xFF) shl 8) or
            (bytes[9].toInt() and 0xFF)
        assertEquals(Kdf.ITERATIONS, iter)
        dir.deleteRecursively()
    }

    private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }
}
