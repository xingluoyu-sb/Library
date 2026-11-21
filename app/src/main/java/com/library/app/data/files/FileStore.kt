package com.library.app.data.files

import android.provider.DocumentsContract
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore

/** 引用式条目：只存位置和元信息，磁盘上没有第二份。 */
data class DescribedFile(
    val uri: String,
    val name: String,
    val size: Long,
    val mime: String,
)

/**
 * 文件类条目走「引用」：只在库里记下 SAF 的 document uri + 名称/大小/类型，
 * 不再往「文档/library」复制一份，手机上不会出现重复内容。
 * 目录只留给数据库备份用。
 */
class FileStore(private val context: Context) {

    private val resolver get() = context.contentResolver

    fun rootLabel(): String = "${Environment.DIRECTORY_DOCUMENTS}/library"

    fun open(uriString: String, mime: String?): Boolean = runCatching {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(uriString), mime ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    /**
     * 真删源文件。SAF 的文档要用 DocumentsContract 删，MediaStore 之类的走 resolver.delete。
     * 只有拿到写权限才会成功，否则抛 SecurityException，这里统一转成 false。
     */
    fun deleteDocument(uriString: String): Boolean = runCatching {
        val uri = Uri.parse(uriString)
        if (uri.authority?.contains("documents") == true) {
            DocumentsContract.deleteDocument(resolver, uri)
        } else {
            resolver.delete(uri, null, null) > 0
        }
    }.getOrDefault(false)

    fun nameOf(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

    fun mimeOf(uri: Uri): String? = runCatching { resolver.getType(uri) }.getOrNull()

    fun sizeOf(uri: Uri): Long = runCatching {
        resolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L }
            ?: 0L
    }.getOrDefault(0L)

    /** 只记录来源，不复制文件：保留读权限，源文件挪走后条目就打不开了。 */
    fun describe(uri: Uri): DescribedFile {
        takeReadPermission(uri)
        return DescribedFile(
            uri = uri.toString(),
            name = nameOf(uri) ?: "未命名文件",
            size = sizeOf(uri),
            mime = mimeOf(uri) ?: "application/octet-stream",
        )
    }

    /**
     * 持久化读 + 写权限：写权限是为了以后能真正删除源文件。
     * 有些 provider 只给读，那就退一步只要读，删除时会失败并提示。
     */
    fun takeReadPermission(uri: Uri) {
        val both = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { resolver.takePersistableUriPermission(uri, both) }.isSuccess ||
            runCatching {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.isSuccess
    }

}

fun formatSize(bytes: Long): String = when {
    bytes <= 0L -> ""
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    else -> String.format("%.1f GB", bytes / 1024.0 / 1024.0 / 1024.0)
}

/** 判断引用的源文件还打不打得开（被移动/删除/权限过期都会 false）。 */
/**
 * 源文件还在不在。先问元数据（很轻，几百条也不卡），
 * 遇到不支持 metadata 的 provider 再退回去真的打开一次。
 */
fun fileExists(context: Context, uriString: String): Boolean = runCatching {
    val uri = Uri.parse(uriString)
    val resolver = context.contentResolver
    val cursor = resolver.query(
        uri,
        arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null,
    )
    if (cursor != null) {
        cursor.use { return it.moveToFirst() }
    }
    resolver.openInputStream(uri)?.use { true } ?: false
}.getOrDefault(false)
