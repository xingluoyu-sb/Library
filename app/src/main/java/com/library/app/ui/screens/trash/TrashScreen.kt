@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.library.app.ui.screens.trash

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.library.app.data.files.RestoreResult
import com.library.app.data.model.Entry
import com.library.app.ui.LocalRepository
import com.library.app.ui.components.AppIcons
import com.library.app.ui.components.ConfirmDialog
import com.library.app.ui.components.EmptyState
import com.library.app.ui.components.EntryListRow
import com.library.app.ui.components.LibraryTopBar
import com.library.app.ui.components.relativeTime
import kotlinx.coroutines.launch
import com.library.app.i18n.tr

/**
 * 回收站。删除先落这里，30 天后才真删（启动时清理）。
 */
@Composable
fun TrashScreen(onBack: () -> Unit) {
    val repository = LocalRepository.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var entries by remember { mutableStateOf<List<Entry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var showEmpty by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch {
            entries = repository.listTrash()
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    Scaffold(
        topBar = {
            LibraryTopBar(
                title = "回收站".tr(),
                subtitle = if (entries.isEmpty()) "空的".tr() else "${entries.size} 条 · 保留 30 天".tr(),
                onBack = onBack,
                actions = {
                    if (entries.isNotEmpty()) {
                        TextButton(onClick = { showEmpty = true }) { Text("清空".tr()) }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        if (entries.isEmpty() && !loading) {
            Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.TopCenter) {
                EmptyState(
                    icon = AppIcons.Delete,
                    title = "回收站是空的".tr(),
                    message = "删掉的内容会先放这里，30 天内都能恢复".tr(),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(entries, key = { it.id }) { entry ->
                    EntryListRow(
                        entry = entry,
                        onClick = {},
                        trailingLabel = "${relativeTime(entry.deletedAt)}删除".tr(),
                        trailing = {
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        val result = repository.restoreFromTrash(entry)
                                        reload()
                                        snackbar.showSnackbar(restoreMessage(result))
                                    }
                                },
                                modifier = Modifier.size(40.dp),
                            ) {
                                Icon(
                                    AppIcons.Restore,
                                    contentDescription = "恢复".tr(),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        repository.deleteEntryForever(entry)
                                        reload()
                                        snackbar.showSnackbar("已永久删除".tr())
                                    }
                                },
                                modifier = Modifier.size(40.dp),
                            ) {
                                Icon(
                                    AppIcons.Delete,
                                    contentDescription = "永久删除".tr(),
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        },
                    )
                }
            }
        }
    }

    if (showEmpty) {
        ConfirmDialog(
            title = "清空回收站？".tr(),
            message = "里面的文件和记录会被永久删除，不能恢复。".tr(),
            confirmText = "清空".tr(),
            onConfirm = {
                showEmpty = false
                scope.launch {
                    repository.emptyTrash()
                    reload()
                }
            },
            onDismiss = { showEmpty = false },
        )
    }
}

/** 恢复结果文案：换位置了要说清楚文件去哪了。 */
private fun restoreMessage(result: RestoreResult): String = when (result) {
    is RestoreResult.Done ->
        if (result.moved) "已恢复，原目录写不进去，文件放到了 文档/library".tr() else "已恢复".tr()

    RestoreResult.NoCopy -> "已恢复条目，但没有留下文件副本".tr()
    RestoreResult.Failed -> "恢复失败：文件副本丢了或原目录写不进去，条目还留在回收站".tr()
}
