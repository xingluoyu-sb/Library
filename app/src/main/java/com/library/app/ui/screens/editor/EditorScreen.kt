@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.library.app.ui.screens.editor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.library.app.data.crypto.SecretResult
import com.library.app.data.crypto.AuthOutcome
import com.library.app.data.crypto.BiometricGate
import com.library.app.ui.LocalSecretCrypto
import com.library.app.ui.LocalSecurityPrefs
import com.library.app.ui.LocalSession
import com.library.app.ui.screens.lock.findActivity
import kotlinx.coroutines.launch
import com.library.app.ui.components.AppIcons
import com.library.app.ui.components.ConfirmDialog
import com.library.app.ui.components.LibraryTopBar
import com.library.app.ui.components.TextInputDialog
import com.library.app.ui.libraryViewModel
import com.library.app.ui.nav.EditorMode
import com.library.app.i18n.tr

@Composable
fun EditorScreen(
    moduleId: Long,
    folderId: Long?,
    entryId: Long?,
    mode: EditorMode,
    readOnly: Boolean,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val viewModel = libraryViewModel<EditorViewModel>(
        key = "editor-$moduleId-${folderId ?: 0}-${entryId ?: 0}-${mode.name}",
    ) {
        EditorViewModel(it, moduleId, folderId, entryId, mode)
    }

    val crypto = LocalSecretCrypto.current
    val snackbar = remember { SnackbarHostState() }

    var showDelete by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var secretVisible by remember { mutableStateOf(false) }
    var showNewGroup by remember { mutableStateOf(false) }
    var groupMenuOpen by remember { mutableStateOf(false) }

    // 进入应用时已经验过身份，保存不再二次验证
    val encrypt: suspend (String) -> SecretResult = { plain -> crypto.encryptNow(plain) }

    LaunchedEffect(viewModel.message) {
        viewModel.message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    val groupName = viewModel.folderId?.let { id ->
        viewModel.groups.firstOrNull { it.id == id }?.name
    } ?: "默认分组".tr()

    Scaffold(
        topBar = {
            LibraryTopBar(
                title = if (viewModel.isNew) "新建".tr() else "编辑".tr(),
                subtitle = if (mode == EditorMode.PASSWORD) "密码".tr() else "记录 · $groupName".tr(),
                onBack = onBack,
                actions = {
                    IconButton(onClick = { viewModel.toggleFavorite() }) {
                        Icon(
                            imageVector = if (viewModel.isFavorite) AppIcons.Star else AppIcons.StarOutline,
                            contentDescription = "收藏".tr(),
                            tint = if (viewModel.isFavorite) {
                                MaterialTheme.colorScheme.secondary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    TextButton(
                        onClick = { viewModel.save(encrypt) { onSaved() } },
                        enabled = !viewModel.saving,
                    ) {
                        Text(if (viewModel.saving) "保存中…".tr() else "保存".tr())
                    }
                    if (!viewModel.isNew) {
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(AppIcons.More, contentDescription = "更多".tr())
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("删除".tr(), color = MaterialTheme.colorScheme.error) },
                                    onClick = {
                                        menuOpen = false
                                        showDelete = true
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (readOnly) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "只读模式：密码相关操作不可用，其余内容正常。".tr(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            if (mode == EditorMode.PASSWORD) {
                PasswordForm(
                    viewModel = viewModel,
                    secretVisible = secretVisible,
                    onToggleVisible = { secretVisible = !secretVisible },
                )
            } else {
                OutlinedTextField(
                    value = viewModel.title,
                    onValueChange = { viewModel.title = it },
                    label = { Text("标题（可留空）".tr()) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Box {
                    // 自定义外观的只读字段：点击整块都能弹分组菜单
                    Surface(
                        onClick = { groupMenuOpen = true },
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        color = androidx.compose.ui.graphics.Color.Transparent,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = "分组".tr(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = groupName,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                            Icon(
                                AppIcons.ArrowDown,
                                contentDescription = "选择分组".tr(),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    DropdownMenu(expanded = groupMenuOpen, onDismissRequest = { groupMenuOpen = false }) {
                        viewModel.groups.forEach { group ->
                            DropdownMenuItem(
                                text = { Text(group.name) },
                                onClick = {
                                    viewModel.selectGroup(group.id)
                                    groupMenuOpen = false
                                },
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("＋ 新建分组".tr()) },
                            onClick = {
                                groupMenuOpen = false
                                showNewGroup = true
                            },
                        )
                    }
                }

                OutlinedTextField(
                    value = viewModel.body,
                    onValueChange = { viewModel.body = it },
                    label = { Text("内容".tr()) },
                    placeholder = { Text("长文、便签都写这里".tr()) },
                    minLines = 10,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (showNewGroup) {
        TextInputDialog(
            title = "新建分组".tr(),
            label = "分组名称（比如 要玩的游戏）".tr(),
            confirmText = "创建".tr(),
            onConfirm = {
                viewModel.createGroup(it)
                showNewGroup = false
            },
            onDismiss = { showNewGroup = false },
        )
    }

    if (showDelete) {
        ConfirmDialog(
            title = "删除这条内容？".tr(),
            message = "删除后不能恢复。".tr(),
            onConfirm = {
                showDelete = false
                viewModel.delete { onBack() }
            },
            onDismiss = { showDelete = false },
        )
    }
}

@Composable
private fun PasswordForm(
    viewModel: EditorViewModel,
    secretVisible: Boolean,
    onToggleVisible: () -> Unit,
) {
    val context = LocalContext.current
    val crypto = LocalSecretCrypto.current
    val prefs = LocalSecurityPrefs.current
    val session = LocalSession.current
    val scope = rememberCoroutineScope()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = viewModel.account,
            onValueChange = { viewModel.account = it },
            label = { Text("账号 / 平台".tr()) },
            placeholder = { Text("比如 微信、某网站登录名".tr()) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        if (viewModel.hasStoredSecret) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "密码".tr(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = viewModel.revealed ?: "•••••••• 已加密".tr(),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    IconButton(onClick = {
                        if (viewModel.revealed != null) {
                            viewModel.hideSecret()
                        } else if (prefs.requireAuthToViewPassword && !session.passwordVerified) {
                            val host = context.findActivity() ?: return@IconButton
                            scope.launch {
                                val outcome = BiometricGate.authenticate(host, "查看密码".tr(), null)
                                if (outcome is AuthOutcome.Success) {
                                    session.markPasswordVerified()
                                    viewModel.revealSecret { ct -> crypto.decryptNow(ct) }
                                } else {
                                    android.widget.Toast.makeText(
                                        context,
                                        "验证没有通过".tr(),
                                        android.widget.Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        } else {
                            viewModel.revealSecret { ct -> crypto.decryptNow(ct) }
                        }
                    }) {
                        Icon(
                            imageVector = if (viewModel.revealed != null) AppIcons.EyeOff else AppIcons.Eye,
                            contentDescription = "查看密码".tr(),
                        )
                    }
                    IconButton(
                        onClick = {
                            viewModel.revealed?.let { plain ->
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                    as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("密码".tr(), plain))
                            }
                        },
                        enabled = viewModel.revealed != null,
                    ) {
                        Icon(AppIcons.Copy, contentDescription = "复制密码".tr())
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { viewModel.replaceSecret() }) { Text("换成新密码".tr()) }
                Text(
                    text = "点眼睛查看，点复制拷贝".tr(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
        } else {
            OutlinedTextField(
                value = viewModel.secretInput,
                onValueChange = viewModel::updateSecretInput,
                label = { Text("密码".tr()) },
                singleLine = true,
                visualTransformation = if (secretVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = onToggleVisible) {
                        Icon(
                            imageVector = if (secretVisible) AppIcons.EyeOff else AppIcons.Eye,
                            contentDescription = "显示密码".tr(),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "用设备密钥加密后保存在本机".tr(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OutlinedTextField(
            value = viewModel.url,
            onValueChange = { viewModel.url = it },
            label = { Text("网址（可选）".tr()) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = viewModel.body,
            onValueChange = { viewModel.body = it },
            label = { Text("备注（可选）".tr()) },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(16.dp))
    }
}
