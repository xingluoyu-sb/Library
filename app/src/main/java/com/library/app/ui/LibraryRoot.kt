package com.library.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.library.app.data.files.BackupManager
import com.library.app.i18n.tr
import com.library.app.ui.components.BottomTabBar
import com.library.app.ui.components.ImportBanner
import com.library.app.ui.nav.AppNavHost
import com.library.app.ui.nav.Navigator
import com.library.app.ui.nav.Route
import com.library.app.ui.nav.section
import com.library.app.ui.nav.toRoute
import com.library.app.ui.screens.lock.LockScreen
import com.library.app.ui.screens.onboarding.OnboardingScreen
import kotlinx.coroutines.launch

@Composable
fun LibraryRoot(session: SessionState) {
    // 启动就落在资料库
    val navigator = remember { Navigator(Route.Library) }
    val lock = LocalAppLock.current

    // 第一次进：先把语言、头像昵称、进入方式定下来，再谈解锁
    if (!lock.onboarded) {
        OnboardingScreen(lock = lock, onDone = { session.unlock() })
        return
    }

    if (!session.unlocked) {
        LockScreen(session, lock)
        return
    }

    LaunchedEffect(session.devViewEntryId) {
        val entryId = session.devViewEntryId ?: return@LaunchedEffect
        session.devViewEntryId = null
        navigator.push(Route.Viewer(entryId))
    }

    val runner = LocalImportRunner.current
    val importState by runner.state.collectAsState()

    val route = navigator.current
    // 只有三个一级页给胶囊留位置，进了模块 / 文件 / 设置这些二级页就收起来
    val currentSection = route.section
    val showTabs = currentSection != null
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    CompositionLocalProvider(LocalSession provides session) {
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            AppNavHost(navigator, session)

            AnimatedVisibility(
                visible = showTabs,
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = navBottom + 12.dp),
            ) {
                BottomTabBar(
                    selected = currentSection,
                    onSelect = { navigator.resetTo(it.toRoute()) },
                )
            }

            InterruptedRestorePrompt()

            // 导入进度挂在根上：切页、返回都不中断，也能随手取消
            ImportBanner(
                state = importState,
                onCancel = runner::cancel,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(
                        start = 16.dp,
                        end = 88.dp,
                        // 有胶囊时抬到它上面，别压在一起
                        bottom = navBottom + if (showTabs) 78.dp else 12.dp,
                    ),
            )
        }
    }
}

/**
 * 上次恢复在「覆盖库」和「写回 DEK」之间被杀掉时，磁盘上还留着恢复前的副本。
 * 这种状态用户只会看到密码全打不开，所以启动就问一句要不要退回去。
 *
 * 选「先不用」只是这次不弹，副本留着，下次冷启动还会问。
 */
@Composable
private fun InterruptedRestorePrompt() {
    val backup = LocalBackupManager.current
    val pending by backup.pendingRollback.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var dismissed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    if (!pending || dismissed) return

    AlertDialog(
        onDismissRequest = {},
        title = { Text("上次恢复没有完成".tr()) },
        text = {
            Text(
                "新库已经写进去了，但密钥没接上，现在密码都打不开。" +
                    "可以退回恢复之前的资料库。".tr(),
            )
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        when (val outcome = backup.rollbackToPendingCopy()) {
                            BackupManager.Restore.Ok -> BackupManager.restart(context)
                            is BackupManager.Restore.Failed -> {
                                busy = false
                                android.widget.Toast.makeText(
                                    context,
                                    outcome.reason,
                                    android.widget.Toast.LENGTH_LONG,
                                ).show()
                            }
                            is BackupManager.Restore.Partial -> busy = false
                        }
                    }
                },
            ) { Text(if (busy) "正在退回…".tr() else "退回".tr()) }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = { dismissed = true }) { Text("先不用".tr()) }
        },
    )
}
