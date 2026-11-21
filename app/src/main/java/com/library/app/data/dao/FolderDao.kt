package com.library.app.data.dao

import android.content.ContentValues
import android.database.Cursor
import com.library.app.data.db.AppDatabase
import com.library.app.data.db.escapeLike
import com.library.app.data.db.longOr
import com.library.app.data.db.longOrNull
import com.library.app.data.db.mapRows
import com.library.app.data.db.stringOr
import com.library.app.data.model.Folder

class FolderDao(private val helper: AppDatabase) {

    fun list(moduleId: Long, parentId: Long?): List<Folder> {
        val (where, args) = if (parentId == null) {
            "f.module_id = ? AND f.parent_id IS NULL" to arrayOf(moduleId.toString())
        } else {
            "f.module_id = ? AND f.parent_id = ?" to arrayOf(moduleId.toString(), parentId.toString())
        }
        return helper.readableDatabase
            .rawQuery("$BASE_SQL WHERE $where ORDER BY f.sort_order, f.id", args)
            .mapRows(::toFolder)
    }

    fun get(id: Long): Folder? =
        helper.readableDatabase.rawQuery("$BASE_SQL WHERE f.id = ?", arrayOf(id.toString()))
            .mapRows(::toFolder)
            .firstOrNull()

    /** 同层同名文件夹，导入/同步时用来避免重复建目录。 */
    /**
     * 合并同层同名文件夹：子文件夹和条目都挪到 id 最小的那个上，其余删掉。
     * 需要多轮，因为挪过来的子文件夹本身也可能和已有重名。
     */
    fun dedupeSameName(): Int = helper.transaction { db ->
        var merged = 0
        var pass = 0
        while (pass < 6) {
            val rows = db.rawQuery(
                "SELECT id, module_id, IFNULL(parent_id, -1), name FROM folders " +
                    "ORDER BY module_id, IFNULL(parent_id, -1), name, id",
                null,
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(longArrayOf(cursor.getLong(0), cursor.getLong(1), cursor.getLong(2)) to cursor.getString(3))
                    }
                }
            }
            val keep = HashMap<Triple<Long, Long, String>, Long>()
            var cleanedThisPass = 0
            rows.forEach { (ids, name) ->
                val id = ids[0]
                val key = Triple(ids[1], ids[2], name)
                val keeper = keep[key]
                if (keeper == null) {
                    keep[key] = id
                } else {
                    db.execSQL("UPDATE folders SET parent_id = ? WHERE parent_id = ?", arrayOf<Any>(keeper, id))
                    db.execSQL("UPDATE entries SET folder_id = ? WHERE folder_id = ?", arrayOf<Any>(keeper, id))
                    db.execSQL("DELETE FROM folders WHERE id = ?", arrayOf<Any>(id))
                    merged++
                    cleanedThisPass++
                }
            }
            if (cleanedThisPass == 0) break
            pass++
        }
        merged
    }

    fun findChildId(moduleId: Long, parentId: Long?, name: String): Long? {
        val sql = if (parentId == null) {
            "SELECT id FROM folders WHERE module_id = ? AND parent_id IS NULL AND name = ? LIMIT 1"
        } else {
            "SELECT id FROM folders WHERE module_id = ? AND parent_id = ? AND name = ? LIMIT 1"
        }
        val args = if (parentId == null) {
            arrayOf(moduleId.toString(), name)
        } else {
            arrayOf(moduleId.toString(), parentId.toString(), name)
        }
        return helper.readableDatabase.rawQuery(sql, args)
            .use { if (it.moveToFirst()) it.getLong(0) else null }
    }

    fun search(query: String, excludeModuleName: String? = null): List<Folder> {
        val pattern = "%" + escapeLike(query) + "%"
        val sql = StringBuilder("$BASE_SQL WHERE f.name LIKE ? ESCAPE '\\'")
        val args = mutableListOf(pattern)
        if (excludeModuleName != null) {
            sql.append(" AND f.module_id NOT IN (SELECT id FROM modules WHERE name = ?)")
            args.add(excludeModuleName)
        }
        sql.append(" ORDER BY f.module_id, f.sort_order")
        return helper.readableDatabase.rawQuery(sql.toString(), args.toTypedArray()).mapRows(::toFolder)
    }

    /** 从根到当前节点的路径，用于面包屑。 */
    fun path(folderId: Long?): List<Folder> {
        val chain = ArrayDeque<Folder>()
        var current = folderId?.let(::get)
        var guard = 0
        while (current != null && guard < 64) {
            chain.addFirst(current)
            current = current.parentId?.let(::get)
            guard++
        }
        return chain.toList()
    }

    fun insert(moduleId: Long, parentId: Long?, name: String): Long {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("module_id", moduleId)
            put("parent_id", parentId)
            put("name", name)
            put("sort_order", nextSortOrder(moduleId, parentId))
            put("created_at", now)
            put("updated_at", now)
        }
        return helper.writableDatabase.insertOrThrow("folders", null, values)
    }

    /** 和同级相邻文件夹交换排序值。 */
    fun swapSort(first: Long, second: Long) {
        helper.transaction { db ->
            val a = db.rawQuery("SELECT sort_order FROM folders WHERE id = ?", arrayOf(first.toString()))
                .use { if (it.moveToFirst()) it.getInt(0) else null } ?: return@transaction
            val b = db.rawQuery("SELECT sort_order FROM folders WHERE id = ?", arrayOf(second.toString()))
                .use { if (it.moveToFirst()) it.getInt(0) else null } ?: return@transaction
            db.execSQL("UPDATE folders SET sort_order = ? WHERE id = ?", arrayOf<Any>(b, first))
            db.execSQL("UPDATE folders SET sort_order = ? WHERE id = ?", arrayOf<Any>(a, second))
        }
    }

    fun rename(id: Long, name: String) {
        val values = ContentValues().apply {
            put("name", name)
            put("updated_at", System.currentTimeMillis())
        }
        helper.writableDatabase.update("folders", values, "id = ?", arrayOf(id.toString()))
    }

    /**
     * 把「未分组」（folder_id 为空）的记录收进指定分组。
     * 记录模块只留一个「默认分组」，不再单独摆一个未分组入口。
     */
    fun adoptUnfiled(moduleId: Long, groupId: Long): Int {
        val values = ContentValues().apply { put("folder_id", groupId) }
        return helper.writableDatabase.update(
            "entries",
            values,
            "module_id = ? AND folder_id IS NULL",
            arrayOf(moduleId.toString()),
        )
    }

    /**
     * 删掉整棵子树，但子树里的条目不能跟着消失：
     * 先把这些条目的 folder_id 上提到被删节点的父级，再删节点（子节点由外键 CASCADE 带走）。
     */
    fun deleteSubtree(id: Long) {
        helper.transaction { db ->
            val parentId = db.rawQuery(
                "SELECT parent_id FROM folders WHERE id = ?",
                arrayOf(id.toString()),
            ).use { if (it.moveToFirst()) it.getLongOrNullCompat(0) else null }

            val subtree = collectSubtreeIds(id)
            val placeholders = subtree.joinToString(",") { "?" }
            val args = subtree.map { it.toString() }.toTypedArray()

            val detach = ContentValues().apply { put("folder_id", parentId) }
            db.update("entries", detach, "folder_id IN ($placeholders)", args)
            db.delete("folders", "id = ?", arrayOf(id.toString()))
        }
    }

    private fun collectSubtreeIds(rootId: Long): List<Long> {
        val result = mutableListOf<Long>()
        val queue = ArrayDeque<Long>().apply { add(rootId) }
        while (queue.isNotEmpty() && result.size < 5000) {
            val id = queue.removeFirst()
            result.add(id)
            helper.readableDatabase.rawQuery(
                "SELECT id FROM folders WHERE parent_id = ?",
                arrayOf(id.toString()),
            ).use { cursor ->
                while (cursor.moveToNext()) queue.add(cursor.getLong(0))
            }
        }
        return result
    }

    private fun nextSortOrder(moduleId: Long, parentId: Long?): Int {
        val sql = if (parentId == null) {
            "SELECT IFNULL(MAX(sort_order), -1) + 1 FROM folders WHERE module_id = ? AND parent_id IS NULL"
        } else {
            "SELECT IFNULL(MAX(sort_order), -1) + 1 FROM folders WHERE module_id = ? AND parent_id = ?"
        }
        val args = if (parentId == null) {
            arrayOf(moduleId.toString())
        } else {
            arrayOf(moduleId.toString(), parentId.toString())
        }
        return helper.readableDatabase.rawQuery(sql, args)
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    private fun toFolder(cursor: Cursor) = Folder(
        id = cursor.longOr("id"),
        moduleId = cursor.longOr("module_id"),
        parentId = cursor.longOrNull("parent_id"),
        name = cursor.stringOr("name"),
        sortOrder = cursor.longOr("sort_order").toInt(),
        childCount = cursor.longOr("child_count").toInt(),
        entryCount = cursor.longOr("entry_count").toInt(),
        createdAt = cursor.longOr("created_at"),
        updatedAt = cursor.longOr("updated_at"),
    )

    private companion object {
        const val BASE_SQL = """
            SELECT f.*,
                   (SELECT COUNT(*) FROM folders c WHERE c.parent_id = f.id) AS child_count,
                   (SELECT COUNT(*) FROM entries e WHERE e.folder_id = f.id AND e.deleted_at = 0) AS entry_count
            FROM folders f
        """
    }
}

private fun Cursor.getLongOrNullCompat(index: Int): Long? =
    if (isNull(index)) null else getLong(index)
