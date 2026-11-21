package com.library.app.ui.screens.module

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.library.app.data.Repository
import com.library.app.data.TrashOutcome
import com.library.app.data.files.FileStore
import com.library.app.data.files.ImportRunner
import com.library.app.data.model.Entry
import com.library.app.data.model.EntryType
import com.library.app.data.model.Folder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.library.app.i18n.tr

class ModuleViewModel(
    private val repository: Repository,
    private val moduleId: Long,
    private val folderId: Long?,
) : ViewModel() {

    var moduleName by mutableStateOf("")
        private set
    var folders by mutableStateOf<List<Folder>>(emptyList())
        private set
    var entries by mutableStateOf<List<Entry>>(emptyList())
        private set
    var breadcrumb by mutableStateOf<List<Folder>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set

    /** 非空表示这个资料库是「导入文件夹」来的，可以同步源目录。 */
    var sourceTreeUri by mutableStateOf<String?>(null)
        private set

    private var lastSyncAt = 0L
    private var syncing = false

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            loading = true
            val module = repository.getModule(moduleId)
            moduleName = module?.name ?: "已删除的资料库".tr()
            sourceTreeUri = module?.sourceTreeUri
            lastSyncAt = module?.lastSyncAt ?: 0L
            folders = repository.listFolders(moduleId, folderId)
            entries = repository.listEntries(moduleId, folderId)
            breadcrumb = repository.folderPath(folderId)
            loading = false
        }
    }

    /**
     * 进页面静默补一次：距上次同步超过 60 秒才扫，避免来回切页反复遍历。
     * 交给 ImportRunner，退出页面也不会把同步掐断。
     */
    fun autoSync(runner: ImportRunner) {
        if (syncing) return
        if (sourceTreeUri == null) return
        if (System.currentTimeMillis() - lastSyncAt < AUTO_SYNC_INTERVAL_MS) return
        syncing = true
        runner.syncModule(moduleId, silent = true) { outcome ->
            syncing = false
            if (outcome is ImportRunner.Outcome.Done &&
                (outcome.result.files > 0 || outcome.result.folders > 0)
            ) {
                refresh()
            }
        }
    }

    fun createFolder(name: String) {
        viewModelScope.launch {
            repository.createFolder(moduleId, folderId, name)
            refresh()
        }
    }

    fun renameCurrentFolder(name: String) {
        val id = folderId ?: return
        viewModelScope.launch {
            repository.renameFolder(id, name)
            refresh()
        }
    }

    fun renameFolder(id: Long, name: String) {
        viewModelScope.launch {
            repository.renameFolder(id, name)
            refresh()
        }
    }

    fun deleteFolder(id: Long) {
        viewModelScope.launch {
            repository.deleteFolder(id)
            refresh()
        }
    }

    fun moveFolder(id: Long, up: Boolean) {
        viewModelScope.launch {
            repository.moveFolder(moduleId, folderId, id, up)
            refresh()
        }
    }

    fun moveEntry(id: Long, up: Boolean) {
        viewModelScope.launch {
            repository.moveEntry(moduleId, folderId, id, up)
            refresh()
        }
    }

    fun deleteCurrentFolder() {
        val id = folderId ?: return
        viewModelScope.launch {
            repository.deleteFolder(id)
        }
    }

    /** 把当前显示顺序固化成手动顺序（拖动排序前调用）。 */
    fun applyOrder(ids: List<Long>) {
        viewModelScope.launch {
            repository.applyEntryOrder(ids)
            refresh()
        }
    }

    /**
     * 两种「删除」：
     * - [DeleteMode.MOVE_OUT] 只移出资料库，手机里的文件一个字节都不动；
     * - [DeleteMode.DELETE] 内容先搬进 App 私有回收站，再删原位置的文件。
     * 两者都进回收站，30 天内能恢复。
     */
    fun deleteFileEntry(entry: Entry, mode: DeleteMode, onDone: (TrashOutcome) -> Unit) {
        viewModelScope.launch {
            val outcome = dispose(entry, mode)
            refresh()
            onDone(outcome)
        }
    }

    /** 批量删除：按同一个模式逐个处理。 */
    fun deleteEntries(
        entries: List<Entry>,
        mode: DeleteMode,
        onDone: (List<TrashOutcome>) -> Unit,
    ) {
        viewModelScope.launch {
            val outcomes = entries.map { dispose(it, mode) }
            refresh()
            onDone(outcomes)
        }
    }

    private suspend fun dispose(entry: Entry, mode: DeleteMode): TrashOutcome =
        if (mode == DeleteMode.MOVE_OUT) repository.moveOutOfLibrary(entry) else repository.trashEntry(entry)

    fun deleteEntry(id: Long) {
        viewModelScope.launch {
            repository.deleteEntry(id)
            refresh()
        }
    }

    /** 单个/多个文件只记录位置，不复制实体，避免手机里存两份。 */
    fun importFiles(uris: List<Uri>, fileStore: FileStore) {
        viewModelScope.launch {
            uris.forEach { uri ->
                val described = fileStore.describe(uri)
                repository.saveEntry(
                    Entry(
                        id = 0L,
                        moduleId = moduleId,
                        folderId = folderId,
                        type = EntryType.FILE,
                        title = described.name,
                        fileUri = described.uri,
                        fileName = described.name,
                        fileSize = described.size,
                        mimeType = described.mime,
                    ),
                )
            }
            refresh()
        }
    }

    /** 把一个文件夹整棵树导入到当前资料库/当前文件夹下。 */
    fun importFolderTree(
        runner: ImportRunner,
        treeUri: Uri,
        onResult: (String) -> Unit,
    ) {
        runner.importIntoModule(treeUri, moduleId, folderId) { outcome ->
            refresh()
            onResult(ImportRunner.describe(outcome))
        }
    }

    /** 重新扫描导入时的源文件夹，把后来新增的文件补进来。 */
    fun syncSource(runner: ImportRunner, onResult: (String) -> Unit) {
        if (sourceTreeUri == null) {
            onResult("这个资料库不是从文件夹导入的，没有可同步的来源".tr())
            return
        }
        runner.syncModule(moduleId, silent = false) { outcome ->
            refresh()
            onResult(ImportRunner.describeSync(outcome))
        }
    }

    /** 源文件被移动/删除后，重新指定一个文件，把这条索引接回去。 */
    fun relocate(entry: Entry, uri: Uri, fileStore: FileStore) {
        viewModelScope.launch {
            val described = fileStore.describe(uri)
            repository.saveEntry(
                entry.copy(
                    title = entry.title.ifBlank { described.name },
                    fileUri = described.uri,
                    fileName = described.name,
                    fileSize = described.size,
                    mimeType = described.mime,
                ),
            )
            refresh()
        }
    }

    fun toggleFavorite(entry: Entry) {
        viewModelScope.launch {
            repository.setFavorite(entry, !entry.isFavorite)
            refresh()
        }
    }

    private companion object {
        const val AUTO_SYNC_INTERVAL_MS = 60_000L
    }
}

/** 删除时选哪种处理方式。 */
enum class DeleteMode {
    /** 只移出资料库：手机里的文件不动。 */
    MOVE_OUT,

    /** 连文件一起处理：内容搬进 App 回收站，原位置那份删掉。 */
    DELETE,
}
