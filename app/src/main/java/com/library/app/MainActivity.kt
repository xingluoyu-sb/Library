package com.library.app

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.library.app.ui.LibraryRoot
import com.library.app.ui.LocalAppLock
import com.library.app.ui.LocalBackupManager
import com.library.app.ui.LocalBranding
import com.library.app.ui.LocalFileStore
import com.library.app.ui.LocalFolderImporter
import com.library.app.ui.LocalImportRunner
import com.library.app.ui.LocalRepository
import com.library.app.ui.LocalSecretCrypto
import com.library.app.ui.LocalSecurityPrefs
import com.library.app.ui.LocalSortPrefs
import com.library.app.ui.SessionState
import com.library.app.ui.theme.LibraryTheme

class MainActivity : ComponentActivity() {

    private val session = SessionState()
    private var stoppedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // DEBUG ONLY: adb shell am start ... --ez dev_crypto_test true 跑一遍加解密自检
        val debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable && intent?.getBooleanExtra("dev_crypto_test", false) == true) {
            kotlin.concurrent.thread {
                val box = com.library.app.data.crypto.CryptoBox(this)
                val result = runCatching {
                    val state = box.ensureKey()
                    val ct = box.encrypt("hello-资料库")
                    val pt = box.decrypt(ct)
                    "state=$state ctLen=${ct.length} roundTrip=${pt == "hello-资料库"}"
                }
                android.util.Log.i("LibraryCryptoTest", result.getOrElse { "FAIL ${it.javaClass.name}: ${it.message}" })
            }
        }

        val container = (application as LibraryApp).container

        // DEBUG ONLY: adb shell am start ... --ez dev_unlock true 跳过指纹，方便无人值守看界面。
        if (debuggable && intent?.getBooleanExtra("dev_unlock", false) == true) session.unlock()
        if (debuggable) {
            intent?.getLongExtra("dev_view", -1L)?.takeIf { it > 0L }?.let { session.devViewEntryId = it }
        }

        setContent {
            CompositionLocalProvider(
                LocalRepository provides container.repository,
                LocalSecretCrypto provides container.secretCrypto,
                LocalFileStore provides container.fileStore,
                LocalFolderImporter provides container.folderImporter,
                LocalImportRunner provides container.importRunner,
                LocalBackupManager provides container.backupManager,
                LocalSecurityPrefs provides container.security,
                LocalSortPrefs provides container.sortPrefs,
                LocalBranding provides container.branding,
                LocalAppLock provides container.appLock,
            ) {
                LibraryTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        LibraryRoot(session)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // 后台停留超过 1 分钟回来要重新验证，避免手机借人时直接看到内容
        if (stoppedAt > 0L && SystemClock.elapsedRealtime() - stoppedAt >= LOCK_AFTER_MS) {
            session.lock()
        }
    }

    override fun onStop() {
        super.onStop()
        stoppedAt = SystemClock.elapsedRealtime()
    }

    private companion object {
        const val LOCK_AFTER_MS = 60_000L
    }
}
