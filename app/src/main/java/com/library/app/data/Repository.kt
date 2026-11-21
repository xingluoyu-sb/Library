package com.library.app.data

import com.library.app.data.dao.EntryDao
import com.library.app.data.dao.FolderDao
import com.library.app.data.dao.ModuleDao
import com.library.app.data.db.AppDatabase
import com.library.app.data.files.FileStore
import com.library.app.data.files.RestoreResult
import com.library.app.data.files.TrashStore
import com.library.app.data.model.Entry
import com.library.app.data.model.EntryType
import com.library.app.data.model.Folder
import com.library.app.data.model.Module
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 搜索页的三段结果。 */
data class SearchResult(
    val modules: List<Module> = emptyList(),
    val folders: List<Folder> = emptyList(),
    val entries: List<Entry> = emptyList(),
) {
    val isEmpty: Boolean get() = modules.isEmpty() && folders.isEmpty() && entries.isEmpty()
    val total: Int get() = modules.size + folders.size + entries.size
}

/** 删除操作的落点，界面据此说一句准确的话。 */
enum class TrashOutcome {
    /** 记录类条目：只进回收站，没有文件。 */
    RECORD,

    /** 文件内容已搬进回收站，原位置的文件也删掉了。 */
    TRASHED,

    /** 内容搬进去了，但系统不允许删原文件（原位置还留着一份）。 */
    PARTIAL,

    /** 连内容都没读到，只把条目放进了回收站，原文件没动。 */
    COPY_FAILED,

    /** 只移出了资料库：原文件还在手机里原位待着，恢复就是把记录接回去。 */
    MOVED_OUT,
}

/**
 * 所有数据库操作都从这里走，统一切到 IO 线程。
 */
class Repository(
    private val db: AppDatabase,
    private val fileStore: FileStore,
    private val trashStore: TrashStore,
) {

    private val moduleDao = ModuleDao(db)
    private val folderDao = FolderDao(db)
    private val entryDao = EntryDao(db)

    // ---------- 资料库（顶层模块） ----------

    suspend fun listModules(): List<Module> = io { moduleDao.list() }

    suspend fun getModule(id: Long): Module? = io { moduleDao.get(id) }

    suspend fun createModule(name: String, color: Int): Long = io { createModuleSync(name, color) }

    fun createModuleSync(name: String, color: Int): Long {
        var candidate = name.trim().ifBlank { "未命名资料库" }
        if (moduleDao.nameExists(candidate)) {
            var index = 2
            while (moduleDao.nameExists("$candidate $index") && index < 100) index++
            candidate = "$candidate $index"
        }
        return moduleDao.insert(candidate, "archive", color)
    }

    suspend fun renameModule(id: Long, name: String) = io {
        val trimmed = name.trim()
        if (trimmed.isNotEmpty()) moduleDao.rename(id, trimmed)
    }

    suspend fun deleteModule(id: Long) = io { moduleDao.delete(id) }

    // ---------- 文件夹 / 分组 ----------

    suspend fun listFolders(moduleId: Long, parentId: Long?): List<Folder> =
        io { folderDao.list(moduleId, parentId) }

    suspend fun folderPath(folderId: Long?): List<Folder> = io { folderDao.path(folderId) }

    suspend fun createFolder(moduleId: Long, parentId: Long?, name: String): Long = io {
        createFolderSync(moduleId, parentId, name)
    }

    fun createFolderSync(moduleId: Long, parentId: Long?, name: String): Long =
        folderDao.insert(moduleId, parentId, name.trim().ifBlank { "新建文件夹" })

    /**
     * 导入/同步时按「同层同名」复用已有文件夹，避免每次同步都叠一层目录。
     * 返回值 second 表示这次是不是新建的，用来统计新增数量。
     */
    fun ensureFolderSync(moduleId: Long, parentId: Long?, name: String): Pair<Long, Boolean> {
        // 查重和写入必须用同一份规范化后的名字：SAF 给的目录名常带尾随空格/换行，
        // 以前拿原名去查、用 trim 后的名字入库，导致每次同步都查不到、多建一个同名文件夹。
        val clean = normalizeFolderName(name)
        folderDao.findChildId(moduleId, parentId, clean)?.let { return it to false }
        val id = folderDao.insert(moduleId, parentId, clean)
        return id to true
    }

    /** 文件夹名统一规范化：去首尾空白（含全角空格/不换行空格），空了给个默认名。 */
    fun normalizeFolderName(name: String): String {
        val trimmed = name.replace('\u00A0', ' ').trim()
        return trimmed.ifBlank { "新建文件夹" }
    }

    /** 合并历史遗留的同层同名文件夹（早期同步 bug 留下的）。返回合并掉的个数。 */
    suspend fun dedupeFolders(): Int = io { folderDao.dedupeSameName() }

    /** 这个源文件是否已经在库里（同步时去重用）。 */
    fun fileEntryExistsSync(fileUri: String): Boolean = entryDao.existsByFileUri(fileUri)

    fun setModuleSourceTreeSync(id: Long, treeUri: String?) = moduleDao.updateSourceTree(id, treeUri)

    fun markModuleSyncedSync(id: Long) = moduleDao.updateLastSync(id, System.currentTimeMillis())

    suspend fun markModuleSynced(id: Long) = io { moduleDao.updateLastSync(id, System.currentTimeMillis()) }

    suspend fun renameFolder(id: Long, name: String) = io {
        val trimmed = name.trim()
        if (trimmed.isNotEmpty()) folderDao.rename(id, trimmed)
    }

    suspend fun deleteFolder(id: Long) = io { folderDao.deleteSubtree(id) }

    // ---------- 条目 ----------

    suspend fun listEntries(moduleId: Long, folderId: Long?): List<Entry> =
        io { entryDao.list(moduleId, folderId) }

    suspend fun getEntry(id: Long): Entry? = io { entryDao.get(id) }

    suspend fun saveEntry(entry: Entry): Long = io { saveEntrySync(entry) }

    fun saveEntrySync(entry: Entry): Long =
        if (entry.id == 0L) entryDao.insert(entry) else {
            entryDao.update(entry)
            entry.id
        }

    suspend fun setFavorite(entry: Entry, favorite: Boolean) = io {
        entryDao.update(entry.copy(isFavorite = favorite))
    }

    /** 记录列表里直接勾选 `- [ ]` 行时调用。 */
    suspend fun updateEntryBody(id: Long, body: String) = io { entryDao.updateBody(id, body) }

    /** 删除进回收站（软删除），可在回收站里恢复。 */
    suspend fun deleteEntry(id: Long) = io { entryDao.trash(id) }

    /**
     * 删文件：先把内容复制进 App 私有回收站，成功后再删原位置的文件，
     * 条目同时进回收站（带 trash_path）。这样回收站里有东西，30 天内也真能恢复。
     */
    suspend fun trashEntry(entry: Entry): TrashOutcome = io {
        val uri = entry.fileUri
        if (entry.type != EntryType.FILE || uri.isNullOrBlank()) {
            entryDao.trash(entry.id)
            return@io TrashOutcome.RECORD
        }
        val copy = trashStore.capture(entry.id, uri)
        if (copy == null) {
            // 读不到内容就别动原文件，只把条目收进回收站
            entryDao.trash(entry.id)
            return@io TrashOutcome.COPY_FAILED
        }
        val removed = fileStore.deleteDocument(uri)
        entryDao.trash(entry.id, copy)
        if (removed) TrashOutcome.TRASHED else TrashOutcome.PARTIAL
    }

    suspend fun restoreEntry(id: Long) = io { entryDao.restore(id) }

    /**
     * 只把条目移出资料库：进回收站，但手机里的文件一个字都不动。
     * 适合「只是想让它别出现在库里」的情况 —— 恢复时把记录接回去就行。
     */
    suspend fun moveOutOfLibrary(entry: Entry): TrashOutcome = io {
        entryDao.trash(entry.id)
        if (entry.type == EntryType.FILE && !entry.fileUri.isNullOrBlank()) {
            TrashOutcome.MOVED_OUT
        } else {
            TrashOutcome.RECORD
        }
    }

    /** 从回收站恢复文件条目：写回原目录（不行就落到 文档/library）。 */
    suspend fun restoreFromTrash(entry: Entry): RestoreResult = io {
        if (entry.trashPath.isNullOrBlank()) {
            val uri = entry.fileUri
            // 原文件没被动过，直接恢复条目。但要是同步已经把它重新导进来了一条，
            // 就把这条旧的清掉，不然列表里会出现两条一模一样的。
            if (!uri.isNullOrBlank() && entryDao.hasAliveWithFileUri(uri)) {
                entryDao.deleteForever(entry.id)
                return@io RestoreResult.Done(uri, moved = false)
            }
            entryDao.restore(entry.id, null)
            return@io RestoreResult.Done(uri, moved = false)
        }
        val module = moduleDao.get(entry.moduleId)
        when (val result = trashStore.restore(entry, module)) {
            is RestoreResult.Done -> {
                entryDao.restore(entry.id, result.uri)
                result
            }
            // 副本丢了或写不回去，就留在回收站里，别造一条打不开的记录
            else -> RestoreResult.Failed
        }
    }

    suspend fun deleteEntryForever(entry: Entry) = io {
        trashStore.discard(entry.trashPath)
        entryDao.deleteForever(entry.id)
    }

    suspend fun listTrash(): List<Entry> = io { entryDao.listTrash() }

    suspend fun trashCount(): Int = io { entryDao.trashCount() }

    suspend fun emptyTrash() = io {
        entryDao.listTrash().forEach { trashStore.discard(it.trashPath) }
        entryDao.emptyTrash()
    }

    /** 回收站保留 30 天，超时副本和条目一起清掉，顺手扫掉孤儿副本。 */
    suspend fun purgeTrash(retentionDays: Int = 30) = io {
        val cutoff = System.currentTimeMillis() - retentionDays * 86_400_000L
        entryDao.listTrash()
            .filter { it.deletedAt in 1 until cutoff }
            .forEach {
                trashStore.discard(it.trashPath)
                entryDao.deleteForever(it.id)
            }
        trashStore.purgeOrphans(entryDao.trashPaths())
    }

    // ---------- 排序（上移/下移） ----------

    suspend fun moveModule(id: Long, up: Boolean) = io {
        val list = moduleDao.list()
        val index = list.indexOfFirst { it.id == id }
        val target = if (up) index - 1 else index + 1
        if (index >= 0 && target in list.indices) {
            moduleDao.swapSort(list[index].id, list[target].id)
        }
    }

    suspend fun moveFolder(moduleId: Long, parentId: Long?, id: Long, up: Boolean) = io {
        val list = folderDao.list(moduleId, parentId)
        val index = list.indexOfFirst { it.id == id }
        val target = if (up) index - 1 else index + 1
        if (index >= 0 && target in list.indices) {
            folderDao.swapSort(list[index].id, list[target].id)
        }
    }

    /** 把当前显示顺序固化成手动顺序，拖动排序前调一次，视觉上不会跳。 */
    suspend fun applyEntryOrder(ids: List<Long>) = io { entryDao.applyOrder(ids) }

    suspend fun moveEntry(moduleId: Long, folderId: Long?, id: Long, up: Boolean) = io {
        val list = entryDao.list(moduleId, folderId)
        val index = list.indexOfFirst { it.id == id }
        val target = if (up) index - 1 else index + 1
        if (index >= 0 && target in list.indices) {
            entryDao.swapSort(list[index].id, list[target].id)
        }
    }

    suspend fun moveEntry(id: Long, folderId: Long?) = io { entryDao.moveTo(id, folderId) }

    // ---------- 密码台 ----------

    suspend fun listPasswords(): List<Entry> = io { entryDao.listByType(EntryType.PASSWORD) }

    suspend fun passwordsModuleId(): Long = io { moduleDao.ensureSystemModule(SYSTEM_PASSWORDS, 0) }

    // ---------- 记录（分组 = 文件夹，只有一个「默认分组」兜底） ----------

    /** 记录模块 id，第一次调用时自动建。 */
    suspend fun recordsModuleId(): Long = io { moduleDao.ensureSystemModule(SYSTEM_RECORDS, 0) }

    /**
     * 记录首页分组列表：保证至少有一个「默认分组」，并把「未分组」的记录收进去。
     * 所以列表里不会再出现语义重复的「未分组」。
     */
    suspend fun listRecordGroups(): List<Folder> = io {
        val moduleId = moduleDao.ensureSystemModule(SYSTEM_RECORDS, 0)
        var groups = folderDao.list(moduleId, null)
        if (groups.isEmpty()) {
            folderDao.insert(moduleId, null, DEFAULT_GROUP)
            groups = folderDao.list(moduleId, null)
        }
        groups.firstOrNull()?.let { folderDao.adoptUnfiled(moduleId, it.id) }
        // 收编之后重新读一次，让每组的条数准确
        folderDao.list(moduleId, null)
    }

    /** 新建记录默认落在哪一组：列表里的第一组（默认分组）。 */
    suspend fun recordDefaultGroupId(): Long = io {
        val moduleId = moduleDao.ensureSystemModule(SYSTEM_RECORDS, 0)
        folderDao.list(moduleId, null).firstOrNull()?.id
            ?: folderDao.insert(moduleId, null, DEFAULT_GROUP)
    }

    suspend fun listRecords(folderId: Long?): List<Entry> = io {
        val moduleId = moduleDao.ensureSystemModule(SYSTEM_RECORDS, 0)
        entryDao.list(moduleId, folderId)
    }

    suspend fun createRecordGroup(name: String): Long = io {
        val moduleId = moduleDao.ensureSystemModule(SYSTEM_RECORDS, 0)
        folderDao.insert(moduleId, null, name.trim().ifBlank { "新分组" })
    }

    // ---------- 搜索 ----------

    suspend fun search(query: String): SearchResult = io {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@io SearchResult()
        SearchResult(
            modules = moduleDao.search(trimmed),
            folders = folderDao.search(trimmed, excludeModuleName = SYSTEM_RECORDS),
            entries = entryDao.search(trimmed),
        )
    }

    // ---------- 杂项 ----------

    suspend fun clearAll() = io { moduleDao.deleteAll() }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        /**
         * 数据库里这个系统模块一直叫「密码台」。界面上已经改叫「密码」，
         * 这里保持旧值不动：改了会按新名字重新建模块，看起来就像密码全丢了。
         */
        const val SYSTEM_PASSWORDS = "密码台"
        const val SYSTEM_RECORDS = "记录"
        const val DEFAULT_GROUP = "默认分组"
    }
}
