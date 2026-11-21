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
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.library.app.ui.components.BottomTabBar
import com.library.app.ui.components.ImportBanner
import com.library.app.ui.nav.AppNavHost
import com.library.app.ui.nav.Navigator
import com.library.app.ui.nav.Route
import com.library.app.ui.nav.section
import com.library.app.ui.nav.toRoute
import com.library.app.ui.screens.lock.LockScreen
import com.library.app.ui.screens.onboarding.OnboardingScreen

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
