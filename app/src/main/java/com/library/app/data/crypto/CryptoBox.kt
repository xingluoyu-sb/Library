package com.library.app.data.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 敏感字段的加解密。密钥生成在 Android Keystore 里，不可导出、不落文件。
 *
 * 这里刻意 **没有** 设置 setUserAuthenticationRequired：
 * 一旦绑定「每次使用都要认证」，Cipher 就必须走 BiometricPrompt 的 CryptoObject 授权，
 * 而这个组合与「允许锁屏密码兜底」在多数 ROM 上并存时，认证成功也拿不到密钥授权，
 * 表现为保存/查看密码一律失败。所以认证改由 App 层（SecretCrypto）在每次加解密前把关，
 * 密钥只保证「不出 TEE、不写在文件里」。
 */
class CryptoBox(private val context: Context) {

    enum class KeyState { READY, CREATED, FAILED }

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    fun hasKey(): Boolean = runCatching { keyStore.containsAlias(ALIAS) }.getOrDefault(false)

    fun ensureKey(): KeyState {
        if (hasKey()) return KeyState.READY
        return runCatching {
            // 旧版本（绑定认证）留下的密钥直接废弃，避免 init 时抛 UserNotAuthenticated
            if (keyStore.containsAlias(LEGACY_ALIAS)) keyStore.deleteEntry(LEGACY_ALIAS)
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .setRandomizedEncryptionRequired(true)
                .build()
            generator.init(spec)
            generator.generateKey()
            KeyState.CREATED
        }.getOrDefault(KeyState.FAILED)
    }

    /** 加密，返回 `v1:` + Base64(iv || ciphertext)。 */
    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, secretKey())
            updateAAD(AAD)
        }
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.encodeToString(cipher.iv + body, Base64.NO_WRAP)
    }

    fun decrypt(blob: String): String {
        require(blob.startsWith(PREFIX)) { "密文格式不对" }
        val raw = Base64.decode(blob.removePrefix(PREFIX), Base64.NO_WRAP)
        require(raw.size > IV_SIZE) { "密文长度不足" }
        val iv = raw.copyOfRange(0, IV_SIZE)
        val body = raw.copyOfRange(IV_SIZE, raw.size)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            updateAAD(AAD)
        }
        return cipher.doFinal(body).toString(Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey = keyStore.getKey(ALIAS, null) as SecretKey

    companion object {
        const val ALIAS = "library.secret.v2"
        private const val LEGACY_ALIAS = "library.secret.v1"
        const val PREFIX = "v1:"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
        private const val TAG_BITS = 128
        private val AAD = "library-v1".toByteArray(Charsets.UTF_8)
    }
}
