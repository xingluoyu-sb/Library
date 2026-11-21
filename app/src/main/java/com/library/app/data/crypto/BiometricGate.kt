package com.library.app.data.crypto

import android.app.Activity
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

sealed interface AuthOutcome {
    data class Success(val crypto: BiometricPrompt.CryptoObject?) : AuthOutcome
    data object Cancelled : AuthOutcome
    data class Failed(val code: Int, val message: CharSequence?) : AuthOutcome
}

/**
 * 平台自带的 BiometricPrompt 封装，不引 androidx.biometric。
 * 同一时刻只允许一个弹窗（系统本身也不允许并发），所以用 Mutex 串行化。
 */
object BiometricGate {

    private val mutex = Mutex()
    private val mainHandler = Handler(Looper.getMainLooper())

    const val ALLOWED = BiometricManager.Authenticators.BIOMETRIC_STRONG or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL

    /** 设备是否至少具备一种可用验证方式。 */
    fun canAuthenticate(activity: Activity): Boolean {
        val manager = activity.getSystemService(BiometricManager::class.java)
        return manager?.canAuthenticate(ALLOWED) == BiometricManager.BIOMETRIC_SUCCESS ||
            manager?.canAuthenticate(BiometricManager.Authenticators.DEVICE_CREDENTIAL) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    fun hasEnrolledBiometric(activity: Activity): Boolean {
        val manager = activity.getSystemService(BiometricManager::class.java) ?: return false
        return manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    suspend fun authenticate(
        activity: Activity,
        title: String,
        subtitle: String? = null,
        crypto: BiometricPrompt.CryptoObject? = null,
    ): AuthOutcome = mutex.withLock {
        suspendCancellableCoroutine { continuation ->
            val cancellationSignal = CancellationSignal()
            continuation.invokeOnCancellation { cancellationSignal.cancel() }

            val prompt = BiometricPrompt.Builder(activity)
                .setTitle(title)
                .apply { if (!subtitle.isNullOrBlank()) setSubtitle(subtitle) }
                // 同时允许生物识别与锁屏密码：这个组合下不能再设 negative button
                .setAllowedAuthenticators(ALLOWED)
                .build()

            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (continuation.isActive) continuation.resume(AuthOutcome.Success(result.cryptoObject))
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (!continuation.isActive) return
                    val outcome = when (errorCode) {
                        BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED,
                        BiometricPrompt.BIOMETRIC_ERROR_CANCELED,
                        13, // 负向按钮（androidx 里的 ERROR_NEGATIVE_BUTTON，平台常量未公开）
                        -> AuthOutcome.Cancelled

                        else -> AuthOutcome.Failed(errorCode, errString)
                    }
                    continuation.resume(outcome)
                }

                override fun onAuthenticationFailed() {
                    // 单次指纹不匹配，系统会自己让用户重试，这里不用做任何事
                }
            }

            mainHandler.post {
                runCatching {
                    prompt.authenticate(cancellationSignal, activity.mainExecutor, callback)
                }.onFailure {
                    if (continuation.isActive) {
                        continuation.resume(AuthOutcome.Failed(-1, it.message))
                    }
                }
            }
        }
    }
}
