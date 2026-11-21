package com.library.app.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.library.app.data.model.Entry

/** 列表排序方式：手动（按 sort_order）/ 名称 / 时间 / 大小。 */
enum class SortKey(val label: String) {
    MANUAL("手动"),
    NAME("名称"),
    TIME("时间"),
    SIZE("大小"),
}

/**
 * 文件列表的排序偏好，全局一份。
 * 值本身是 Compose 状态，切换立刻生效。
 */
class SortPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("library.sort", Context.MODE_PRIVATE)

    var key by mutableStateOf(
        runCatching { SortKey.valueOf(prefs.getString(KEY_KEY, SortKey.MANUAL.name)!!) }
            .getOrDefault(SortKey.MANUAL),
    )
        private set

    var descending by mutableStateOf(prefs.getBoolean(KEY_DESC, false))
        private set

    fun update(newKey: SortKey, newDescending: Boolean = descending) {
        key = newKey
        descending = newDescending
        prefs.edit()
            .putString(KEY_KEY, newKey.name)
            .putBoolean(KEY_DESC, newDescending)
            .apply()
    }

    fun toggleDirection() = update(key, !descending)

    /** 手动上下移之前先切回手动，否则移完看到的还是原来的排序。 */
    fun switchToManual() {
        if (key != SortKey.MANUAL) update(SortKey.MANUAL, false)
    }

    private companion object {
        const val KEY_KEY = "sort_key"
        const val KEY_DESC = "sort_desc"
    }
}

/**
 * 按当前排序方式排一遍。
 * 手动 = 保持数据库给的 sort_order（收藏的仍在最前，见 EntryDao.ORDER_SQL）。
 */
fun sortEntries(entries: List<Entry>, key: SortKey, descending: Boolean): List<Entry> {
    if (key == SortKey.MANUAL) return entries

    val comparator: Comparator<Entry> = when (key) {
        SortKey.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { entry ->
            entry.title.ifBlank { entry.fileName.orEmpty() }
        }

        SortKey.TIME -> compareBy { entry ->
            if (entry.updatedAt > 0L) entry.updatedAt else entry.createdAt
        }

        SortKey.SIZE -> compareBy { it.fileSize }

        SortKey.MANUAL -> return entries
    }

    val sorted = entries.sortedWith(comparator)
    return if (descending) sorted.reversed() else sorted
}
