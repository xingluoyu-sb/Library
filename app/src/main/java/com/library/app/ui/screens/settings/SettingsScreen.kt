package com.library.app.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.library.app.data.AppLock
import com.library.app.data.crypto.AuthOutcome
import com.library.app.data.crypto.BiometricGate
import com.library.app.data.files.BackupManager
import com.library.app.i18n.AppLanguage
import com.library.app.i18n.Lang
import com.library.app.ui.LocalAppLock
import com.library.app.ui.LocalBackupManager
import com.library.app.ui.LocalBranding
import com.library.app.ui.LocalFileStore
import com.library.app.ui.LocalRepository
import com.library.app.ui.components.AppIcons
import com.library.app.ui.components.ConfirmDialog
import com.library.app.ui.components.LibraryTopBar
import com.library.app.ui.components.TextInputDialog
import com.library.app.ui.screens.lock.findActivity
import kotlinx.coroutines.launch
import com.library.app.i18n.tr

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenTrash: () -> Unit = {},
    onOpenGuide: () -> Unit = {},
    onOpenSetPassword: () -> Unit = {},
) {
    val context = LocalContext.current
    val repository = LocalRepository.current
    val fileStore = LocalFileStore.current
    val backupManager = LocalBackupManager.current
    val branding = LocalBranding.current
    val lock = LocalAppLock.current
    val scope = rememberCoroutineScope()
    val activity = remember(context) { context.findActivity() }

    var showClear by remember { mutableStateOf(false) }
    var backupMessage by remember { mutableStateOf<String?>(null) }
    var hint by remember { mutableStateOf<String?>(null) }
    var pendingRestore by remember { mutableStateOf<android.net.Uri?>(null) }
    var restored by remember { mutableStateOf(false) }
    var trashCount by remember { mutableStateOf(0) }
    var cropSource by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var editingNickname by remember { mutableStateOf(false) }
    var verifying by remember { mutableStateOf(false) }
    var verifyError by remember { mutableStateOf<String?>(null) }
    var deviceUnavailable by remember { mutableStateOf(false) }
    var exportOpen by remember { mutableStateOf(false) }
    var wantPassphrase by remember { mutableStateOf<android.net.Uri?>(null) }
    var restorePass by remember { mutableStateOf<String?>(null) }
    var restorePlain by remember { mutableStateOf(false) }
    var backupBusy by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        trashCount = repository.trashCount()
    }

    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: ""
    }

    val logo = branding.logo

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val bitmap = loadAvatarSource(context, uri)
                if (bitmap == null) {
                    hint = "这张图片读不出来，换一张试试".tr()
                } else {
                    cropSource = bitmap
                }
            }
        }
    }

    val restorePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            backupBusy = true
            val kind = backupManager.kindOf(uri)
            backupBusy = false
            when (kind) {
                // 加密备份先问口令，问完再让用户确认覆盖
                BackupManager.Kind.ENCRYPTED -> wantPassphrase = uri
                BackupManager.Kind.PLAIN -> {
                    restorePlain = true
                    restorePass = null
                    pendingRestore = uri
                }
                BackupManager.Kind.UNKNOWN -> backupMessage = "读不到这个文件".tr()
            }
        }
    }

    Scaffold(
        topBar = { LibraryTopBar(title = "设置".tr(), onBack = onBack) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            GroupLabel("外观".tr())
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        picker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    }
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    if (logo != null) {
                        Image(
                            bitmap = logo,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Text(
                            text = "库".tr(),
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 18.sp,
                        )
                    }
                }
                Column(Modifier.weight(1f)) {
                    hint?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Text("更换".tr(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            if (logo != null) {
                TextButton(
                    onClick = { branding.reset() },
                    modifier = Modifier.padding(start = 14.dp),
                ) {
                    Text("恢复默认".tr())
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { editingNickname = true }
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("昵称".tr(), style = MaterialTheme.typography.bodyLarge)
                }
                Text(
                    text = branding.nickname,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = "语言".tr(),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AppLanguage.entries.forEach { language ->
                        val selected = Lang.current == language
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                )
                                .clickable { Lang.select(language) }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = language.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (selected) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )

            GroupLabel("安全".tr())
            ActionRow(
                icon = AppIcons.Lock,
                title = "重置密码".tr(),
                subtitle = "验证后设置新的应用密码".tr(),
                tint = MaterialTheme.colorScheme.primary,
                onClick = {
                    // 先叫系统验一次：指纹或本机锁屏密码都行，不用记旧的应用密码
                    val host = activity
                    if (host != null && BiometricGate.canAuthenticate(host)) {
                        scope.launch {
                            when (BiometricGate.authenticate(
                                activity = host,
                                title = "验证手机凭证".tr(),
                                subtitle = "确认是本人后再设置新密码".tr(),
                            )) {
                                is AuthOutcome.Success -> onOpenSetPassword()
                                AuthOutcome.Cancelled -> Unit
                                is AuthOutcome.Failed -> verifyError = "验证没有通过".tr()
                            }
                        }
                    } else if (lock.mode == AppLock.Mode.PASSWORD) {
                        // 手机没设锁屏密码，只能拿应用密码验
                        verifying = true
                    } else {
                        deviceUnavailable = true
                    }
                },
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )

            GroupLabel("数据".tr())
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(
                    AppIcons.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text("备份目录".tr(), style = MaterialTheme.typography.bodyLarge)
                    SelectionContainer {
                        Text(
                            text = fileStore.rootLabel(),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            ActionRow(
                icon = AppIcons.Doc,
                title = "导出备份".tr(),
                subtitle = backupMessage ?: "用口令加密后存到上面的目录".tr(),
                tint = MaterialTheme.colorScheme.primary,
                onClick = { if (!backupBusy) exportOpen = true },
            )

            ActionRow(
                icon = AppIcons.Archive,
                title = "从备份恢复".tr(),
                subtitle = backupMessage ?: "选一个 .libbak 备份，覆盖当前数据".tr(),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = { restorePicker.launch(arrayOf("*/*")) },
            )

            ActionRow(
                icon = AppIcons.Delete,
                title = "回收站".tr(),
                subtitle = if (trashCount == 0) "没有已删除的内容".tr() else "$trashCount 条待清理".tr(),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = onOpenTrash,
            )

            ActionRow(
                icon = AppIcons.Delete,
                title = "清空全部数据".tr(),
                subtitle = "删除所有内容，不可恢复".tr(),
                tint = MaterialTheme.colorScheme.error,
                onClick = { showClear = true },
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )

            GroupLabel("关于".tr())
            ActionRow(
                icon = AppIcons.Globe,
                title = "新手引导".tr(),
                subtitle = "再看一遍语言、导入和三个模块的说明".tr(),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = onOpenGuide,
            )
            ActionRow(
                icon = AppIcons.Archive,
                title = "资料库".tr(),
                subtitle = "版本 $versionName".tr(),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = null,
            )
            // 署名想换成自己的名字，改下面这句就行
            ActionRow(
                icon = AppIcons.Doc,
                title = "开发".tr(),
                subtitle = "星落雨 · 个人独立开发 · 无广告 · 无需联网".tr(),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = null,
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showClear) {
        ConfirmDialog(
            title = "清空全部数据？".tr(),
            message = "所有资料库、文件夹和内容都会被删除，包括已加密的密码。".tr(),
            confirmText = "清空".tr(),
            onConfirm = {
                showClear = false
                scope.launch {
                    repository.clearAll()
                    onBack()
                }
            },
            onDismiss = { showClear = false },
        )
    }

    pendingRestore?.let { uri ->
        ConfirmDialog(
            title = "用备份覆盖当前数据？".tr(),
            message = if (restorePlain) {
                "这是旧版明文备份：记录和资料条目能回来，里面的密码在这台手机上解不开。当前所有内容都会被替换，不能撤销。".tr()
            } else {
                "当前所有资料库、文件夹和密码都会被这份备份替换，不能撤销。".tr()
            },
            confirmText = "覆盖".tr(),
            onConfirm = {
                val pass = restorePass
                pendingRestore = null
                scope.launch {
                    backupBusy = true
                    when (val outcome = backupManager.restoreFrom(uri, pass)) {
                        BackupManager.Restore.Ok -> restored = true
                        is BackupManager.Restore.Failed ->
                            backupMessage = "恢复失败：${outcome.reason}".tr()
                    }
                    backupBusy = false
                }
            },
            onDismiss = { pendingRestore = null },
        )
    }

    if (exportOpen) {
        PassphraseDialog(
            title = "设置备份口令".tr(),
            message = "备份会用一个口令加密，8 位以上。口令不写进任何地方，忘了这份备份就打不开。".tr(),
            confirmText = "导出".tr(),
            withConfirm = true,
            onCancel = { exportOpen = false },
            onConfirm = { pass ->
                exportOpen = false
                scope.launch {
                    backupBusy = true
                    backupMessage = "正在导出…".tr()
                    when (val outcome = backupManager.exportEncrypted(pass)) {
                        is BackupManager.Export.Ok -> backupMessage = "已保存 ${outcome.name}".tr()
                        is BackupManager.Export.Failed ->
                            backupMessage = "备份失败：${outcome.reason}".tr()
                    }
                    backupBusy = false
                }
            },
        )
    }

    wantPassphrase?.let { uri ->
        PassphraseDialog(
            title = "输入备份口令".tr(),
            message = "这份备份是加密的，需要当时设置的口令。".tr(),
            confirmText = "继续".tr(),
            withConfirm = false,
            onCancel = { wantPassphrase = null },
            onConfirm = { pass ->
                wantPassphrase = null
                restorePass = pass
                restorePlain = false
                pendingRestore = uri
            },
        )
    }

    if (restored) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {},
            title = { Text("恢复完成".tr()) },
            text = { Text("数据已替换，需要重启应用才能读取。".tr()) },
            confirmButton = {
                TextButton(onClick = { BackupManager.restart(context) }) { Text("立即重启".tr()) }
            },
        )
    }

    cropSource?.let { source ->
        AvatarCropDialog(
            source = source,
            onCancel = { cropSource = null },
            onDone = { square ->
                hint = if (branding.update(square)) null else "头像保存失败，再试一次".tr()
                cropSource = null
            },
        )
    }

    if (editingNickname) {
        TextInputDialog(
            title = "昵称".tr(),
            label = "显示在头像右边".tr(),
            initial = branding.nickname,
            confirmText = "保存".tr(),
            onConfirm = { value ->
                branding.updateNickname(value)
                editingNickname = false
            },
            onDismiss = { editingNickname = false },
        )
    }

    if (verifying) {
        VerifyPasswordDialog(
            lock = lock,
            onPassed = {
                verifying = false
                onOpenSetPassword()
            },
            onDismiss = { verifying = false },
        )
    }

    if (deviceUnavailable) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deviceUnavailable = false },
            title = { Text("手机凭证用不了".tr()) },
            text = { Text("这台手机现在没有可用的锁屏密码或指纹，可以直接设置一个新的应用密码。".tr()) },
            confirmButton = {
                TextButton(onClick = {
                    deviceUnavailable = false
                    onOpenSetPassword()
                }) { Text("继续".tr()) }
            },
            dismissButton = {
                TextButton(onClick = { deviceUnavailable = false }) { Text("取消".tr()) }
            },
        )
    }

    verifyError?.let { message ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { verifyError = null },
            title = { Text("验证没有通过".tr()) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { verifyError = null }) { Text("知道了".tr()) }
            },
        )
    }
}

/** 重置密码前先验一次旧密码，过了才放行到设置页。 */
@Composable
private fun VerifyPasswordDialog(
    lock: AppLock,
    onPassed: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("验证当前密码".tr()) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        error = null
                    },
                    label = { Text("当前密码".tr()) },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                        imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onDone = {
                            if (!busy && text.isNotBlank()) {
                                scope.launch {
                                    busy = true
                                    val ok = kotlinx.coroutines.withContext(
                                        kotlinx.coroutines.Dispatchers.Default,
                                    ) { lock.verifyPassword(text) }
                                    busy = false
                                    if (ok) onPassed() else error = "当前密码不对".tr()
                                }
                            }
                        },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && text.isNotBlank(),
                onClick = {
                    scope.launch {
                        busy = true
                        val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                            lock.verifyPassword(text)
                        }
                        busy = false
                        if (ok) onPassed() else error = "当前密码不对".tr()
                    }
                },
            ) { Text("继续".tr()) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消".tr()) }
        },
    )
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 6.dp),
    )
}

@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    tint: androidx.compose.ui.graphics.Color,
    onClick: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 收口令的小弹窗。withConfirm=true 时要求输两遍（导出用），
 * false 时只输一遍（恢复时验证口令用）。
 */
@Composable
private fun PassphraseDialog(
    title: String,
    message: String,
    confirmText: String,
    withConfirm: Boolean,
    onCancel: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val min = BackupManager.MIN_PASSPHRASE

    fun submit() {
        if (!withConfirm) {
            if (value.isBlank()) {
                error = "先输入口令".tr()
                return
            }
            onConfirm(value)
            return
        }
        error = when {
            value.length < min -> "口令至少 $min 位".tr()
            value != again -> "两次输入不一致".tr()
            else -> null
        }
        if (error == null) onConfirm(value)
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = {
                        value = it
                        error = null
                    },
                    label = { Text(if (withConfirm) "口令".tr() else "备份口令".tr()) },
                    singleLine = true,
                    visualTransformation = if (visible) {
                        androidx.compose.ui.text.input.VisualTransformation.None
                    } else {
                        androidx.compose.ui.text.input.PasswordVisualTransformation()
                    },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                        imeAction = if (withConfirm) {
                            androidx.compose.ui.text.input.ImeAction.Next
                        } else {
                            androidx.compose.ui.text.input.ImeAction.Done
                        },
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onDone = { submit() },
                    ),
                    trailingIcon = {
                        TextButton(onClick = { visible = !visible }) {
                            Text(if (visible) "隐藏".tr() else "显示".tr())
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (withConfirm) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = again,
                        onValueChange = {
                            again = it
                            error = null
                        },
                        label = { Text("再次输入".tr()) },
                        singleLine = true,
                        visualTransformation = if (visible) {
                            androidx.compose.ui.text.input.VisualTransformation.None
                        } else {
                            androidx.compose.ui.text.input.PasswordVisualTransformation()
                        },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                            imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                        ),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                            onDone = { submit() },
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { submit() }) { Text(confirmText) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("取消".tr()) }
        },
    )
}
