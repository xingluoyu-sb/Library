package com.library.app.data.crypto

import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * 口令 → 密钥。备份口令和锁屏密码共用同一套参数，
 * 改这里要注意老备份还能不能打开。
 */
object Kdf {

    /** 12 万轮，手机上大约 80–150ms。 */
    const val ITERATIONS = 120_000

    const val SALT_SIZE = 16

    fun derive(passphrase: String, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray {
        require(salt.isNotEmpty()) { "盐不能为空" }
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
