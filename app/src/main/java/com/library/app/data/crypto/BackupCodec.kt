package com.library.app.data.crypto

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
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

    private const val MIN_DEK_SIZE = 16
    private const val MAX_DEK_SIZE = 64

    /** GCM 的 tag 长度，密文总是比明文多这么多。 */
    private const val TAG_BYTES = 16

    private const val BUFFER = 64 * 1024

    /**
     * 允许写在头部的 PBKDF2 轮数区间。
     * 下限挡住「iter=1 的弱口令备份」，上限挡住伪造一个大数让我们空转。
     */
    private const val MIN_ITERATIONS = 10_000
    private const val MAX_ITERATIONS = 1_000_000

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
        // 加密方向的 CipherOutputStream 没有「吞掉 tag」的问题：doFinal 在 close 里抛得出来
        CipherOutputStream(out, cipher).use { encrypted ->
            encrypted.write(intToBytes(dek.size))
            encrypted.write(dek)
            dbFile.inputStream().use { it.copyTo(encrypted, BUFFER) }
            encrypted.flush()
        }
        out.flush()
    }

    /**
     * 解密到 [outFile]，返回容器里带出来的 DEK。
     * 口令错、文件被改、被截断，一律抛 [WrongPassphrase] 或 [Damaged]（区分不出是哪种，也不该区分）。
     *
     * 刻意**不用** `CipherInputStream`：JDK 里它在 doFinal 抛 `AEADBadTagException` 时
     * 有的版本会静默返回 -1 而不是把异常抛出来，于是错误口令被当成「解完了」，
     * 落盘一个截断的明文库，`catch` 也救不到（根本没有异常）。Conscrypt 在真机上会抛，
     * 所以这是个「测试机通过、真机不安全」的坑。
     * 这里自己驱动 `update` / `doFinal`，并核对明文长度与密文长度，两条腿一起站。
     */
    fun decodeTo(input: InputStream, outFile: File, passphrase: String): ByteArray {
        val fixed = readFully(input, HEADER_FIXED)
        if (!looksLikeBackup(fixed)) throw NotBackupFile()
        val format = fixed[5].toInt()
        if (format != FORMAT_VERSION) throw Damaged()

        val iterations = bytesToInt(fixed, 6)
        if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) throw Damaged()
        val salt = readFully(input, Kdf.SALT_SIZE)
        val iv = readFully(input, AesGcm.IV_SIZE)
        val key = Kdf.derive(passphrase, salt, iterations)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            updateAAD(AAD)
        }

        var dek: ByteArray? = null
        var completed = false
        try {
            FileOutputStream(outFile).use { target ->
                val sink = PlainSink(target)
                val buffer = ByteArray(BUFFER)
                var cipherBytes = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n == 0) continue
                    cipherBytes += n
                    val chunk = cipher.update(buffer, 0, n)
                    if (chunk != null && chunk.isNotEmpty()) sink.write(chunk, 0, chunk.size)
                }
                // tag 在这里校验，抛 AEADBadTagException；抛不出来就说明实现有问题，
                // 所以下面还要再用长度对一次账
                val tail = cipher.doFinal()
                if (tail != null && tail.isNotEmpty()) sink.write(tail, 0, tail.size)

                val plain = sink.finish()
                // 明文长度必须正好是「密文 - tag」；对不上就是流被截断或中间丢过数据
                if (plain.size != cipherBytes - TAG_BYTES) throw Damaged()
                dek = sink.dek
                target.flush()
                target.fd.sync()
                completed = true
            }
        } catch (e: WrongPassphrase) {
            throw e
        } catch (e: Damaged) {
            throw e
        } catch (e: AEADBadTagException) {
            throw WrongPassphrase()
        } catch (e: IOException) {
            // 读流被截断 / 写盘失败：文件本身不完整，不是口令问题
            throw Damaged()
        } catch (e: GeneralSecurityException) {
            // 只管密码学异常。部分实现把 tag 失败包成普通 BadPaddingException，
            // 这里一并当「口令不对」；别的运行时异常没资格冒充解错口令，让它原样冒出去
            throw WrongPassphrase()
        } finally {
            // 流式 GCM 在末尾才校验，失败前可能已经写出半截明文。
            // 用 finally 而不是每个 catch 里 delete：任何异常类型（包括没列出来的）都不许把明文留在盘上
            if (!completed) outFile.delete()
        }
        return dek ?: throw Damaged()
    }

    /**
     * 把解密出来的明文按「4 字节 DEK 长度 | DEK | 库字节」拆开：
     * 头部攒在内存里，其余全部直接落盘。
     *
     * 返回的 [dek] 只有走完 [finish] 才非空；[finish] 顺便确认头部是完整的。
     */
    private class PlainSink(private val target: OutputStream) {

        private val head = ByteArray(4 + MAX_DEK_SIZE)
        private var headLen = 0
        private var need = -1
        private var total = 0L

        var dek: ByteArray? = null
            private set

        fun write(buf: ByteArray, off: Int, len: Int) {
            total += len
            var o = off
            var n = len
            while (dek == null && n > 0) {
                if (need < 0) {
                    // 还不知道 DEK 多长，只先凑够 4 字节长度
                    val take = minOf(4 - headLen, n)
                    System.arraycopy(buf, o, head, headLen, take)
                    headLen += take
                    o += take
                    n -= take
                    if (headLen == 4) {
                        val size = bytesToInt(head, 0)
                        if (size !in MIN_DEK_SIZE..MAX_DEK_SIZE) throw Damaged()
                        need = 4 + size
                    }
                } else {
                    val take = minOf(need - headLen, n)
                    System.arraycopy(buf, o, head, headLen, take)
                    headLen += take
                    o += take
                    n -= take
                    if (headLen == need) dek = head.copyOfRange(4, need)
                }
            }
            if (n > 0) target.write(buf, o, n)
        }

        fun finish(): PlainSink {
            if (dek == null) throw Damaged()
            return this
        }

        val size: Long get() = total
    }
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
        if (n <= 0) throw BackupCodec.Damaged()
        read += n
    }
    return buffer
}
