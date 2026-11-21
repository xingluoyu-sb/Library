@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package com.library.app.ui.screens.module

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.library.app.data.SortKey
import com.library.app.data.TrashOutcome
import com.library.app.data.files.PreviewKind
import com.library.app.data.files.fileExists
import com.library.app.data.files.previewKind
import com.library.app.data.model.Entry
import com.library.app.data.model.EntryType
import com.library.app.data.model.Folder
import com.library.app.data.sortEntries
import com.library.app.ui.LocalFileStore
import com.library.app.ui.LocalImportRunner
import com.library.app.ui.LocalSortPrefs
import com.library.app.ui.components.AppIcons
import com.library.app.ui.components.ConfirmDialog
import com.library.app.ui.components.DeleteChoiceDialog
import com.library.app.ui.components.EmptyState
import com.library.app.ui.components.EntryListRow
import com.library.app.ui.components.LibraryTopBar
import com.library.app.ui.components.TextInputDialog
import com.library.app.ui.libraryViewModel
import com.library.app.ui.pickers.OpenDocumentWithWrite
import com.library.app.ui.pickers.OpenDocumentsWithWrite
import com.library.app.ui.pickers.OpenTreeWithWrite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.library.app.i18n.tr

@Composable
fun ModuleScreen(
    moduleId: Long,
    folderId: Long?,
    refreshKey: Int,
    onOpenFolder: (Long?) -> Unit,
    onOpenEntry: (Entry) -> Unit,
    onBack: () -> Unit,
) {
    val viewModel = libraryViewModel<ModuleViewModel>("module-$moduleId-$folderId") {
        ModuleViewModel(it, moduleId, folderId)
    }
    LaunchedEffect(refreshKey) { viewModel.refresh() }

    val context = LocalContext.current
    val fileStore = LocalFileStore.current
    val runner = LocalImportRunner.current
    val sortPrefs = LocalSortPrefs.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val shownEntries = remember(viewModel.entries, sortPrefs.key, sortPrefs.descending) {
        sortEntries(viewModel.entries, sortPrefs.key, sortPrefs.descending)
    }

    // 进页面静默同步一次源文件夹；同步跑在后台，退出页面不会中断
    LaunchedEffect(viewModel.sourceTreeUri) { viewModel.autoSync(runner) }

    // 源文件被移走/删掉的条目：标出来，并给「重新定位」的入口
    var missing by remember { mutableStateOf<Set<Long>>(emptySet()) }
    LaunchedEffect(viewModel.entries) {
        val files = viewModel.entries.filter { it.type == EntryType.FILE }
        missing = withContext(Dispatchers.IO) {
            files.filterNot { entry -> entry.fileUri?.let { fileExists(context, it) } == true }
                .map { it.id }
                .toSet()
        }
    }

    var menuOpen by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    var creatingFolder by remember { mutableStateOf(false) }
    var renamingFolder by remember { mutableStateOf(false) }
    var deletingFolder by remember { mutableStateOf(false) }
    var renamingFolderAt by remember { mutableStateOf<Folder?>(null) }
    var deletingFolderAt by remember { mutableStateOf<Folder?>(null) }
    var deletingFile by remember { mutableStateOf<Entry?>(null) }
    var relocating by remember { mutableStateOf<Entry?>(null) }
    var confirmingBatch by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var reorderMode by remember { mutableStateOf(false) }

    val selecting = selection.isNotEmpty()

    val picker = rememberLauncherForActivityResult(OpenDocumentsWithWrite()) { uris ->
        if (uris.isNotEmpty()) viewModel.importFiles(uris, fileStore)
    }

    val folderPicker = rememberLauncherForActivityResult(OpenTreeWithWrite()) { uri ->
        if (uri != null) {
            viewModel.importFolderTree(runner, uri) { message ->
                scope.launch { snackbar.showSnackbar(message) }
            }
        }
    }

    val relocatePicker = rememberLauncherForActivityResult(OpenDocumentWithWrite()) { uri ->
        val target = relocating
        relocating = null
        if (uri != null && target != null) {
            viewModel.relocate(target, uri, fileStore)
            scope.launch { snackbar.showSnackbar("已重新定位".tr()) }
        }
    }

    fun openFile(entry: Entry) {
        val uri = entry.fileUri
        if (uri == null || !fileExists(context, uri)) {
            scope.launch { snackbar.showSnackbar("文件不在原来的位置了，点右侧 ⋮ 可以重新定位".tr()) }
            return
        }
        onOpenEntry(entry)
    }

    /** 进入拖动排序：先把当前显示顺序固化成手动顺序，视觉上不会跳。 */
    fun startReorder() {
        val ids = shownEntries.map { it.id }
        viewModel.applyOrder(ids)
        sortPrefs.switchToManual()
        reorderMode = true
    }

    fun deleteOneFile(entry: Entry, mode: DeleteMode) {
        viewModel.deleteFileEntry(entry, mode) { outcome ->
            scope.launch { snackbar.showSnackbar(trashMessage(outcome)) }
        }
    }

    Scaffold(
        topBar = {
            LibraryTopBar(
                title = when {
                    selecting -> "已选 ${selection.size} 项".tr()
                    reorderMode -> "拖动排序".tr()
                    else -> viewModel.moduleName
                },
                subtitle = when {
                    selecting -> null
                    reorderMode -> "按住右侧 ☰ 上下拖动".tr()
                    viewModel.entries.isEmpty() && viewModel.folders.isEmpty() -> null
                    else -> "${viewModel.folders.size} 个文件夹 · ${viewModel.entries.size} 项".tr()
                },
                onBack = when {
                    selecting -> ({ selection = emptySet() })
                    reorderMode -> ({ reorderMode = false })
                    else -> onBack
                },
                actions = {
                    when {
                        selecting -> {
                            IconButton(onClick = { confirmingBatch = true }) {
                                Icon(
                                    AppIcons.Delete,
                                    contentDescription = "删除选中".tr(),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }

                        reorderMode -> {
                            TextButton(onClick = { reorderMode = false }) { Text("完成".tr()) }
                        }

                        else -> {
                            Box {
                                IconButton(onClick = { sortOpen = true }) {
                                    Icon(AppIcons.ArrowDown, contentDescription = "排序".tr())
                                }
                                DropdownMenu(
                                    expanded = sortOpen,
                                    onDismissRequest = { sortOpen = false },
                                ) {
                                    SortKey.entries
                                        .filter { it != SortKey.MANUAL }
                                        .forEach { option ->
                                            DropdownMenuItem(
                                                text = { Text(option.label) },
                                                leadingIcon = {
                                                    if (sortPrefs.key == option) {
                                                        Icon(
                                                            AppIcons.Check,
                                                            contentDescription = null,
                                                            modifier = Modifier.size(16.dp),
                                                        )
                                                    }
                                                },
                                                onClick = {
                                                    sortPrefs.update(option)
                                                    sortOpen = false
                                                },
                                            )
                                        }
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = {
                                            Text(if (sortPrefs.descending) "改为正序".tr() else "改为倒序".tr())
                                        },
                                        trailingIcon = {
                                            Text(
                                                text = if (sortPrefs.descending) "当前倒序".tr() else "当前正序".tr(),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        },
                                        onClick = {
                                            sortPrefs.toggleDirection()
                                            sortOpen = false
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("拖动调整顺序".tr()) },
                                        onClick = {
                                            sortOpen = false
                                            startReorder()
                                        },
                                    )
                                }
                            }
                            Box {
                                IconButton(onClick = { menuOpen = true }) {
                                    Icon(AppIcons.More, contentDescription = "更多".tr())
                                }
                                DropdownMenu(
                                    expanded = menuOpen,
                                    onDismissRequest = { menuOpen = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("新建文件夹".tr()) },
                                        onClick = {
                                            menuOpen = false
                                            creatingFolder = true
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("导入文件夹（保留层级）".tr()) },
                                        onClick = {
                                            menuOpen = false
                                            folderPicker.launch(null)
                                        },
                                    )
                                    if (viewModel.sourceTreeUri != null) {
                                        DropdownMenuItem(
                                            text = { Text("同步源文件夹".tr()) },
                                            onClick = {
                                                menuOpen = false
                                                viewModel.syncSource(runner) { message ->
                                                    scope.launch { snackbar.showSnackbar(message) }
                                                }
                                            },
                                        )
                                    }
                                    if (folderId != null) {
                                        DropdownMenuItem(
                                            text = { Text("重命名当前文件夹".tr()) },
                                            onClick = {
                                                menuOpen = false
                                                renamingFolder = true
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    "删除当前文件夹".tr(),
                                                    color = MaterialTheme.colorScheme.error,
                                                )
                                            },
                                            onClick = {
                                                menuOpen = false
                                                deletingFolder = true
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (!selecting && !reorderMode) {
                ExtendedFloatingActionButton(
                    onClick = { picker.launch(arrayOf("*/*")) },
                    icon = { Icon(AppIcons.Add, contentDescription = null) },
                    text = { Text("导入文件".tr()) },
                )
            }
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            Breadcrumb(
                moduleName = viewModel.moduleName,
                path = viewModel.breadcrumb,
                onRoot = { onOpenFolder(null) },
                onFolder = { onOpenFolder(it) },
            )

            if (viewModel.folders.isEmpty() && viewModel.entries.isEmpty() && !viewModel.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    EmptyState(
                        icon = AppIcons.Archive,
                        title = "这里还是空的".tr(),
                        message = "导入文件或文件夹开始整理".tr(),
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 96.dp),
                ) {
                    if (viewModel.folders.isNotEmpty()) {
                        item { SectionHeader("文件夹".tr()) }
                        items(viewModel.folders, key = { "f-${it.id}" }) { folder ->
                            FolderRow(
                                folder = folder,
                                onClick = { onOpenFolder(folder.id) },
                                onRename = { renamingFolderAt = folder },
                                onMoveUp = { viewModel.moveFolder(folder.id, up = true) },
                                onMoveDown = { viewModel.moveFolder(folder.id, up = false) },
                                onDelete = { deletingFolderAt = folder },
                            )
                        }
                    }
                    if (shownEntries.isNotEmpty()) {
                        item { SectionHeader("内容".tr()) }
                        items(shownEntries, key = { "e-${it.id}" }) { entry ->
                            val isSelected = entry.id in selection
                            EntryListRow(
                                entry = entry,
                                selected = isSelected,
                                selectable = selecting,
                                onLongClick = {
                                    if (!reorderMode) {
                                        selection = selection + entry.id
                                    }
                                },
                                onClick = {
                                    when {
                                        reorderMode -> Unit
                                        selecting -> selection = if (isSelected) {
                                            selection - entry.id
                                        } else {
                                            selection + entry.id
                                        }

                                        entry.type == EntryType.FILE -> openFile(entry)
                                        else -> onOpenEntry(entry)
                                    }
                                },
                                onFavorite = if (selecting || reorderMode || entry.type == EntryType.FILE) {
                                    null
                                } else {
                                    { viewModel.toggleFavorite(entry) }
                                },
                                warning = if (entry.id in missing) "文件不在原来的位置了".tr() else null,
                                thumbUri = entry.fileUri?.takeIf {
                                    entry.type == EntryType.FILE &&
                                        previewKind(entry.mimeType, entry.fileName) in
                                        setOf(PreviewKind.IMAGE, PreviewKind.VIDEO)
                                },
                                trailing = when {
                                    reorderMode -> {
                                        {
                                            DragHandle(
                                                onMove = { up -> viewModel.moveEntry(entry.id, up) },
                                            )
                                        }
                                    }

                                    selecting -> null

                                    entry.type == EntryType.FILE -> {
                                        {
                                            FileMenu(
                                                missing = entry.id in missing,
                                                onRelocate = {
                                                    relocating = entry
                                                    relocatePicker.launch(arrayOf("*/*"))
                                                },
                                                onDelete = { deletingFile = entry },
                                            )
                                        }
                                    }

                                    else -> {
                                        {
                                            OrderMenu(
                                                onMoveUp = {
                                                    sortPrefs.switchToManual()
                                                    viewModel.moveEntry(entry.id, up = true)
                                                },
                                                onMoveDown = {
                                                    sortPrefs.switchToManual()
                                                    viewModel.moveEntry(entry.id, up = false)
                                                },
                                            )
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (creatingFolder) {
        TextInputDialog(
            title = "新建文件夹".tr(),
            label = "文件夹名称".tr(),
            confirmText = "创建".tr(),
            onConfirm = {
                viewModel.createFolder(it)
                creatingFolder = false
            },
            onDismiss = { creatingFolder = false },
        )
    }

    if (renamingFolder) {
        TextInputDialog(
            title = "重命名文件夹".tr(),
            label = "文件夹名称".tr(),
            initial = viewModel.breadcrumb.lastOrNull()?.name.orEmpty(),
            onConfirm = {
                viewModel.renameCurrentFolder(it)
                renamingFolder = false
            },
            onDismiss = { renamingFolder = false },
        )
    }

    renamingFolderAt?.let { folder ->
        TextInputDialog(
            title = "重命名文件夹".tr(),
            label = "文件夹名称".tr(),
            initial = folder.name,
            onConfirm = { name ->
                viewModel.renameFolder(folder.id, name)
                renamingFolderAt = null
            },
            onDismiss = { renamingFolderAt = null },
        )
    }

    if (deletingFolder) {
        ConfirmDialog(
            title = "删除当前文件夹？".tr(),
            message = "子文件夹会一起删掉，里面的内容会移动到上一级。".tr(),
            onConfirm = {
                viewModel.deleteCurrentFolder()
                deletingFolder = false
                onOpenFolder(viewModel.breadcrumb.dropLast(1).lastOrNull()?.id)
            },
            onDismiss = { deletingFolder = false },
        )
    }

    deletingFolderAt?.let { folder ->
        ConfirmDialog(
            title = "删除「${folder.name}」？".tr(),
            message = "子文件夹会一起删掉，里面的内容会移动到上一级。".tr(),
            onConfirm = {
                viewModel.deleteFolder(folder.id)
                deletingFolderAt = null
            },
            onDismiss = { deletingFolderAt = null },
        )
    }

    deletingFile?.let { entry ->
        DeleteChoiceDialog(
            title = "删除这个文件？".tr(),
            name = entry.title,
            onMoveOut = {
                deleteOneFile(entry, DeleteMode.MOVE_OUT)
                deletingFile = null
            },
            onDelete = {
                deleteOneFile(entry, DeleteMode.DELETE)
                deletingFile = null
            },
            onDismiss = { deletingFile = null },
        )
    }

    if (confirmingBatch) {
        val targets = shownEntries.filter { it.id in selection }
        DeleteChoiceDialog(
            title = "删除选中的 ${targets.size} 项？".tr(),
            onMoveOut = {
                confirmingBatch = false
                selection = emptySet()
                viewModel.deleteEntries(targets, DeleteMode.MOVE_OUT) { outcomes ->
                    scope.launch { snackbar.showSnackbar(batchTrashMessage(outcomes)) }
                }
            },
            onDelete = {
                confirmingBatch = false
                selection = emptySet()
                viewModel.deleteEntries(targets, DeleteMode.DELETE) { outcomes ->
                    scope.launch { snackbar.showSnackbar(batchTrashMessage(outcomes)) }
                }
            },
            onDismiss = { confirmingBatch = false },
        )
    }
}

/** 拖动排序用的把手：上下拖超过半行就换一次位置。 */
@Composable
private fun DragHandle(onMove: (Boolean) -> Unit) {
    val threshold = with(LocalDensity.current) { 40.dp.toPx() }
    var accumulated by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .size(44.dp)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = { accumulated = 0f },
                    onDragCancel = { accumulated = 0f },
                ) { change, dragAmount ->
                    change.consume()
                    accumulated += dragAmount
                    if (accumulated > threshold) {
                        onMove(false)
                        accumulated = 0f
                    } else if (accumulated < -threshold) {
                        onMove(true)
                        accumulated = 0f
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            AppIcons.List,
            contentDescription = "拖动排序".tr(),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun FileMenu(
    missing: Boolean,
    onRelocate: () -> Unit,
    onDelete: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(40.dp)) {
            Icon(
                AppIcons.More,
                contentDescription = "更多".tr(),
                tint = if (missing) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (missing) {
                DropdownMenuItem(
                    text = { Text("重新定位到新文件".tr()) },
                    onClick = {
                        open = false
                        onRelocate()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("删除".tr(), color = MaterialTheme.colorScheme.error) },
                onClick = {
                    open = false
                    onDelete()
                },
            )
        }
    }
}

/** 单条删除的提示：说清楚东西去哪了、能不能恢复。 */
private fun trashMessage(outcome: TrashOutcome): String = when (outcome) {
    TrashOutcome.MOVED_OUT -> "已移出资料库".tr()
    TrashOutcome.TRASHED -> "已移入回收站，30 天内可以恢复".tr()
    TrashOutcome.PARTIAL -> "已移入回收站；系统不允许删原位置的文件".tr()
    TrashOutcome.COPY_FAILED -> "已移入回收站，但没读到文件内容（原文件没动）".tr()
    TrashOutcome.RECORD -> "已移入回收站，30 天内可以恢复".tr()
}

/** 批量删除的汇总提示。 */
private fun batchTrashMessage(outcomes: List<TrashOutcome>): String = buildString {
    val movedOut = outcomes.count { it == TrashOutcome.MOVED_OUT }
    if (movedOut == outcomes.size) {
        append("已移出资料库 ${outcomes.size} 项".tr())
        return@buildString
    }
    append("已移入回收站 ${outcomes.size} 项".tr())
    val partial = outcomes.count { it == TrashOutcome.PARTIAL }
    val noCopy = outcomes.count { it == TrashOutcome.COPY_FAILED }
    if (partial > 0) append("；$partial 个原文件系统不允许删除".tr())
    if (noCopy > 0) append("；$noCopy 个没读到内容，原文件还在".tr())
}

@Composable
private fun OrderMenu(onMoveUp: () -> Unit, onMoveDown: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(40.dp)) {
            Icon(
                AppIcons.More,
                contentDescription = "排序".tr(),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("上移".tr()) },
                onClick = {
                    open = false
                    onMoveUp()
                },
            )
            DropdownMenuItem(
                text = { Text("下移".tr()) },
                onClick = {
                    open = false
                    onMoveDown()
                },
            )
        }
    }
}

@Composable
private fun Breadcrumb(
    moduleName: String,
    path: List<Folder>,
    onRoot: () -> Unit,
    onFolder: (Long) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Crumb(text = moduleName, enabled = path.isNotEmpty(), onClick = onRoot)
        path.forEachIndexed { index, folder ->
            Icon(
                AppIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(14.dp),
            )
            Crumb(
                text = folder.name,
                enabled = index != path.lastIndex,
                onClick = { onFolder(folder.id) },
            )
        }
    }
}

@Composable
private fun Crumb(text: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = if (enabled) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
    )
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp),
    )
}

@Composable
private fun FolderRow(
    folder: Folder,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 20.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                AppIcons.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(19.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Text(
            text = folder.name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = if (folder.entryCount > 0) "${folder.entryCount} 项".tr() else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(40.dp)) {
                Icon(
                    AppIcons.More,
                    contentDescription = "文件夹菜单".tr(),
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
}
