package com.library.app.data.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class AppDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, NAME, null, VERSION) {

    companion object {
        const val NAME = "library.db"

        /**
         * 3 → 4：清单表并进正文（`- [ ]` 行），删掉 checklist_items；
         *        记录模块统一出一个「默认分组」，原来 folder_id 为空的记录全部并进去。
         * 4 → 5：modules 加 source_tree_uri / last_sync_at，记住导进来的源文件夹与上次同步时间，
         *        进模块时按需自动同步新增文件。
         * 5 → 6：entries 加 deleted_at，删除改成进回收站（软删除），30 天后才真删。
         * 6 → 7：entries 加 trash_path。删文件时把内容先搬进 App 私有回收站目录，
         *        原位置的文件照旧删掉，30 天内可以从回收站写回去。
         * 7 → 8：新增 crypto_keys，存「用设备密钥包起来的数据密钥（DEK）」和 crypto_version。
         *        crypto_version = 1 表示 entries.secret_ct 还是老的 Keystore 密文，
         *        = 2 表示已经全部换成 DEK 密文。读取路径只看这个值，不靠试解密。
         */
        const val VERSION = 8
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        // 必须在 onConfigure 里开，写在 onCreate 里对外键无效
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        Schema.CREATE.forEach(db::execSQL)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Migrations.run(db, oldVersion, newVersion)
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw IllegalStateException("数据库版本 $oldVersion 高于 App 支持的 $newVersion，拒绝降级以免丢数据")
    }

    /**
     * 一个事务包起来，异常自动回滚。
     */
    fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val result = block(db)
            db.setTransactionSuccessful()
            result
        } finally {
            db.endTransaction()
        }
    }
}

object Migrations {

    const val RECORD_MODULE = "记录"
    const val DEFAULT_GROUP = "默认分组"

    /**
     * 每一步只负责 oldVersion → oldVersion + 1，run 会逐级执行。
     * 加字段就往后补一条，不要 drop 重建。
     */
    private val steps: Map<Int, (SQLiteDatabase) -> Unit> = mapOf(
        1 to { db ->
            db.execSQL("ALTER TABLE modules ADD COLUMN is_system INTEGER NOT NULL DEFAULT 0")
        },
        2 to { db ->
            db.execSQL("ALTER TABLE entries ADD COLUMN file_uri TEXT")
            db.execSQL("ALTER TABLE entries ADD COLUMN file_name TEXT")
            db.execSQL("ALTER TABLE entries ADD COLUMN file_size INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE entries ADD COLUMN mime_type TEXT")
        },
        3 to { db ->
            mergeChecklistIntoBody(db)
            db.execSQL("DROP TABLE IF EXISTS checklist_items")
            unifyRecordGroup(db)
        },
        4 to { db ->
            db.execSQL("ALTER TABLE modules ADD COLUMN source_tree_uri TEXT")
            db.execSQL("ALTER TABLE modules ADD COLUMN last_sync_at INTEGER NOT NULL DEFAULT 0")
        },
        5 to { db ->
            db.execSQL("ALTER TABLE entries ADD COLUMN deleted_at INTEGER NOT NULL DEFAULT 0")
        },
        6 to { db ->
            db.execSQL("ALTER TABLE entries ADD COLUMN trash_path TEXT")
        },
        7 to { db ->
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS crypto_keys (
                    id             INTEGER PRIMARY KEY CHECK (id = 1),
                    wrapped_dek    TEXT    NOT NULL,
                    crypto_version INTEGER NOT NULL DEFAULT 1,
                    created_at     INTEGER NOT NULL
                )
                """.trimIndent(),
            )
        },
    )

    fun run(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        for (version in oldVersion until newVersion) {
            val step = steps[version]
                ?: throw IllegalStateException("缺少 $version → ${version + 1} 的迁移步骤")
            step(db)
        }
    }

    /** 旧清单按顺序拼进正文，写成 `- [ ] 文本` / `- [x] 文本`，编辑器和列表都还能看懂。 */
    private fun mergeChecklistIntoBody(db: SQLiteDatabase) {
        val lines = LinkedHashMap<Long, MutableList<String>>()
        db.rawQuery(
            "SELECT entry_id, text, is_done FROM checklist_items ORDER BY entry_id, sort_order, id",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val entryId = cursor.getLong(0)
                val text = if (cursor.isNull(1)) "" else cursor.getString(1)
                if (text.isBlank()) continue
                val done = !cursor.isNull(2) && cursor.getLong(2) != 0L
                lines.getOrPut(entryId) { mutableListOf() }
                    .add(if (done) "- [x] $text" else "- [ ] $text")
            }
        }
        lines.forEach { (entryId, checklistLines) ->
            val existing = db.rawQuery(
                "SELECT body FROM entries WHERE id = ?",
                arrayOf(entryId.toString()),
            ).use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null }
            val merged = listOfNotNull(existing?.takeIf { it.isNotBlank() })
                .plus(checklistLines)
                .joinToString("\n")
            db.execSQL(
                "UPDATE entries SET body = ? WHERE id = ?",
                arrayOf<Any>(merged, entryId),
            )
        }
    }

    /** 「未分组」并入「默认分组」，分组列表里不再出现一个语义重复的假分组。 */
    private fun unifyRecordGroup(db: SQLiteDatabase) {
        val moduleId = db.rawQuery(
            "SELECT id FROM modules WHERE name = ? AND is_system = 1 LIMIT 1",
            arrayOf(RECORD_MODULE),
        ).use { if (it.moveToFirst()) it.getLong(0) else null } ?: return

        var groupId = db.rawQuery(
            "SELECT id FROM folders WHERE module_id = ? AND parent_id IS NULL ORDER BY sort_order, id LIMIT 1",
            arrayOf(moduleId.toString()),
        ).use { if (it.moveToFirst()) it.getLong(0) else null }

        if (groupId == null) {
            val now = System.currentTimeMillis()
            val values = ContentValues().apply {
                put("module_id", moduleId)
                putNull("parent_id")
                put("name", DEFAULT_GROUP)
                put("sort_order", 0)
                put("created_at", now)
                put("updated_at", now)
            }
            groupId = db.insertOrThrow("folders", null, values)
        }

        db.execSQL(
            "UPDATE entries SET folder_id = ? WHERE module_id = ? AND folder_id IS NULL",
            arrayOf<Any>(groupId, moduleId),
        )
    }
}
