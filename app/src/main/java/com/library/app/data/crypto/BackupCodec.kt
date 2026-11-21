package com.library.app.data.crypto

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 加密备份容器（后缀 `.libbak`），格式：
 *
 * ```
 * | "LIBK1" | ver=1 | iter(4) | salt(16) | iv(12) | AES-GCM 密文 | tag |
 * ```
 *
 * - 密钥 = PBKDF2-HMAC-SHA256(导出时输入的口令, salt, iter)，**和锁屏口令完全分开**：
 *   这份文件发给别人、传网盘，暴露的只是备份口令，不等于解锁口令；
 * - iter 写进头部，将来提高轮数时老文件仍然打得开；
 * - 明文内容 = `4 字节 DEK 长度 | DEK | sqlite 文件字节`。
 *
 * 为什么 DEK 要塞进容器：这个应用允许「手机凭证」模式（不设应用口令），
 * 那种情况下库里没有能用口令派生的 KEK，DEK 只能在这里用备份口令包一层带走，
 * 否则该模式的备份换机必然解不开。DEK 本身在加密载荷内，不是明文暴露。
 *
 * 全流式读写，不把整库读进内存。
 */
object BackupCodec {

    private val MAGIC = "LIBK1".toByteArray(Charsets.US_ASCII)
    private const val FORMAT_VERSION = 1
    private const val HEADER_FIXED = 5 + 1 + 4
    private val AAD = "backup-v1".toByteArray(Charsets.UTF_8)

    class WrongPassphrase : Exception("口令不对")
    class NotBackupFile : Exception("不是备份文件")
    class Damaged : Exception("备份文件损坏")

    fun looksLikeBackup(head: ByteArray): Boolean {
        if (head.size < MAGIC.size) return false
        for (i in MAGIC.indices) if (head[i] != MAGIC[i]) return false
        return true
    }

    /** [dbFile] 的字节全部加密后写进 [out]，不生成中间明文文件。 */
    fun encodeTo(out: OutputStream, dbFile: File, dek: ByteArray, passphrase: String) {
        require(dek.isNotEmpty()) { "数据密钥是空的" }
        val salt = AesGcm.randomBytes(Kdf.SALT_SIZE)
        val iterations = Kdf.ITERATIONS
        val key = Kdf.derive(passphrase, salt, iterations)
        val iv = AesGcm.randomBytes(AesGcm.IV_SIZE)

        out.write(MAGIC)
        out.write(FORMAT_VERSION)
        out.write(intToBytes(iterations))
        out.write(salt)
        out.write(iv)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            updateAAD(AAD)
        }
        CipherOutputStream(out, cipher).use { encrypted ->
            encrypted.write(intToBytes(dek.size))
            encrypted.write(dek)
            dbFile.inputStream().use { it.copyTo(encrypted, 64 * 1024) }
            encrypted.flush()
        }
        out.flush()
    }

    /**
     * 解密到 [outFile]，返回容器里带出来的 DEK。
     * 口令错、文件被改、被截断，一律抛 [WrongPassphrase]（区分不出是哪种，也不该区分）。
     */
    fun decodeTo(input: InputStream, outFile: File, passphrase: String): ByteArray {
        val fixed = readFully(input, HEADER_FIXED)
        if (!looksLikeBackup(fixed)) throw NotBackupFile()
        val format = fixed[5].toInt()
        if (format != FORMAT_VERSION) throw Damaged()

        val iterations = bytesToInt(fixed, 6)
        if (iterations !in 1..2_000_000) throw Damaged()
        val salt = readFully(input, Kdf.SALT_SIZE)
        val iv = readFully(input, AesGcm.IV_SIZE)
        val key = Kdf.derive(passphrase, salt, iterations)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            updateAAD(AAD)
        }

        var dek: ByteArray
        try {
            CipherInputStream(input, cipher).use { decrypted ->
                val length = bytesToInt(readFully(decrypted, 4), 0)
                if (length !in 16..64) throw Damaged()
                dek = readFully(decrypted, length)
                FileOutputStream(outFile).use { target ->
                    decrypted.copyTo(target, 64 * 1024)
                    target.flush()
                    target.fd.sync()
                }
            }
        } catch (e: WrongPassphrase) {
            outFile.delete()
            throw e
        } catch (e: Damaged) {
            outFile.delete()
            throw e
        } catch (e: AEADBadTagException) {
            outFile.delete()
            throw WrongPassphrase()
        } catch (e: Exception) {
            // 流式 GCM 是在流末尾才校验 tag 的，口令错时可能已经写出半截乱码，
            // 这里必须把落盘的明文删掉再抛
            outFile.delete()
            throw WrongPassphrase()
        }
        return dek
    }

    private fun intToBytes(value: Int) = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun bytesToInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun readFully(input: InputStream, size: Int): ByteArray {
        val buffer = ByteArray(size)
        var read = 0
        while (read < size) {
            val n = input.read(buffer, read, size - read)
            if (n <= 0) throw Damaged()
            read += n
        }
        return buffer
    }
}
