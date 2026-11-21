@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.library.app.ui.screens.records

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.library.app.data.model.Entry
import com.library.app.data.model.Folder
import com.library.app.ui.components.AppIcons
import com.library.app.ui.components.PrimaryTopBar
import com.library.app.ui.components.ConfirmDialog
import com.library.app.ui.components.EmptyState
import com.library.app.ui.components.RecordBodyPreview
import com.library.app.ui.components.SwipeToDelete
import com.library.app.ui.components.TextInputDialog
import com.library.app.ui.libraryViewModel
import kotlinx.coroutines.launch
import com.library.app.i18n.tr

private const val PREVIEW_ENTRIES = 3

@Composable
fun RecordsScreen(
    refreshKey: Int,
    onOpenGroup: (Long) -> Unit,
    onOpenEntry: (Entry) -> Unit,
    onNewInGroup: (Long) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val viewModel = libraryViewModel<RecordsViewModel>("records") { RecordsViewModel(it) }
    LaunchedEffect(refreshKey) { viewModel.refresh() }

    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Folder?>(null) }
    var deleting by remember { mutableStateOf<Folder?>(null) }

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
            PrimaryTopBar(
                title = "记录".tr(),
                onAvatarClick = onOpenSettings,
                onSearch = onOpenSearch,
            ) {
                IconButton(onClick = { creating = true }) {
                    Icon(AppIcons.Add, contentDescription = "新建分组".tr())
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        if (viewModel.groups.isEmpty() && !viewModel.loading) {
            Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.TopCenter) {
                EmptyState(
                    icon = AppIcons.List,
                    title = "还没有分组".tr(),
                    message = "比如「游戏」「歌单」，点进去就能写".tr(),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(top = 6.dp, bottom = 96.dp),
            ) {
                items(viewModel.groups, key = { it.folder.id }) { block ->
                    GroupCard(
                        block = block,
                        onOpenGroup = { onOpenGroup(block.folder.id) },
                        onOpenEntry = onOpenEntry,
                        onNewInGroup = { onNewInGroup(block.folder.id) },
                        onRename = { renaming = block.folder },
                        onMoveUp = { viewModel.moveGroup(block.folder.id, up = true) },
                        onMoveDown = { viewModel.moveGroup(block.folder.id, up = false) },
                        onDelete = { deleting = block.folder },
                        onDeleteEntry = ::deleteWithUndo,
                    )
                }
            }
        }
    }

    if (creating) {
        TextInputDialog(
            title = "新建分组".tr(),
            label = "分组名称".tr(),
            confirmText = "创建".tr(),
            onConfirm = {
                viewModel.createGroup(it)
                creating = false
            },
            onDismiss = { creating = false },
        )
    }

    renaming?.let { group ->
        TextInputDialog(
            title = "重命名分组".tr(),
            label = "分组名称".tr(),
            initial = group.name,
            onConfirm = {
                viewModel.renameGroup(group.id, it)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }

    deleting?.let { group ->
        ConfirmDialog(
            title = "删除分组「${group.name}」？".tr(),
            message = "子分组会一起删掉，里面的内容会移动到上一级，不会直接消失。".tr(),
            onConfirm = {
                viewModel.deleteGroup(group.id)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun GroupCard(
    block: RecordGroupBlock,
    onOpenGroup: () -> Unit,
    onOpenEntry: (Entry) -> Unit,
    onNewInGroup: () -> Unit,
    onRename: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    onDeleteEntry: (Entry) -> Unit,
) {
    val folder = block.folder
    // 不透明底色：否则左滑的红色背景会从卡片里透出来
    val cardColor = MaterialTheme.colorScheme.surfaceContainerLow
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = cardColor,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Column(modifier = Modifier.clickable(onClick = onOpenGroup)) {
            // 整张卡片都能进分组：只有记录行和「在这里新建」会各自消费点击
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        AppIcons.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(17.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = folder.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (block.entries.isEmpty()) {
                            "空分组".tr()
                        } else {
                            "${block.entries.size} 条内容".tr()
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FolderMenu(
                    onRename = onRename,
                    onMoveUp = onMoveUp,
                    onMoveDown = onMoveDown,
                    onDelete = onDelete,
                )
                Icon(
                    AppIcons.ChevronRight,
                    contentDescription = "查看全部".tr(),
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(17.dp),
                )
                Spacer(Modifier.width(6.dp))
            }

            if (block.entries.isNotEmpty()) {
                block.entries.take(PREVIEW_ENTRIES).forEach { entry ->
                    SwipeToDelete(
                        onDelete = { onDeleteEntry(entry) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Box(Modifier.fillMaxWidth().background(cardColor)) {
                            RecordLine(entry = entry, onClick = { onOpenEntry(entry) })
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenGroup)
                        .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (block.entries.size > PREVIEW_ENTRIES) {
                            "查看全部 ${block.entries.size} 条".tr()
                        } else {
                            "进入分组".tr()
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        AppIcons.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp),
                    )
                }
            } else {
                Text(
                    text = "这个分组还没有内容".tr(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            TextButton(
                onClick = onNewInGroup,
                modifier = Modifier.padding(start = 6.dp, bottom = 2.dp),
            ) {
                Icon(AppIcons.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("在这里新建".tr())
            }
        }
    }
}

@Composable
private fun RecordLine(
    entry: Entry,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        if (entry.title.isNotBlank()) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
        }
        RecordBodyPreview(body = entry.body, maxLines = 3)
    }
}

@Composable
private fun FolderMenu(
    onRename: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) {
            Icon(
                AppIcons.More,
                contentDescription = "更多".tr(),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("重命名".tr()) },
                onClick = {
                    menu = false
                    onRename()
                },
            )
            DropdownMenuItem(
                text = { Text("上移".tr()) },
                onClick = {
                    menu = false
                    onMoveUp()
                },
            )
            DropdownMenuItem(
                text = { Text("下移".tr()) },
                onClick = {
                    menu = false
                    onMoveDown()
                },
            )
            DropdownMenuItem(
                text = { Text("删除".tr(), color = MaterialTheme.colorScheme.error) },
                onClick = {
                    menu = false
                    onDelete()
                },
            )
        }
    }
}
