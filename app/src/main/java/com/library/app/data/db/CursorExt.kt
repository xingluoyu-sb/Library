package com.library.app.data.db

import android.database.Cursor

fun Cursor.stringOrNull(name: String): String? {
    val index = getColumnIndex(name)
    return if (index < 0 || isNull(index)) null else getString(index)
}

fun Cursor.stringOr(name: String, fallback: String = ""): String = stringOrNull(name) ?: fallback

fun Cursor.longOr(name: String, fallback: Long = 0L): Long {
    val index = getColumnIndex(name)
    return if (index < 0 || isNull(index)) fallback else getLong(index)
}

fun Cursor.longOrNull(name: String): Long? {
    val index = getColumnIndex(name)
    return if (index < 0 || isNull(index)) null else getLong(index)
}

fun Cursor.intOr(name: String, fallback: Int = 0): Int {
    val index = getColumnIndex(name)
    return if (index < 0 || isNull(index)) fallback else getInt(index)
}

fun Cursor.boolOr(name: String, fallback: Boolean = false): Boolean {
    val index = getColumnIndex(name)
    return if (index < 0 || isNull(index)) fallback else getInt(index) != 0
}

inline fun <T> Cursor.mapRows(block: (Cursor) -> T): List<T> {
    val result = ArrayList<T>(count)
    use { cursor ->
        while (cursor.moveToNext()) result.add(block(cursor))
    }
    return result
}

/** 转义 LIKE 里的通配符，配合 ESCAPE '\' 使用。 */
fun escapeLike(query: String): String =
    query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
