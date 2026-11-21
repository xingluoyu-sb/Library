package com.library.app.ui.screens.home

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.library.app.data.Repository
import com.library.app.data.files.ImportRunner
import com.library.app.data.model.Module
import kotlinx.coroutines.launch

class HomeViewModel(private val repository: Repository) : ViewModel() {

    var modules by mutableStateOf<List<Module>>(emptyList())
        private set

    var loading by mutableStateOf(true)
        private set

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            loading = true
            modules = repository.listModules()
            loading = false
        }
    }

    fun createModule(name: String, color: Int, onCreated: (Long) -> Unit = {}) {
        viewModelScope.launch {
            val id = repository.createModule(name, color)
            modules = repository.listModules()
            onCreated(id)
        }
    }

    fun renameModule(id: Long, name: String) {
        viewModelScope.launch {
            repository.renameModule(id, name)
            modules = repository.listModules()
        }
    }

    fun deleteModule(id: Long) {
        viewModelScope.launch {
            repository.deleteModule(id)
            modules = repository.listModules()
        }
    }

    /**
     * 选中的文件夹整体归纳成一个资料库，子目录按原层级变成文件夹；
     * 只记录文件位置，不复制实体。结果用 Snackbar 报出来。
     *
     * 真正干活的是 ImportRunner（进程级作用域），所以离开首页也不会中断。
     */
    fun importFolderTree(
        runner: ImportRunner,
        treeUri: Uri,
        color: Int,
        onResult: (String) -> Unit,
    ) {
        runner.importAsModule(treeUri, color) { outcome ->
            viewModelScope.launch { modules = repository.listModules() }
            onResult(
                if (outcome is ImportRunner.Outcome.Done) {
                    "「${outcome.result.moduleName}」${ImportRunner.describe(outcome)}"
                } else {
                    ImportRunner.describe(outcome)
                },
            )
        }
    }

    fun moveModule(id: Long, up: Boolean) {
        viewModelScope.launch {
            repository.moveModule(id, up)
            modules = repository.listModules()
        }
    }

    fun colorForNextModule(index: Int): Int = index
}
