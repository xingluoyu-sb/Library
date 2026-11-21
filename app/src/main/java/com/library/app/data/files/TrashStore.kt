package com.library.app.data.files

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.library.app.data.model.Entry
import com.library.app.data.model.Module
import java.io.File
import java.io.OutputStream

/** 从回收站恢复的结果。 */
sealed interface RestoreResult {
    /** 写回去了。moved = true 表示原目录写不进去，改放到了「文档/library」。 */
    data class Done(val uri: String?, val moved: Boolean) : RestoreResult

    /** 条目恢复了，但当初没留下文件副本（原文件可能还在原处，也可能早没了）。 */
    data object NoCopy : RestoreResult

    /** 副本还在，但写不回去，条目继续留在回收站。 */
    data object Failed : RestoreResult
}

/**
 * 回收站真正的存储。
 *
 * 删文件时先把内容复制进 App 私有目录（/data/data/.../files/trash），确认复制成功后才删原位置的文件，
 * 这样「原文件夹里看不到它了」和「30 天内还能恢复」两件事同时成立。
 * 恢复时优先写回原来的目录，写不进去（源文件夹被移除授权等）就落到「文档/library」，并更新条目的引用。
 */
class TrashStore(private val context: Context, private val fileStore: FileStore) {

    private val resolver get() = context.contentResolver

    private val dir: File get() = File(context.filesDir, "trash").apply { mkdirs() }

    /** 把源文件搬进回收站：只复制，不删原文件，删不删由调用方决定。返回副本路径。 */
    fun capture(entryId: Long, uriString: String): String? =
        runCatching {
            val uri = Uri.parse(uriString)
            val name = (fileStore.nameOf(uri) ?: "file").sanitize()
            val target = File(dir, "${entryId}_${System.currentTimeMillis()}_$name")
            val copied = resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
                true
            } ?: false
            if (!copied || target.length() <= 0L) {
                target.delete()
                null
            } else {
                target.absolutePath
            }
        }.getOrNull()

    /** 丢掉一份副本（永久删除 / 已恢复 / 过期清理）。 */
    fun discard(path: String?) {
        if (path.isNullOrBlank()) return
        runCatching { File(path).takeIf { it.exists() }?.delete() }
    }

    /** 清掉没有被任何条目引用的副本（数据库被还原、写入中断等留下的）。 */
    fun purgeOrphans(keep: Set<String>) {
        runCatching {
            dir.listFiles()?.forEach { file ->
                if (file.absolutePath !in keep) file.delete()
            }
        }
    }

    /** 把副本写回去，成功后自己删掉副本。必须在 IO 线程调用。 */
    fun restore(entry: Entry, module: Module?): RestoreResult {
        val copy = entry.trashPath?.let(::File)?.takeIf { it.exists() && it.length() > 0L }
            ?: return RestoreResult.NoCopy
        val name = (entry.fileName ?: entry.title).ifBlank { "恢复的文件" }
        val mime = entry.mimeType ?: "application/octet-stream"

        val write: (OutputStream) -> Unit = { output -> copy.inputStream().use { it.copyTo(output) } }

        val original = entry.fileUri?.let { uriString ->
            writeBack(Uri.parse(uriString), module, name, mime, write)
        }
        val restored = original ?: saveToLibrary(name, mime, write)
        return if (restored == null) {
            RestoreResult.Failed
        } else {
            discard(copy.absolutePath)
            RestoreResult.Done(restored.uri, moved = !restored.inPlace)
        }
    }

    private data class Written(val uri: String, val inPlace: Boolean)

    /** 先按原路径重建文档；原路径不可用时退回源文件夹根目录。 */
    private fun writeBack(
        original: Uri,
        module: Module?,
        name: String,
        mime: String,
        write: (OutputStream) -> Unit,
    ): Written? {
        val tree = module?.sourceTreeUri?.let(Uri::parse)
        if (tree == null || tree.authority != original.authority) return null
        val docId = runCatching { DocumentsContract.getDocumentId(original) }.getOrNull() ?: return null

        val parentId = docId.substringBeforeLast('/', "")
        if (parentId.isNotBlank()) {
            val parent = runCatching {
                DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
            }.getOrNull()
            if (parent != null) {
                createAndWrite(parent, name, mime, write)?.let { return Written(it, true) }
            }
        }
        // 原目录拆不出来或没写权限，就放回源文件夹根目录
        return createAndWrite(tree, name, mime, write)?.let { Written(it, false) }
    }

    private fun createAndWrite(
        parent: Uri,
        name: String,
        mime: String,
        write: (OutputStream) -> Unit,
    ): String? {
        val candidates = listOf(name, withSuffix(name, " 恢复"))
        for (candidate in candidates) {
            val created = runCatching {
                DocumentsContract.createDocument(resolver, parent, mime, candidate)
            }.getOrNull() ?: continue
            val ok = runCatching {
                resolver.openOutputStream(created)?.use(write) != null
            }.getOrDefault(false)
            if (ok) return created.toString()
            runCatching { DocumentsContract.deleteDocument(resolver, created) }
        }
        return null
    }

    /** 兜底：写进「文档/library」，并返回新的 MediaStore uri。 */
    private fun saveToLibrary(name: String, mime: String, write: (OutputStream) -> Unit): Written? =
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOCUMENTS}/library")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val target = resolver.insert(collection, values) ?: return null
            resolver.openOutputStream(target)?.use(write)
            resolver.update(
                target,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            Written(target.toString(), false)
        }.getOrNull()

    private fun String.sanitize(): String =
        replace(Regex("[/\\\\:*?\"<>|\\n\\r\\t]"), "_").take(80).ifBlank { "file" }

    private fun withSuffix(name: String, suffix: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) + suffix + name.substring(dot) else name + suffix
    }
}
