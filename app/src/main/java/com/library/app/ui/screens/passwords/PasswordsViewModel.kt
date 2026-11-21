package com.library.app.ui.screens.passwords

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.library.app.data.Repository
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

    fun loadPlaintext(resolve: (String) -> String?) {
        val source = entries
        viewModelScope.launch {
            val map = withContext(Dispatchers.Default) {
                source.mapNotNull { entry ->
                    entry.secretCt?.let { ct -> resolve(ct)?.let { value -> entry.id to value } }
                }.toMap()
            }
            plaintext = map
        }
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
