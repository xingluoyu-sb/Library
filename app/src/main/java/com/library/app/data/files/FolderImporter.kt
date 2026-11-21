package com.library.app.data.files

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.library.app.data.Repository
import com.library.app.data.model.Entry
import com.library.app.data.model.EntryType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 用 SAF 选一个文件夹，把整棵树按原层级建成库结构：
 * 根目录 → 一个资料库，子目录 → 模块内的文件夹，文件 → FILE 条目。
 *
 * 文件只在库里记 SAF 的 document uri，**不复制实体**，手机里不会多出第二份；
 * 源文件夹记在 modules.source_tree_uri 上，之后「同步」可以把它新增的文件补进来。
 */
class FolderImporter(
    private val context: Context,
    private val repository: Repository,
) {

    data class Progress(val folders: Int, val files: Int, val skipped: Int, val current: String)

    data class Result(
        val moduleId: Long,
        val moduleName: String,
        val folders: Int,
        val files: Int,
        val skipped: Int,
        val truncated: Boolean,
    )

    /** 整个文件夹作为新资料库导入。 */
    suspend fun importAsModule(
        treeUri: Uri,
        color: Int,
        onProgress: (Progress) -> Unit = {},
    ): Result? = withContext(Dispatchers.IO) {
        takePermission(treeUri)
        val rootId = rootDocumentId(treeUri) ?: return@withContext null
        val rootName = queryName(treeUri, rootId) ?: "导入的资料库"
        val moduleId = repository.createModuleSync(rootName, color)
        repository.setModuleSourceTreeSync(moduleId, treeUri.toString())
        val moduleName = repository.getModule(moduleId)?.name ?: rootName
        walk(moduleId, moduleName, null, treeUri, rootId, 0, onProgress)
            .also { repository.markModuleSyncedSync(moduleId) }
    }

    /** 把文件夹整体导入到已有资料库的某个文件夹下（不记来源，只这一次）。 */
    suspend fun importIntoModule(
        treeUri: Uri,
        moduleId: Long,
        parentFolderId: Long?,
        onProgress: (Progress) -> Unit = {},
    ): Result? = withContext(Dispatchers.IO) {
        takePermission(treeUri)
        val rootId = rootDocumentId(treeUri) ?: return@withContext null
        val moduleName = repository.getModule(moduleId)?.name ?: return@withContext null
        walk(moduleId, moduleName, parentFolderId, treeUri, rootId, 0, onProgress)
    }

    /**
     * 重新扫一遍导入时的源文件夹：新增的文件补进来，已有的按 document uri 跳过。
     * 源文件夹里删掉的文件不会自动移除条目，避免连带删掉自己写的东西。
     */
    suspend fun syncModule(
        moduleId: Long,
        onProgress: (Progress) -> Unit = {},
    ): Result? = withContext(Dispatchers.IO) {
        val module = repository.getModule(moduleId) ?: return@withContext null
        val stored = module.sourceTreeUri ?: return@withContext null
        val treeUri = runCatching { Uri.parse(stored) }.getOrNull() ?: return@withContext null
        takePermission(treeUri)
        val rootId = rootDocumentId(treeUri) ?: return@withContext null
        repository.dedupeFolders()
        walk(moduleId, module.name, null, treeUri, rootId, 0, onProgress)
            .also { repository.markModuleSyncedSync(moduleId) }
    }

    private fun walk(
        moduleId: Long,
        moduleName: String,
        targetFolderId: Long?,
        treeUri: Uri,
        parentDocId: String,
        depth: Int,
        onProgress: (Progress) -> Unit,
    ): Result {
        val counters = intArrayOf(0, 0) // 新增文件夹 / 新增文件
        var skipped = 0
        var truncated = false

        fun visit(docId: String, folderId: Long?, level: Int) {
            if (level > MAX_DEPTH) return
            for (doc in children(treeUri, docId)) {
                if (counters[1] >= MAX_FILES) {
                    truncated = true
                    return
                }
                if (doc.isDirectory) {
                    val (folder, created) = repository.ensureFolderSync(moduleId, folderId, doc.name)
                    if (created) counters[0]++
                    onProgress(Progress(counters[0], counters[1], skipped, doc.name))
                    visit(doc.docId, folder, level + 1)
                } else {
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, doc.docId)
                    val uriString = docUri.toString()
                    if (repository.fileEntryExistsSync(uriString)) {
                        skipped++
                    } else {
                        repository.saveEntrySync(
                            Entry(
                                id = 0L,
                                moduleId = moduleId,
                                folderId = folderId,
                                type = EntryType.FILE,
                                title = doc.name,
                                fileUri = uriString,
                                fileName = doc.name,
                                fileSize = doc.size,
                                mimeType = doc.mime.takeIf { it.isNotEmpty() },
                            ),
                        )
                        counters[1]++
                    }
                    onProgress(Progress(counters[0], counters[1], skipped, doc.name))
                }
            }
        }

        visit(parentDocId, targetFolderId, depth)

        return Result(
            moduleId = moduleId,
            moduleName = moduleName,
            folders = counters[0],
            files = counters[1],
            skipped = skipped,
            truncated = truncated,
        )
    }

    private data class Doc(
        val docId: String,
        val name: String,
        val mime: String,
        val size: Long,
    ) {
        val isDirectory: Boolean get() = mime == DocumentsContract.Document.MIME_TYPE_DIR
    }

    private fun children(treeUri: Uri, parentDocId: String): List<Doc> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val result = ArrayList<Doc>()
        runCatching {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val docId = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: "未命名"
                    val mime = cursor.getString(2) ?: ""
                    val size = if (cursor.isNull(3)) 0L else cursor.getLong(3)
                    result.add(Doc(docId, name, mime, size))
                }
            }
        }
        return result
    }

    private fun queryName(treeUri: Uri, docId: String): String? = runCatching {
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        context.contentResolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

    private fun rootDocumentId(treeUri: Uri): String? =
        runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()

    private fun takePermission(treeUri: Uri) {
        val resolver = context.contentResolver
        val both = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        if (runCatching { resolver.takePersistableUriPermission(treeUri, both) }.isSuccess) return
        runCatching {
            resolver.takePersistableUriPermission(
                treeUri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    private companion object {
        const val MAX_DEPTH = 12
        const val MAX_FILES = 2000
    }
}
