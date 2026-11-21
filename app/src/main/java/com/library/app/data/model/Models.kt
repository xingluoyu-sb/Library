package com.library.app.data.model

/**
 * PASSWORD：密码台用，账号 + 密文，不需要标题和分类。
 * RECORD  ：记录用，标题（可空）+ 正文，归到某个分组（文件夹）。
 *           清单不再是独立结构，写成正文里的 `- [ ] 事项` / `- [x] 已完成`。
 * FILE    ：资料库导入的文件，只存元信息，实体在手机存储的 library 目录里。
 */
enum class EntryType {
    PASSWORD,
    RECORD,
    FILE;

    companion object {
        fun fromRaw(raw: String?): EntryType = when (raw) {
            "PASSWORD" -> PASSWORD
            "FILE" -> FILE
            // 旧版本里的 CHECKLIST / NOTE 统一当作 RECORD
            else -> RECORD
        }
    }
}

data class Module(
    val id: Long,
    val name: String,
    val iconKey: String = "archive",
    val color: Int = 0,
    val isSystem: Boolean = false,
    /** 导入时记下的源文件夹（SAF tree uri），有值才能「同步」新增文件。 */
    val sourceTreeUri: String? = null,
    /** 上次同步源文件夹的时间，用来做节流。 */
    val lastSyncAt: Long = 0L,
    val sortOrder: Int = 0,
    val entryCount: Int = 0,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

data class Folder(
    val id: Long,
    val moduleId: Long,
    val parentId: Long?,
    val name: String,
    val sortOrder: Int = 0,
    val childCount: Int = 0,
    val entryCount: Int = 0,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

data class Entry(
    val id: Long,
    val moduleId: Long,
    val folderId: Long?,
    val type: EntryType,
    val title: String,
    val category: String? = null,
    val account: String? = null,
    val secretCt: String? = null,
    val url: String? = null,
    val body: String? = null,
    val fileUri: String? = null,
    /** 进回收站时内容被搬到 App 私有目录，这里是那份副本的路径；有它才能恢复。 */
    val trashPath: String? = null,
    val fileName: String? = null,
    val fileSize: Long = 0L,
    val mimeType: String? = null,
    val isFavorite: Boolean = false,
    /** 进回收站的时间，0 表示正常。 */
    val deletedAt: Long = 0L,
    val sortOrder: Int = 0,
    val moduleName: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    /** 标题可空，取正文第一行兜底，列表里不至于显示成一片空白。 */
    val displayTitle: String
        get() = title.trim().ifBlank { body?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim().orEmpty() }
}
