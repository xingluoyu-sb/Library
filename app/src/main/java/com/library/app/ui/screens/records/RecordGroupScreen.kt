@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.library.app.ui.screens.records

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.library.app.data.Repository
import com.library.app.data.model.Entry
import com.library.app.ui.components.AppIcons
import com.library.app.ui.components.EmptyState
import com.library.app.ui.components.LibraryTopBar
import com.library.app.ui.components.MarkdownText
import com.library.app.ui.components.SwipeToDelete
import com.library.app.ui.libraryViewModel
import kotlinx.coroutines.launch
import com.library.app.i18n.tr

class RecordGroupViewModel(
    private val repository: Repository,
    private val folderId: Long,
) : ViewModel() {

    var title by mutableStateOf("")
        private set
    var entries by mutableStateOf<List<Entry>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            loading = true
            title = repository.folderPath(folderId).lastOrNull()?.name ?: "分组".tr()
            entries = repository.listRecords(folderId)
            loading = false
        }
    }

    fun deleteEntry(entry: Entry) {
        viewModelScope.launch {
            repository.deleteEntry(entry.id)
            refresh()
        }
    }

    fun restoreEntry(entry: Entry) {
        viewModelScope.launch {
            repository.saveEntry(entry.copy(id = 0L))
            refresh()
        }
    }
}

@Composable
fun RecordGroupScreen(
    folderId: Long,
    refreshKey: Int,
    onOpenEntry: (Entry) -> Unit,
    onNewRecord: () -> Unit,
    onBack: () -> Unit,
) {
    val viewModel = libraryViewModel<RecordGroupViewModel>("record-group-$folderId") {
        RecordGroupViewModel(it, folderId)
    }
    LaunchedEffect(refreshKey) { viewModel.refresh() }

    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    fun deleteWithUndo(entry: Entry) {
        viewModel.deleteEntry(entry)
        scope.launch {
            val result = snackbar.showSnackbar(
                message = "已删除「${entry.displayTitle.ifBlank { "这条记录".tr() }}」".tr(),
                actionLabel = "撤销".tr(),
                withDismissAction = true,
            )
            if (result == SnackbarResult.ActionPerformed) viewModel.restoreEntry(entry)
        }
    }

    Scaffold(
        topBar = {
            LibraryTopBar(
                title = viewModel.title,
                subtitle = if (viewModel.entries.isEmpty()) "空分组".tr() else "${viewModel.entries.size} 条内容".tr(),
                onBack = onBack,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewRecord,
                icon = { Icon(AppIcons.Add, contentDescription = null) },
                text = { Text("在这里新建".tr()) },
            )
        },
    ) { innerPadding ->
        if (viewModel.entries.isEmpty() && !viewModel.loading) {
            Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.TopCenter) {
                EmptyState(
                    icon = AppIcons.List,
                    title = "这个分组还是空的".tr(),
                    message = "点右下角新建".tr(),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp),
            ) {
                items(viewModel.entries, key = { it.id }) { entry ->
                    SwipeToDelete(
                        onDelete = { deleteWithUndo(entry) },
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 5.dp),
                    ) {
                    Surface(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenEntry(entry) }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            if (entry.title.isNotBlank()) {
                                Text(
                                    text = entry.title,
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Spacer(Modifier.height(4.dp))
                            }
                            if (entry.body.isNullOrBlank()) {
                                Text(
                                    text = "还没有内容".tr(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            } else {
                                MarkdownText(text = entry.body!!)
                            }
                            if (entry.updatedAt > 0L) {
                                Spacer(Modifier.height(6.dp))
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = com.library.app.ui.components.relativeTime(entry.updatedAt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                    }
                    }
                }
            }
        }
    }
}
