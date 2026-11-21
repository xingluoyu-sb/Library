package com.library.app.ui.screens.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.library.app.data.Repository
import com.library.app.data.crypto.SecretResult
import com.library.app.data.model.Entry
import com.library.app.data.model.EntryType
import com.library.app.data.model.Folder
import com.library.app.ui.nav.EditorMode
import kotlinx.coroutines.launch
import com.library.app.i18n.tr

class EditorViewModel(
    private val repository: Repository,
    private val moduleId: Long,
    initialFolderId: Long?,
    private val entryId: Long?,
    val mode: EditorMode,
) : ViewModel() {

    var title by mutableStateOf("")
    var account by mutableStateOf("")
    var url by mutableStateOf("")
    var body by mutableStateOf("")
    var isFavorite by mutableStateOf(false)

    /** 记录的分组（文件夹）。 */
    var folderId by mutableStateOf(initialFolderId)
        private set
    var groups by mutableStateOf<List<Folder>>(emptyList())
        private set

    var secretInput by mutableStateOf("")
        private set
    var secretCt by mutableStateOf<String?>(null)
        private set
    var revealed by mutableStateOf<String?>(null)
        private set

    var saving by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var loaded by mutableStateOf(false)
        private set

    private var currentId: Long? = entryId

    val isNew: Boolean get() = currentId == null
    val hasStoredSecret: Boolean get() = secretCt != null

    init {
        if (mode == EditorMode.RECORD) loadGroups()
        if (entryId != null) load(entryId) else loaded = true
    }

    private fun loadGroups() {
        viewModelScope.launch {
            repository.recordsModuleId()
            groups = repository.listRecordGroups()
            // 新建记录默认落在第一组（默认分组），不再有「未分组」这种游离状态
            if (folderId == null) folderId = groups.firstOrNull()?.id
        }
    }

    private fun load(id: Long) {
        viewModelScope.launch {
            val entry = repository.getEntry(id)
            if (entry == null) {
                message = "这条内容已经不存在了".tr()
                loaded = true
                return@launch
            }
            title = entry.title
            account = entry.account.orEmpty()
            url = entry.url.orEmpty()
            body = entry.body.orEmpty()
            isFavorite = entry.isFavorite
            secretCt = entry.secretCt
            if (mode == EditorMode.RECORD) folderId = entry.folderId
            loaded = true
        }
    }

    fun updateSecretInput(value: String) {
        secretInput = value
    }

    fun replaceSecret() {
        secretCt = null
        secretInput = ""
        revealed = null
    }

    fun selectGroup(id: Long?) {
        folderId = id
    }

    fun createGroup(name: String) {
        viewModelScope.launch {
            val id = repository.createRecordGroup(name)
            groups = repository.listRecordGroups()
            folderId = id
        }
    }

    fun toggleFavorite() {
        isFavorite = !isFavorite
    }

    fun revealSecret(decrypt: suspend (String) -> SecretResult) {
        val ct = secretCt ?: return
        if (revealed != null) return
        viewModelScope.launch {
            when (val result = decrypt(ct)) {
                is SecretResult.Ok -> revealed = result.value
                SecretResult.Cancelled -> message = "已取消验证".tr()
                is SecretResult.Failed -> message = result.message
            }
        }
    }

    fun hideSecret() {
        revealed = null
    }

    fun save(encrypt: suspend (String) -> SecretResult, onSaved: () -> Unit) {
        if (saving) return

        // 密码用账号当标题；记录标题可留空，完全靠正文也能存
        val resolvedTitle = when (mode) {
            EditorMode.PASSWORD -> account.trim().ifBlank { "未命名密码".tr() }
            EditorMode.RECORD -> title.trim()
        }
        if (mode == EditorMode.PASSWORD && resolvedTitle.isBlank()) {
            message = "请先填写账号或平台".tr()
            return
        }
        if (mode == EditorMode.RECORD && title.isBlank() && body.isBlank()) {
            message = "写点什么再保存".tr()
            return
        }

        viewModelScope.launch {
            saving = true
            var ct = secretCt

            if (mode == EditorMode.PASSWORD && secretInput.isNotBlank()) {
                when (val result = encrypt(secretInput)) {
                    is SecretResult.Ok -> {
                        ct = result.value
                        secretCt = result.value
                        secretInput = ""
                    }

                    SecretResult.Cancelled -> {
                        message = "已取消验证，还没有保存".tr()
                        saving = false
                        return@launch
                    }

                    is SecretResult.Failed -> {
                        message = result.message
                        saving = false
                        return@launch
                    }
                }
            }

            val entry = Entry(
                id = currentId ?: 0L,
                moduleId = moduleId,
                folderId = if (mode == EditorMode.RECORD) folderId else null,
                type = if (mode == EditorMode.PASSWORD) EntryType.PASSWORD else EntryType.RECORD,
                title = resolvedTitle,
                account = account.trim().ifBlank { null },
                secretCt = ct,
                url = url.trim().ifBlank { null },
                body = body.ifBlank { null },
                isFavorite = isFavorite,
            )
            currentId = repository.saveEntry(entry)

            saving = false
            onSaved()
        }
    }

    fun delete(onDeleted: () -> Unit) {
        val id = currentId ?: return
        viewModelScope.launch {
            repository.deleteEntry(id)
            onDeleted()
        }
    }

    fun consumeMessage() {
        message = null
    }
}
