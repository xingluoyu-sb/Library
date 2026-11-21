package com.library.app.data.dao

import android.content.ContentValues
import com.library.app.data.db.AppDatabase
import com.library.app.data.db.escapeLike
import com.library.app.data.db.longOr
import com.library.app.data.db.mapRows
import com.library.app.data.db.stringOr
import com.library.app.data.db.stringOrNull
import com.library.app.data.model.Module

class ModuleDao(private val helper: AppDatabase) {

    fun list(): List<Module> =
        helper.readableDatabase
            .rawQuery("$BASE_SQL WHERE m.is_system = 0 ORDER BY m.sort_order, m.id", null)
            .mapRows(::toModule)

    fun get(id: Long): Module? =
        helper.readableDatabase.rawQuery("$BASE_SQL WHERE m.id = ?", arrayOf(id.toString()))
            .mapRows(::toModule)
            .firstOrNull()

    fun insert(name: String, iconKey: String, color: Int): Long {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("name", name)
            put("icon_key", iconKey)
            put("color", color)
            put("is_system", 0)
            put("sort_order", nextSortOrder())
            put("created_at", now)
            put("updated_at", now)
        }
        return helper.writableDatabase.insertOrThrow("modules", null, values)
    }

    /** 密码台 / 记录 这类内置空间的容器模块：不出现在「资料库」列表里。 */
    fun ensureSystemModule(name: String, color: Int): Long {
        getByName(name)?.let { return it.id }
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("name", name)
            put("icon_key", "system")
            put("color", color)
            put("is_system", 1)
            put("sort_order", nextSortOrder())
            put("created_at", now)
            put("updated_at", now)
        }
        return helper.writableDatabase.insertOrThrow("modules", null, values)
    }

    fun getByName(name: String): Module? =
        helper.readableDatabase.rawQuery("$BASE_SQL WHERE m.name = ?", arrayOf(name))
            .mapRows(::toModule)
            .firstOrNull()

    fun search(query: String): List<Module> {
        val pattern = "%" + escapeLike(query) + "%"
        return helper.readableDatabase
            .rawQuery(
                "$BASE_SQL WHERE m.is_system = 0 AND m.name LIKE ? ESCAPE '\\' ORDER BY m.sort_order, m.id",
                arrayOf(pattern),
            )
            .mapRows(::toModule)
    }

    /** 和相邻资料库交换排序值。 */
    fun swapSort(first: Long, second: Long) {
        helper.transaction { db ->
            val a = db.rawQuery("SELECT sort_order FROM modules WHERE id = ?", arrayOf(first.toString()))
                .use { if (it.moveToFirst()) it.getInt(0) else null } ?: return@transaction
            val b = db.rawQuery("SELECT sort_order FROM modules WHERE id = ?", arrayOf(second.toString()))
                .use { if (it.moveToFirst()) it.getInt(0) else null } ?: return@transaction
            db.execSQL("UPDATE modules SET sort_order = ? WHERE id = ?", arrayOf<Any>(b, first))
            db.execSQL("UPDATE modules SET sort_order = ? WHERE id = ?", arrayOf<Any>(a, second))
        }
    }

    fun rename(id: Long, name: String) {
        val values = ContentValues().apply {
            put("name", name)
            put("updated_at", System.currentTimeMillis())
        }
        helper.writableDatabase.update("modules", values, "id = ?", arrayOf(id.toString()))
    }

    /** 记住导入来源，之后「同步」照着这棵树扫一遍。 */
    fun updateSourceTree(id: Long, treeUri: String?) {
        val values = ContentValues().apply { put("source_tree_uri", treeUri) }
        helper.writableDatabase.update("modules", values, "id = ?", arrayOf(id.toString()))
    }

    fun updateLastSync(id: Long, at: Long) {
        val values = ContentValues().apply { put("last_sync_at", at) }
        helper.writableDatabase.update("modules", values, "id = ?", arrayOf(id.toString()))
    }

    fun updateColor(id: Long, color: Int) {
        val values = ContentValues().apply { put("color", color) }
        helper.writableDatabase.update("modules", values, "id = ?", arrayOf(id.toString()))
    }

    fun delete(id: Long) {
        helper.writableDatabase.delete("modules", "id = ?", arrayOf(id.toString()))
    }

    fun deleteAll() {
        helper.writableDatabase.delete("modules", null, null)
    }

    fun nameExists(name: String): Boolean =
        helper.readableDatabase.rawQuery(
            "SELECT 1 FROM modules WHERE name = ? LIMIT 1",
            arrayOf(name),
        ).use { it.moveToFirst() }

    private fun nextSortOrder(): Int =
        helper.readableDatabase.rawQuery("SELECT IFNULL(MAX(sort_order), -1) + 1 FROM modules", null)
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    private fun toModule(cursor: android.database.Cursor) = Module(
        id = cursor.longOr("id"),
        name = cursor.stringOr("name"),
        iconKey = cursor.stringOr("icon_key", "archive"),
        color = cursor.longOr("color").toInt(),
        isSystem = cursor.longOr("is_system") != 0L,
        sourceTreeUri = cursor.stringOrNull("source_tree_uri"),
        lastSyncAt = cursor.longOr("last_sync_at"),
        sortOrder = cursor.longOr("sort_order").toInt(),
        entryCount = cursor.longOr("entry_count").toInt(),
        createdAt = cursor.longOr("created_at"),
        updatedAt = cursor.longOr("updated_at"),
    )

    private companion object {
        const val BASE_SQL = """
            SELECT m.*,
                   (SELECT COUNT(*) FROM entries e WHERE e.module_id = m.id AND e.deleted_at = 0) AS entry_count
            FROM modules m
        """
    }
}
