package com.library.app.data.dao

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.library.app.data.db.AppDatabase
import com.library.app.data.db.boolOr
import com.library.app.data.db.longOr
import com.library.app.data.db.longOrNull
import com.library.app.data.db.mapRows
import com.library.app.data.db.stringOr
import com.library.app.data.db.stringOrNull
import com.library.app.data.model.Entry
import com.library.app.data.model.EntryType

/**
 * 条目表。删除是软删除：只写 deleted_at，回收站里还能捞回来，
 * 所以所有正常查询都必须带上 ALIVE 条件。
 */
class EntryDao(private val helper: AppDatabase) {

    fun list(moduleId: Long, folderId: Long?): List<Entry> {
        val (where, args) = if (folderId == null) {
            "$ALIVE AND e.module_id = ? AND e.folder_id IS NULL" to arrayOf(moduleId.toString())
        } else {
            "$ALIVE AND e.module_id = ? AND e.folder_id = ?" to
                arrayOf(moduleId.toString(), folderId.toString())
        }
        return helper.readableDatabase
            .rawQuery("$BASE_SQL WHERE $where $ORDER_SQL", args)
            .mapRows(::toEntry)
    }

    fun get(id: Long): Entry? =
        helper.readableDatabase.rawQuery("$BASE_SQL WHERE e.id = ?", arrayOf(id.toString()))
            .mapRows(::toEntry)
            .firstOrNull()

    /** 跨模块汇总某一类型，密码台用它。 */
    fun listByType(type: EntryType): List<Entry> =
        helper.readableDatabase
            .rawQuery("$BASE_SQL WHERE $ALIVE AND e.type = ? $ORDER_SQL", arrayOf(type.name))
            .mapRows(::toEntry)

    /** 某个模块下的全部条目（不区分文件夹）。 */
    fun listByModule(moduleId: Long): List<Entry> =
        helper.readableDatabase
            .rawQuery(
                "$BASE_SQL WHERE $ALIVE AND e.module_id = ? $ORDER_SQL",
                arrayOf(moduleId.toString()),
            )
            .mapRows(::toEntry)

    fun categories(moduleId: Long): List<String> =
        helper.readableDatabase.rawQuery(
            "SELECT DISTINCT category FROM entries WHERE deleted_at = 0 AND module_id = ? " +
                "AND category IS NOT NULL AND TRIM(category) <> '' ORDER BY category",
            arrayOf(moduleId.toString()),
        ).use { cursor ->
            val result = ArrayList<String>(cursor.count)
            while (cursor.moveToNext()) result.add(cursor.getString(0))
            result
        }

    fun countUnfiled(moduleId: Long): Int =
        helper.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM entries WHERE deleted_at = 0 AND module_id = ? AND folder_id IS NULL",
            arrayOf(moduleId.toString()),
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /**
     * 同步时用来判断这个源文件是不是已经在库里了。
     * 回收站里的也算 —— 否则「移出资料库」之后一轮自动同步又会把它当成新文件导进来，
     * 列表和回收站各留一条，看起来就是同一个文件出现了两次。
     */
    fun existsByFileUri(uri: String): Boolean =
        helper.readableDatabase.rawQuery(
            "SELECT 1 FROM entries WHERE file_uri = ? LIMIT 1",
            arrayOf(uri),
        ).use { it.moveToFirst() }

    /** 库里是否已经有一条指向这个文件、并且没被删掉的记录。恢复时用来去重。 */
    fun hasAliveWithFileUri(uri: String): Boolean =
        helper.readableDatabase.rawQuery(
            "SELECT 1 FROM entries WHERE file_uri = ? AND deleted_at = 0 LIMIT 1",
            arrayOf(uri),
        ).use { it.moveToFirst() }

    fun search(query: String, limit: Int = 200): List<Entry> {
        val pattern = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        return helper.readableDatabase.rawQuery(
            "$BASE_SQL WHERE $ALIVE AND (e.title LIKE ? ESCAPE '\\' OR IFNULL(e.account,'') LIKE ? ESCAPE '\\' " +
                "OR IFNULL(e.url,'') LIKE ? ESCAPE '\\' OR IFNULL(e.body,'') LIKE ? ESCAPE '\\' " +
                "OR IFNULL(e.file_name,'') LIKE ? ESCAPE '\\') $ORDER_SQL LIMIT ?",
            arrayOf(pattern, pattern, pattern, pattern, pattern, limit.toString()),
        ).mapRows(::toEntry)
    }

    fun insert(entry: Entry): Long {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("module_id", entry.moduleId)
            put("folder_id", entry.folderId)
            put("type", entry.type.name)
            put("title", entry.title)
            put("category", entry.category)
            put("account", entry.account)
            put("secret_ct", entry.secretCt)
            put("url", entry.url)
            put("body", entry.body)
            put("file_uri", entry.fileUri)
            put("file_name", entry.fileName)
            put("file_size", entry.fileSize)
            put("mime_type", entry.mimeType)
            put("is_favorite", if (entry.isFavorite) 1 else 0)
            put("deleted_at", 0)
            put("sort_order", nextSortOrder(entry.moduleId, entry.folderId))
            put("created_at", now)
            put("updated_at", now)
        }
        return helper.writableDatabase.insertOrThrow("entries", null, values)
    }

    fun update(entry: Entry) {
        val values = ContentValues().apply {
            put("type", entry.type.name)
            put("title", entry.title)
            put("category", entry.category)
            put("account", entry.account)
            put("secret_ct", entry.secretCt)
            put("url", entry.url)
            put("body", entry.body)
            put("file_uri", entry.fileUri)
            put("file_name", entry.fileName)
            put("file_size", entry.fileSize)
            put("mime_type", entry.mimeType)
            put("is_favorite", if (entry.isFavorite) 1 else 0)
            put("updated_at", System.currentTimeMillis())
        }
        helper.writableDatabase.update("entries", values, "id = ?", arrayOf(entry.id.toString()))
    }

    fun updateBody(id: Long, body: String) {
        val values = ContentValues().apply {
            put("body", body)
            put("updated_at", System.currentTimeMillis())
        }
        helper.writableDatabase.update("entries", values, "id = ?", arrayOf(id.toString()))
    }

    fun moveTo(id: Long, folderId: Long?) {
        val values = ContentValues().apply {
            put("folder_id", folderId)
            put("updated_at", System.currentTimeMillis())
        }
        helper.writableDatabase.update("entries", values, "id = ?", arrayOf(id.toString()))
    }

    /** 进回收站。trashPath 是搬进 App 私有目录的文件副本（没有副本就传 null）。 */
    fun trash(id: Long, trashPath: String? = null) {
        val values = ContentValues().apply {
            put("deleted_at", System.currentTimeMillis())
            if (trashPath != null) put("trash_path", trashPath)
        }
        helper.writableDatabase.update("entries", values, "id = ?", arrayOf(id.toString()))
    }

    /** 从回收站恢复。fileUri 非空表示恢复时换了新位置，顺带更新引用。 */
    fun restore(id: Long, fileUri: String? = null) {
        val values = ContentValues().apply {
            put("deleted_at", 0L)
            putNull("trash_path")
            if (fileUri != null) put("file_uri", fileUri)
        }
        helper.writableDatabase.update("entries", values, "id = ?", arrayOf(id.toString()))
    }

    fun deleteForever(id: Long) {
        helper.writableDatabase.delete("entries", "id = ?", arrayOf(id.toString()))
    }

    fun listTrash(): List<Entry> =
        helper.readableDatabase
            .rawQuery(
                "$BASE_SQL WHERE e.deleted_at > 0 ORDER BY e.deleted_at DESC",
                null,
            )
            .mapRows(::toEntry)

    fun trashCount(): Int =
        helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM entries WHERE deleted_at > 0", null)
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /** 回收站里所有文件副本路径，用来清掉没被任何条目引用的孤儿文件。 */
    fun trashPaths(): Set<String> =
        helper.readableDatabase.rawQuery(
            "SELECT trash_path FROM entries WHERE trash_path IS NOT NULL AND trash_path <> ''",
            null,
        ).use { cursor ->
            val result = HashSet<String>(cursor.count)
            while (cursor.moveToNext()) result.add(cursor.getString(0))
            result
        }

    /** 回收站里超过保留期的直接清掉，返回删掉的条数。 */
    fun purgeOlderThan(millis: Long): Int =
        helper.writableDatabase.delete(
            "entries",
            "deleted_at > 0 AND deleted_at < ?",
            arrayOf(millis.toString()),
        )

    fun emptyTrash(): Int =
        helper.writableDatabase.delete("entries", "deleted_at > 0", null)

    /** 和相邻条目交换排序值，用于列表里的上移/下移。 */
    fun swapSort(first: Long, second: Long) {
        helper.transaction { db ->
            val a = sortOf(db, first) ?: return@transaction
            val b = sortOf(db, second) ?: return@transaction
            db.execSQL(
                "UPDATE entries SET sort_order = ? WHERE id = ?",
                arrayOf<Any>(b, first),
            )
            db.execSQL(
                "UPDATE entries SET sort_order = ? WHERE id = ?",
                arrayOf<Any>(a, second),
            )
        }
    }

    /** 按传入顺序重写 sort_order，用于「把当前显示顺序变成手动顺序」。 */
    fun applyOrder(ids: List<Long>) {
        helper.transaction { db ->
            ids.forEachIndexed { index, id ->
                db.execSQL(
                    "UPDATE entries SET sort_order = ? WHERE id = ?",
                    arrayOf<Any>(index, id),
                )
            }
        }
    }

    private fun sortOf(db: SQLiteDatabase, id: Long): Int? =
        db.rawQuery("SELECT sort_order FROM entries WHERE id = ?", arrayOf(id.toString()))
            .use { if (it.moveToFirst()) it.getInt(0) else null }

    private fun nextSortOrder(moduleId: Long, folderId: Long?): Int {
        val sql = if (folderId == null) {
            "SELECT IFNULL(MAX(sort_order), -1) + 1 FROM entries WHERE module_id = ? AND folder_id IS NULL"
        } else {
            "SELECT IFNULL(MAX(sort_order), -1) + 1 FROM entries WHERE module_id = ? AND folder_id = ?"
        }
        val args = if (folderId == null) {
            arrayOf(moduleId.toString())
        } else {
            arrayOf(moduleId.toString(), folderId.toString())
        }
        return helper.readableDatabase.rawQuery(sql, args)
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    private fun toEntry(cursor: Cursor) = Entry(
        id = cursor.longOr("id"),
        moduleId = cursor.longOr("module_id"),
        folderId = cursor.longOrNull("folder_id"),
        type = EntryType.fromRaw(cursor.stringOrNull("type")),
        title = cursor.stringOr("title"),
        category = cursor.stringOrNull("category"),
        account = cursor.stringOrNull("account"),
        secretCt = cursor.stringOrNull("secret_ct"),
        url = cursor.stringOrNull("url"),
        body = cursor.stringOrNull("body"),
        fileUri = cursor.stringOrNull("file_uri"),
        trashPath = cursor.stringOrNull("trash_path"),
        fileName = cursor.stringOrNull("file_name"),
        fileSize = cursor.longOr("file_size"),
        mimeType = cursor.stringOrNull("mime_type"),
        isFavorite = cursor.boolOr("is_favorite"),
        deletedAt = cursor.longOr("deleted_at"),
        sortOrder = cursor.longOr("sort_order").toInt(),
        moduleName = cursor.stringOr("module_name"),
        createdAt = cursor.longOr("created_at"),
        updatedAt = cursor.longOr("updated_at"),
    )

    private companion object {
        const val ALIVE = "e.deleted_at = 0"
        const val BASE_SQL = """
            SELECT e.*, m.name AS module_name
            FROM entries e
            LEFT JOIN modules m ON m.id = e.module_id
        """
        const val ORDER_SQL = "ORDER BY e.is_favorite DESC, e.sort_order, e.id"
    }
}
