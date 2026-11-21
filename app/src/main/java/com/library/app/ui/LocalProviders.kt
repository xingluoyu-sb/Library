package com.library.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.library.app.data.AppLock
import com.library.app.data.BrandingStore
import com.library.app.data.Repository
import com.library.app.data.SecurityPrefs
import com.library.app.data.SortPrefs
import com.library.app.data.crypto.SecretCrypto
import com.library.app.data.files.BackupManager
import com.library.app.data.files.FileStore
import com.library.app.data.files.FolderImporter
import com.library.app.data.files.ImportRunner

val LocalRepository = compositionLocalOf<Repository> { error("Repository 未注入") }
val LocalSecretCrypto = compositionLocalOf<SecretCrypto> { error("SecretCrypto 未注入") }
val LocalFileStore = compositionLocalOf<FileStore> { error("FileStore 未注入") }
val LocalFolderImporter = compositionLocalOf<FolderImporter> { error("FolderImporter 未注入") }
val LocalBranding = compositionLocalOf<BrandingStore> { error("BrandingStore 未注入") }
val LocalImportRunner = compositionLocalOf<ImportRunner> { error("ImportRunner 未注入") }
val LocalBackupManager = compositionLocalOf<BackupManager> { error("BackupManager 未注入") }
val LocalSecurityPrefs = compositionLocalOf<SecurityPrefs> { error("SecurityPrefs 未注入") }
val LocalSession = compositionLocalOf<SessionState> { error("SessionState 未注入") }
val LocalSortPrefs = compositionLocalOf<SortPrefs> { error("SortPrefs 未注入") }
val LocalAppLock = compositionLocalOf<AppLock> { error("AppLock 未注入") }

/**
 * 每个页面一套独立的 ViewModelStore，页面离开组合时清掉。
 * 否则 viewModel(key=...) 会一直挂在 Activity 上复用：
 * 再次「新建记录」时会拿到上一次已经保存过的那份编辑器状态，保存就变成了改旧数据。
 */
@Composable
fun ScreenScoped(content: @Composable () -> Unit) {
    val owner = remember {
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
    }
    DisposableEffect(owner) {
        onDispose { owner.viewModelStore.clear() }
    }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner, content = content)
}

/**
 * 省掉每个屏幕写一遍 factory 的样板：ViewModel 只依赖 Repository，直接构造函数注入。
 */
@Composable
inline fun <reified VM : ViewModel> libraryViewModel(
    key: String? = null,
    crossinline create: (Repository) -> VM,
): VM {
    val repository = LocalRepository.current
    return viewModel(
        key = key,
        factory = remember(repository) {
            viewModelFactory {
                initializer { create(repository) }
            }
        },
    )
}
