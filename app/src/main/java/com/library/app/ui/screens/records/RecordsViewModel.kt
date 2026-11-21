package com.library.app.ui.screens.records

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.library.app.data.Repository
import com.library.app.data.model.Entry
import com.library.app.data.model.Folder
import kotlinx.coroutines.launch

/** 一个分组 + 它下面的内容，记录首页直接按这个结构展示。 */
data class RecordGroupBlock(
    val folder: Folder,
    val entries: List<Entry>,
)

class RecordsViewModel(private val repository: Repository) : ViewModel() {

    var groups by mutableStateOf<List<RecordGroupBlock>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            loading = true
            // listRecordGroups 会确保「默认分组」存在，并把未归组的记录收进去
            val folders = repository.listRecordGroups()
            groups = folders.map { RecordGroupBlock(it, repository.listRecords(it.id)) }
            loading = false
        }
    }

    fun createGroup(name: String) {
        viewModelScope.launch {
            repository.createRecordGroup(name)
            refresh()
        }
    }

    fun renameGroup(id: Long, name: String) {
        viewModelScope.launch {
            repository.renameFolder(id, name)
            refresh()
        }
    }

    fun deleteGroup(id: Long) {
        viewModelScope.launch {
            repository.deleteFolder(id)
            refresh()
        }
    }

    /** 左滑删除一条记录：进回收站，撤销就是把它从回收站捞回来。 */
    fun deleteEntry(entry: Entry) {
        viewModelScope.launch {
            repository.deleteEntry(entry.id)
            refresh()
        }
    }

    fun restoreEntry(entry: Entry) {
        viewModelScope.launch {
            repository.restoreEntry(entry.id)
            refresh()
        }
    }

    fun moveGroup(id: Long, up: Boolean) {
        viewModelScope.launch {
            groups.firstOrNull { it.folder.id == id }?.let { block ->
                repository.moveFolder(block.folder.moduleId, null, id, up)
                refresh()
            }
        }
    }
}

