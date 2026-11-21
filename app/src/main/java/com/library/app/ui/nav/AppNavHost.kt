package com.library.app.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.library.app.data.files.PreviewKind
import com.library.app.data.files.previewKind
import com.library.app.data.model.Entry
import com.library.app.data.model.EntryType
import com.library.app.ui.LocalFileStore
import com.library.app.ui.LocalRepository
import com.library.app.ui.ScreenScoped
import com.library.app.ui.SessionState
import com.library.app.ui.screens.editor.EditorScreen
import com.library.app.ui.screens.home.HomeScreen
import com.library.app.ui.screens.lock.findActivity
import com.library.app.ui.screens.module.ModuleScreen
import com.library.app.ui.screens.passwords.PasswordsScreen
import com.library.app.ui.screens.records.RecordGroupScreen
import com.library.app.ui.screens.records.RecordsScreen
import com.library.app.ui.screens.search.SearchScreen
import com.library.app.ui.screens.settings.SetPasswordScreen
import com.library.app.ui.screens.settings.SettingsScreen
import com.library.app.ui.screens.trash.TrashScreen
import com.library.app.ui.screens.viewer.ViewerScreen
import kotlinx.coroutines.launch

@Composable
fun AppNavHost(navigator: Navigator, session: SessionState) {
    val context = LocalContext.current
    val repository = LocalRepository.current
    val fileStore = LocalFileStore.current
    val scope = rememberCoroutineScope()

    var recordsModuleId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) { recordsModuleId = repository.recordsModuleId() }

    BackHandler(enabled = true) {
        if (!navigator.pop()) {
            context.findActivity()?.finish()
        }
    }

    val revision = navigator.revision

    fun openEntry(entry: Entry) {
        if (entry.type == EntryType.FILE) {
            // 能自己渲染的就内置打开，其余交给系统
            val kind = previewKind(entry.mimeType, entry.fileName)
            if (kind == PreviewKind.OTHER) {
                entry.fileUri?.let { fileStore.open(it, entry.mimeType) }
            } else {
                navigator.push(Route.Viewer(entry.id))
            }
            return
        }
        val mode = if (entry.type == EntryType.PASSWORD) EditorMode.PASSWORD else EditorMode.RECORD
        navigator.push(Route.Editor(entry.moduleId, entry.folderId, entry.id, mode))
    }

    fun newRecord(folderId: Long?) {
        scope.launch {
            val moduleId = repository.recordsModuleId()
            navigator.push(
                Route.Editor(
                    moduleId = moduleId,
                    folderId = folderId ?: repository.recordDefaultGroupId(),
                    entryId = null,
                    mode = EditorMode.RECORD,
                ),
            )
        }
    }

    val route = navigator.current
    // 路由一变就换一套 ViewModelStore，页面走了就清，避免复用上一页的状态
    key(route) {
    ScreenScoped {
    when (route) {
        Route.Search -> SearchScreen(
            onOpenModule = { navigator.push(Route.Module(it)) },
            onOpenGroup = { folder ->
                if (folder.moduleId == recordsModuleId) {
                    navigator.push(Route.RecordGroup(folder.id))
                } else {
                    navigator.push(Route.Module(folder.moduleId, folder.id))
                }
            },
            onOpenEntry = ::openEntry,
            onBack = { navigator.pop() },
        )

        Route.Passwords -> PasswordsScreen(
            refreshKey = revision,
            onOpen = ::openEntry,
            onLockNow = { session.lock() },
            onOpenSearch = { navigator.push(Route.Search) },
            onOpenSettings = { navigator.push(Route.Settings) },
            onNewPassword = {
                scope.launch {
                    navigator.push(
                        Route.Editor(
                            moduleId = repository.passwordsModuleId(),
                            folderId = null,
                            entryId = null,
                            mode = EditorMode.PASSWORD,
                        ),
                    )
                }
            },
        )

        Route.Records -> RecordsScreen(
            refreshKey = revision,
            onOpenGroup = { navigator.push(Route.RecordGroup(it)) },
            onOpenEntry = ::openEntry,
            onNewInGroup = { newRecord(it) },
            onOpenSearch = { navigator.push(Route.Search) },
            onOpenSettings = { navigator.push(Route.Settings) },
        )

        is Route.RecordGroup -> RecordGroupScreen(
            folderId = route.folderId,
            refreshKey = revision,
            onOpenEntry = ::openEntry,
            onNewRecord = { newRecord(route.folderId) },
            onBack = { navigator.pop() },
        )

        Route.Library -> HomeScreen(
            refreshKey = revision,
            onOpenModule = { navigator.push(Route.Module(it)) },
            onOpenSearch = { navigator.push(Route.Search) },
            onOpenSettings = { navigator.push(Route.Settings) },
        )

        is Route.Module -> ModuleScreen(
            moduleId = route.moduleId,
            folderId = route.folderId,
            refreshKey = revision,
            onOpenFolder = { navigator.push(Route.Module(route.moduleId, it)) },
            onOpenEntry = ::openEntry,
            onBack = { navigator.pop() },
        )

        is Route.Editor -> EditorScreen(
            moduleId = route.moduleId,
            folderId = route.folderId,
            entryId = route.entryId,
            mode = route.mode,
            readOnly = session.readOnly,
            onBack = { navigator.pop() },
            onSaved = { navigator.pop() },
        )

        Route.Settings -> SettingsScreen(
            onBack = { navigator.pop() },
            onOpenTrash = { navigator.push(Route.Trash) },
            onOpenGuide = { navigator.push(Route.Guide) },
            onOpenSetPassword = { navigator.push(Route.SetPassword) },
        )

        Route.Trash -> TrashScreen(onBack = { navigator.pop() })

        Route.SetPassword -> SetPasswordScreen(onBack = { navigator.pop() })

        Route.Guide -> com.library.app.ui.screens.onboarding.OnboardingScreen(
            lock = com.library.app.ui.LocalAppLock.current,
            replay = true,
            onDone = { navigator.pop() },
        )

        is Route.Viewer -> ViewerScreen(entryId = route.entryId, onBack = { navigator.pop() })
    }
    }
    }
}
