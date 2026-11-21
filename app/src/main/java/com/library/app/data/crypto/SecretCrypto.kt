package com.library.app.data.crypto

import android.app.KeyguardManager
import android.content.Context

sealed interface SecretResult {
    data class Ok(val value: String) : SecretResult
    data object Cancelled : SecretResult
    data class Failed(val message: String) : SecretResult
}

/**
 * 加解密入口。进入应用时已经验证过一次身份（锁屏页），
 * 所以保存密码、查看密码都不再二次弹验证，直接使用设备密钥。
 */
class SecretCrypto(private val context: Context) {

    private val box = CryptoBox(context)

    fun keyState(): CryptoBox.KeyState = box.ensureKey()

    fun hasKey(): Boolean = box.hasKey()

    fun isSecureLockConfigured(): Boolean {
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return keyguard.isDeviceSecure
    }

    /** 不弹验证：保存时用。 */
    fun encryptNow(plain: String): SecretResult {
        if (plain.isEmpty()) return SecretResult.Ok("")
        if (box.ensureKey() == CryptoBox.KeyState.FAILED) {
            return SecretResult.Failed("无法创建加密密钥")
        }
        return runCatching { box.encrypt(plain) }.fold(
            onSuccess = { SecretResult.Ok(it) },
            onFailure = {
                SecretResult.Failed("加密失败：${it.javaClass.simpleName} ${it.message.orEmpty()}".trim())
            },
        )
    }

    /** 不弹验证：查看时用。 */
    fun decryptNow(blob: String): SecretResult {
        if (blob.isEmpty()) return SecretResult.Ok("")
        return runCatching { box.decrypt(blob) }.fold(
            onSuccess = { SecretResult.Ok(it) },
            onFailure = { SecretResult.Failed("解密失败：${it.javaClass.simpleName}") },
        )
    }
}
