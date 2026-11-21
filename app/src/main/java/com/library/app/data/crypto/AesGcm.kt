package com.library.app.data.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 纯粹的 AES-256-GCM，密钥从外面传进来。
 *
 * 跟 CryptoBox 的区别：CryptoBox 的密钥在 Android Keystore 里、拿不出来；
 * 这里的密钥是一段普通字节，可以随备份搬走，所以用它来加密「要跟着数据走」的东西。
 *
 * 输出统一是 iv(12 字节) || 密文（含 16 字节 tag），调用方自己决定怎么编码。
 */
object AesGcm {

    const val IV_SIZE = 12
    private const val TAG_BITS = 128

    private val random = SecureRandom()

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    fun encrypt(key: ByteArray, plain: ByteArray, aad: ByteArray? = null): ByteArray {
        val iv = randomBytes(IV_SIZE)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
            if (aad != null) updateAAD(aad)
        }
        return iv + cipher.doFinal(plain)
    }

    /** 口令错、密文被改过，都会抛 AEADBadTagException。 */
    fun decrypt(key: ByteArray, blob: ByteArray, aad: ByteArray? = null): ByteArray {
        require(blob.size > IV_SIZE) { "密文太短" }
        val iv = blob.copyOfRange(0, IV_SIZE)
        val body = blob.copyOfRange(IV_SIZE, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
            if (aad != null) updateAAD(aad)
        }
        return cipher.doFinal(body)
    }
}
