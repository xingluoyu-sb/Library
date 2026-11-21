package com.library.app.data.db

/**
 * 表结构集中在这里。加字段请同时提高 [AppDatabase.VERSION] 并在 Migrations 里补一步，
 * 不要用 onUpgrade 里的 drop 重建，否则用户数据直接没了。
 */
object Schema {

    const val TABLE_MODULES = "modules"
    const val TABLE_FOLDERS = "folders"
    const val TABLE_ENTRIES = "entries"
    const val TABLE_TAGS = "tags"
    const val TABLE_ENTRY_TAGS = "entry_tags"
    const val TABLE_SETTINGS = "settings"

    val CREATE: List<String> = listOf(

        """
        CREATE TABLE modules (
            id          INTEGER PRIMARY KEY AUTOINCREMENT,
            name        TEXT    NOT NULL,
            icon_key    TEXT    NOT NULL DEFAULT 'archive',
            color       INTEGER NOT NULL DEFAULT 0,
            is_system   INTEGER NOT NULL DEFAULT 0,
            source_tree_uri TEXT,
            last_sync_at INTEGER NOT NULL DEFAULT 0,
            sort_order  INTEGER NOT NULL DEFAULT 0,
            created_at  INTEGER NOT NULL,
            updated_at  INTEGER NOT NULL
        )
        """.trimIndent(),

        """
        CREATE TABLE folders (
            id          INTEGER PRIMARY KEY AUTOINCREMENT,
            module_id   INTEGER NOT NULL REFERENCES modules(id) ON DELETE CASCADE,
            parent_id   INTEGER REFERENCES folders(id) ON DELETE CASCADE,
            name        TEXT    NOT NULL,
            sort_order  INTEGER NOT NULL DEFAULT 0,
            created_at  INTEGER NOT NULL,
            updated_at  INTEGER NOT NULL
        )
        """.trimIndent(),

        """
        CREATE TABLE entries (
            id          INTEGER PRIMARY KEY AUTOINCREMENT,
            module_id   INTEGER NOT NULL REFERENCES modules(id) ON DELETE CASCADE,
            folder_id   INTEGER REFERENCES folders(id) ON DELETE SET NULL,
            type        TEXT    NOT NULL,
            title       TEXT    NOT NULL,
            category    TEXT,
            account     TEXT,
            secret_ct   TEXT,
            url         TEXT,
            body        TEXT,
            file_uri    TEXT,
            trash_path  TEXT,
            file_name   TEXT,
            file_size   INTEGER NOT NULL DEFAULT 0,
            mime_type   TEXT,
            is_favorite INTEGER NOT NULL DEFAULT 0,
            deleted_at  INTEGER NOT NULL DEFAULT 0,
            sort_order  INTEGER NOT NULL DEFAULT 0,
            created_at  INTEGER NOT NULL,
            updated_at  INTEGER NOT NULL
        )
        """.trimIndent(),

        """
        CREATE TABLE tags (
            id          INTEGER PRIMARY KEY AUTOINCREMENT,
            module_id   INTEGER NOT NULL REFERENCES modules(id) ON DELETE CASCADE,
            name        TEXT    NOT NULL,
            color       INTEGER NOT NULL DEFAULT 0
        )
        """.trimIndent(),

        """
        CREATE TABLE entry_tags (
            entry_id    INTEGER NOT NULL REFERENCES entries(id) ON DELETE CASCADE,
            tag_id      INTEGER NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
            PRIMARY KEY (entry_id, tag_id)
        )
        """.trimIndent(),

        """
        CREATE TABLE settings (
            key         TEXT PRIMARY KEY,
            value       TEXT
        )
        """.trimIndent(),

        "CREATE UNIQUE INDEX idx_modules_name ON modules(name)",
        "CREATE UNIQUE INDEX idx_tags_module_name ON tags(module_id, name)",
        "CREATE INDEX idx_folders_module_parent ON folders(module_id, parent_id, sort_order)",
        "CREATE INDEX idx_entries_module_folder ON entries(module_id, folder_id, sort_order)",
        "CREATE INDEX idx_entries_module_updated ON entries(module_id, updated_at)",
        "CREATE INDEX idx_entries_module_category ON entries(module_id, category)",
        "CREATE INDEX idx_entry_tags_tag ON entry_tags(tag_id)",
    )
}
