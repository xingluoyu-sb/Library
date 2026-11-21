package com.library.app.ui.screens.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.library.app.data.model.Module
import com.library.app.ui.LocalImportRunner
import com.library.app.ui.components.AppIcons
import com.library.app.ui.components.Avatar
import com.library.app.ui.components.PrimaryTopBar
import com.library.app.ui.components.ConfirmDialog
import com.library.app.ui.components.EmptyState
import com.library.app.ui.components.TextInputDialog
import com.library.app.ui.libraryViewModel
import com.library.app.ui.theme.ModulePalette
import kotlinx.coroutines.launch
import com.library.app.i18n.tr

@Composable
fun HomeScreen(
    refreshKey: Int,
    onOpenModule: (Long) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val viewModel = libraryViewModel<HomeViewModel>("home") { HomeViewModel(it) }
    LaunchedEffect(refreshKey) { viewModel.refresh() }

    val runner = LocalImportRunner.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Module?>(null) }
    var deleting by remember { mutableStateOf<Module?>(null) }
    var menuFor by remember { mutableStateOf<Long?>(null) }
    var fabMenu by remember { mutableStateOf(false) }

    val treePicker = rememberLauncherForActivityResult(
        com.library.app.ui.pickers.OpenTreeWithWrite(),
    ) { uri ->
        if (uri != null) {
            val color = ModulePalette[viewModel.modules.size % ModulePalette.size].toArgb()
            viewModel.importFolderTree(runner, uri, color) { message ->
                scope.launch { snackbar.showSnackbar(message) }
            }
        }
    }

    Scaffold(
        topBar = {
            PrimaryTopBar(
                title = "资料库".tr(),
                onAvatarClick = onOpenSettings,
                onSearch = onOpenSearch,
            ) {
                Box {
                    IconButton(onClick = { fabMenu = true }) {
                        Icon(AppIcons.Add, contentDescription = "新建".tr())
                    }
                    DropdownMenu(
                        expanded = fabMenu,
                        onDismissRequest = { fabMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("导入文件夹".tr()) },
                            leadingIcon = { Icon(AppIcons.Folder, contentDescription = null) },
                            onClick = {
                                fabMenu = false
                                treePicker.launch(null)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("新建资料库".tr()) },
                            leadingIcon = { Icon(AppIcons.Add, contentDescription = null) },
                            onClick = {
                                fabMenu = false
                                creating = true
                            },
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        if (viewModel.modules.isEmpty() && !viewModel.loading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(
                    icon = AppIcons.Archive,
                    title = "还没有资料库".tr(),
                    message = "新建一个，或从右上角导入文件夹".tr(),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp),
            ) {
                items(viewModel.modules, key = { it.id }) { module ->
                    ModuleRow(
                        module = module,
                        menuExpanded = menuFor == module.id,
                        onOpen = { onOpenModule(module.id) },
                        onMenu = { menuFor = module.id },
                        onDismissMenu = { menuFor = null },
                        onRename = {
                            menuFor = null
                            renaming = module
                        },
                        onMoveUp = {
                            menuFor = null
                            viewModel.moveModule(module.id, up = true)
                        },
                        onMoveDown = {
                            menuFor = null
                            viewModel.moveModule(module.id, up = false)
                        },
                        onDelete = {
                            menuFor = null
                            deleting = module
                        },
                    )
                }
            }
        }
    }

    if (creating) {
        TextInputDialog(
            title = "新建资料库".tr(),
            label = "资料库名称".tr(),
            confirmText = "创建".tr(),
            onConfirm = { name ->
                val color = ModulePalette[viewModel.modules.size % ModulePalette.size].toArgb()
                viewModel.createModule(name, color)
                creating = false
            },
            onDismiss = { creating = false },
        )
    }

    renaming?.let { module ->
        TextInputDialog(
            title = "重命名资料库".tr(),
            label = "资料库名称".tr(),
            initial = module.name,
            onConfirm = { name ->
                viewModel.renameModule(module.id, name)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }

    deleting?.let { module ->
        ConfirmDialog(
            title = "删除「${module.name}」？".tr(),
            message = "里面的文件夹和 ${module.entryCount} 条资料会一起删掉，不能恢复。".tr(),
            onConfirm = {
                viewModel.deleteModule(module.id)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun ModuleRow(
    module: Module,
    menuExpanded: Boolean,
    onOpen: () -> Unit,
    onMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onRename: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(color = Color(module.color)) {
            Text(
                text = module.name.take(1),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = module.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (module.entryCount == 0) "空资料库".tr() else "${module.entryCount} 条资料".tr(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = onMenu) {
                Icon(
                    AppIcons.More,
                    contentDescription = "更多".tr(),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = onDismissMenu) {
                DropdownMenuItem(
                    text = { Text("重命名".tr()) },
                    onClick = onRename,
                )
                DropdownMenuItem(
                    text = { Text("上移".tr()) },
                    onClick = onMoveUp,
                )
                DropdownMenuItem(
                    text = { Text("下移".tr()) },
                    onClick = onMoveDown,
                )
                DropdownMenuItem(
                    text = { Text("删除".tr(), color = MaterialTheme.colorScheme.error) },
                    onClick = onDelete,
                )
            }
        }
        Spacer(Modifier.width(2.dp))
        Icon(
            AppIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(18.dp),
        )
    }
}
