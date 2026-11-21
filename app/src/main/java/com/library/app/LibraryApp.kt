package com.library.app

import android.app.Application
import com.library.app.data.AppContainer
import kotlinx.coroutines.launch

class LibraryApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // 语言要在第一帧之前读出来，否则界面会闪一下中文
        com.library.app.i18n.Lang.init(this)
        // 回收站只在启动时清一次过期内容，不值得为它起常驻任务
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching { container.repository.purgeTrash() }
            // 早期同步 bug 会留下同层同名的文件夹，启动时顺手合并掉
            runCatching { container.repository.dedupeFolders() }
        }
    }
}
