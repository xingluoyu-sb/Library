package com.library.app.data

import android.content.Context
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * 进入应用的方式：自己在应用里设的密码，或者手机本身的锁屏凭证（指纹 / 锁屏密码）。
 *
 * 应用密码只存 PBKDF2 派生值，不存明文；忘记密码只能重来（数据还在，加密密钥在 Keystore 里）。
 */
class AppLock(context: Context) {

    enum class Mode(val storage: String) {
        NONE("none"),
        PASSWORD("password"),
        DEVICE("device"),
    }

    private val prefs = context.applicationContext
        .getSharedPreferences("library.lock", Context.MODE_PRIVATE)

    var mode: Mode by mutableStateOf(readMode())
        private set

    /** 新手引导走完没有。没走完就先引导，不弹锁屏。 */
    var onboarded: Boolean by mutableStateOf(prefs.getBoolean(KEY_ONBOARDED, false))
        private set

    var hasPassword: Boolean by mutableStateOf(prefs.getString(KEY_HASH, null) != null)
        private set

    fun finishOnboarding(mode: Mode) {
        this.mode = mode
        onboarded = true
        prefs.edit()
            .putString(KEY_MODE, mode.storage)
            .putBoolean(KEY_ONBOARDED, true)
            .apply()
    }

    fun setPassword(plain: String) {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val hash = derive(plain, salt, ITERATIONS)
        prefs.edit()
            .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
            .putInt(KEY_ITERATIONS, ITERATIONS)
            .apply()
        hasPassword = true
    }

    fun verifyPassword(plain: String): Boolean {
        val saltText = prefs.getString(KEY_SALT, null) ?: return false
        val hashText = prefs.getString(KEY_HASH, null) ?: return false
        val iterations = prefs.getInt(KEY_ITERATIONS, ITERATIONS)
        val salt = runCatching { Base64.decode(saltText, Base64.NO_WRAP) }.getOrNull() ?: return false
        val expected = runCatching { Base64.decode(hashText, Base64.NO_WRAP) }.getOrNull() ?: return false
        val actual = derive(plain, salt, iterations)
        return actual.size == expected.size && actual.indices.all { actual[it] == expected[it] }
    }

    /** 改成手机凭证：应用密码留着不用，但也不再拦门。 */
    fun useDeviceCredential() {
        mode = Mode.DEVICE
        prefs.edit().putString(KEY_MODE, Mode.DEVICE.storage).apply()
    }

    fun useAppPassword(plain: String) {
        setPassword(plain)
        mode = Mode.PASSWORD
        prefs.edit().putString(KEY_MODE, Mode.PASSWORD.storage).apply()
    }

    /** 设备凭证没了（用户把锁屏密码删了）时，退回应用密码，至少还能自己设一个。 */
    fun resetToPasswordMode(plain: String) {
        useAppPassword(plain)
    }

    private fun readMode(): Mode {
        val saved = prefs.getString(KEY_MODE, null)
        return Mode.entries.firstOrNull { it.storage == saved } ?: Mode.DEVICE
    }

    private fun derive(plain: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(plain.toCharArray(), salt, iterations, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec)
            .encoded
    }

    companion object {
        const val MIN_LENGTH = 4
        private const val ITERATIONS = 120_000
        private const val SALT_BYTES = 16
        private const val KEY_MODE = "mode"
        private const val KEY_ONBOARDED = "onboarded"
        private const val KEY_SALT = "salt"
        private const val KEY_HASH = "hash"
        private const val KEY_ITERATIONS = "iterations"
    }
}
