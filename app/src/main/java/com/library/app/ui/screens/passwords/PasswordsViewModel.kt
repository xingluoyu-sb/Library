package com.library.app.ui.screens.passwords

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.library.app.data.Repository
import com.library.app.data.crypto.SecretResult
import com.library.app.data.model.Entry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PasswordsViewModel(private val repository: Repository) : ViewModel() {

    var entries by mutableStateOf<List<Entry>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set

    /** entryId → 明文密码，只在内存里待着，不落库。 */
    var plaintext by mutableStateOf<Map<Long, String>>(emptyMap())
        private set

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            loading = true
            entries = repository.listPasswords()
            loading = false
        }
    }

    /**
     * 批量解出明文。
     *
     * @return 第一条失败原因；全部成功返回 null。列表这里原来是把失败当「没有密码」静默丢掉，
     * 密钥失效时用户只会看到一片空白，连「去恢复备份」都读不到。
     */
    suspend fun loadPlaintext(decrypt: (String) -> SecretResult): String? {
        val source = entries
        var reason: String? = null
        val map = withContext(Dispatchers.Default) {
            source.mapNotNull { entry ->
                val ct = entry.secretCt ?: return@mapNotNull null
                when (val result = decrypt(ct)) {
                    is SecretResult.Ok -> entry.id to result.value
                    is SecretResult.Failed -> {
                        if (reason == null) reason = result.message
                        null
                    }

                    SecretResult.Cancelled -> null
                }
            }.toMap()
        }
        plaintext = map
        return reason
    }

    fun clearPlaintext() {
        plaintext = emptyMap()
    }

    fun toggleFavorite(entry: Entry) {
        viewModelScope.launch {
            repository.setFavorite(entry, !entry.isFavorite)
            entries = repository.listPasswords()
        }
    }
}
