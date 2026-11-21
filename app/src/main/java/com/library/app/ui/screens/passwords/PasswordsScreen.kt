@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.library.app.ui.screens.passwords

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.library.app.data.crypto.AuthOutcome
import com.library.app.data.crypto.BiometricGate
import com.library.app.data.crypto.SecretResult
import com.library.app.data.model.Entry
import com.library.app.ui.LocalSecretCrypto
import com.library.app.ui.LocalSecurityPrefs
import com.library.app.ui.LocalSession
import com.library.app.ui.components.AppIcons
import com.library.app.ui.components.Avatar
import com.library.app.ui.components.PrimaryTopBar
import com.library.app.ui.components.EmptyState
import com.library.app.ui.libraryViewModel
import com.library.app.ui.screens.lock.findActivity
import kotlinx.coroutines.launch
import com.library.app.i18n.tr

@Composable
fun PasswordsScreen(
    refreshKey: Int,
    onOpen: (Entry) -> Unit,
    onNewPassword: () -> Unit,
    onLockNow: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val viewModel = libraryViewModel<PasswordsViewModel>("passwords") { PasswordsViewModel(it) }
    LaunchedEffect(refreshKey) { viewModel.refresh() }

    val crypto = LocalSecretCrypto.current
    val prefs = LocalSecurityPrefs.current
    val session = LocalSession.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // 设置里开了开关、而且本次会话还没验证过 → 先打码
    val gated = prefs.requireAuthToViewPassword && !session.passwordVerified
    var securityOpen by remember { mutableStateOf(false) }
    var verifying by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel.entries, gated) {
        if (gated) {
            viewModel.clearPlaintext()
        } else {
            viewModel.loadPlaintext { ct -> (crypto.decryptNow(ct) as? SecretResult.Ok)?.value }
        }
    }

    fun verifyThenShow() {
        if (verifying) return
        val host = context.findActivity() ?: return
        verifying = true
        scope.launch {
            val outcome = BiometricGate.authenticate(host, "查看密码".tr(), "验证后显示已保存的密码".tr())
            verifying = false
            when (outcome) {
                is AuthOutcome.Success -> session.markPasswordVerified()
                else -> snackbar.showSnackbar("验证没有通过".tr())
            }
        }
    }

    Scaffold(
        topBar = {
            PrimaryTopBar(
                title = "密码".tr(),
                onAvatarClick = onOpenSettings,
                onSearch = onOpenSearch,
                extraAction = {
                    IconButton(onClick = { securityOpen = true }) {
                        Icon(AppIcons.Lock, contentDescription = "密码安全".tr())
                    }
                },
            ) {
                IconButton(onClick = onNewPassword) {
                    Icon(AppIcons.Add, contentDescription = "新建密码".tr())
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            if (gated && viewModel.entries.isNotEmpty()) {
                LockedNotice(onVerify = ::verifyThenShow, busy = verifying)
            }

            if (viewModel.entries.isEmpty() && !viewModel.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    EmptyState(
                        icon = AppIcons.Key,
                        title = "还没有密码".tr(),
                        message = "账号和密码分开存，密码用设备密钥加密".tr(),
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 96.dp),
                ) {
                    items(viewModel.entries, key = { it.id }) { entry ->
                        PasswordRow(
                            entry = entry,
                            plain = if (gated) null else viewModel.plaintext[entry.id],
                            masked = gated,
                            onClick = { onOpen(entry) },
                            onFavorite = { viewModel.toggleFavorite(entry) },
                        )
                    }
                }
            }
        }
    }

    if (securityOpen) {
        AlertDialog(
            onDismissRequest = { securityOpen = false },
            title = { Text("密码安全".tr()) },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("查看密码需验证".tr(), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = "打开后，看密码要先过指纹或锁屏密码".tr(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = prefs.requireAuthToViewPassword,
                            onCheckedChange = { checked ->
                                prefs.updateRequireAuth(checked)
                                if (!checked) session.markPasswordVerified()
                            },
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    TextButton(
                        onClick = {
                            securityOpen = false
                            onLockNow()
                        },
                    ) {
                        Text("立即锁定".tr())
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { securityOpen = false }) { Text("完成".tr()) }
            },
        )
    }
}

@Composable
private fun LockedNotice(onVerify: () -> Unit, busy: Boolean) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                AppIcons.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = "密码已隐藏".tr(),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onVerify, enabled = !busy) {
                Text(if (busy) "验证中…".tr() else "验证查看".tr())
            }
        }
    }
}

@Composable
private fun PasswordRow(
    entry: Entry,
    plain: String?,
    masked: Boolean,
    onClick: () -> Unit,
    onFavorite: () -> Unit,
) {
    val context = LocalContext.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(color = MaterialTheme.colorScheme.surfaceVariant, size = 38.dp) {
            Icon(
                imageVector = AppIcons.Key,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = entry.displayTitle.ifBlank { "未命名密码".tr() },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            val secretText = when {
                masked -> "••••••••"
                plain != null -> plain
                else -> "解密失败".tr()
            }
            Text(
                text = secretText,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = if (plain != null || masked) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.error
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            entry.url?.takeIf { it.isNotBlank() }?.let { url ->
                Spacer(Modifier.height(2.dp))
                Text(
                    text = url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (plain != null) {
            IconButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("密码".tr(), plain))
                },
            ) {
                Icon(
                    AppIcons.Copy,
                    contentDescription = "复制密码".tr(),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        IconButton(onClick = onFavorite, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = if (entry.isFavorite) AppIcons.Star else AppIcons.StarOutline,
                contentDescription = if (entry.isFavorite) "取消收藏".tr() else "收藏".tr(),
                tint = if (entry.isFavorite) {
                    MaterialTheme.colorScheme.secondary
                } else {
                    MaterialTheme.colorScheme.outline
                },
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
