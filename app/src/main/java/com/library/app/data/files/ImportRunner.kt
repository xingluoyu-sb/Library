package com.library.app.data.files

import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 导入 / 同步的后台执行器。
 *
 * 之前它跑在页面的 viewModelScope 里，一离开页面就被取消，几百个文件导到一半就断。
 * 现在挂在进程级作用域上：切页、返回、进编辑器都不影响；进度用 StateFlow 广播，
 * 任意界面（含全局进度条）都能显示，也能从任意位置取消。
 */
class ImportRunner(private val importer: FolderImporter) {

    data class State(
        val running: Boolean = false,
        val title: String = "",
        val detail: String = "",
        val folders: Int = 0,
        val files: Int = 0,
    )

    sealed interface Outcome {
        data class Done(val result: FolderImporter.Result) : Outcome

        /** 用户中途取消：已经写进去的部分保留，不会回滚。 */
        data class Cancelled(val folders: Int, val files: Int) : Outcome

        data object Failed : Outcome

        /** 已经有任务在跑。 */
        data object Busy : Outcome
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null

    val running: Boolean get() = _state.value.running

    fun cancel() {
        job?.cancel()
    }

    fun importAsModule(treeUri: Uri, color: Int, onDone: (Outcome) -> Unit) =
        start("导入文件夹", onDone) { report -> importer.importAsModule(treeUri, color, report) }

    fun importIntoModule(
        treeUri: Uri,
        moduleId: Long,
        folderId: Long?,
        onDone: (Outcome) -> Unit,
    ) = start("导入文件夹", onDone) { report ->
        importer.importIntoModule(treeUri, moduleId, folderId, report)
    }

    /** 静默同步：不占全局进度条，失败也不打扰。 */
    fun syncModule(moduleId: Long, silent: Boolean, onDone: (Outcome) -> Unit = {}) =
        start("同步文件夹", onDone, silent) { report -> importer.syncModule(moduleId, report) }

    private fun start(
        title: String,
        onDone: (Outcome) -> Unit,
        silent: Boolean = false,
        block: suspend ((FolderImporter.Progress) -> Unit) -> FolderImporter.Result?,
    ) {
        if (_state.value.running) {
            scope.launch { deliver(onDone, Outcome.Busy) }
            return
        }
        var folders = 0
        var files = 0
        job = scope.launch {
            if (!silent) _state.value = State(running = true, title = title)
            val result = try {
                block { progress ->
                    folders = progress.folders
                    files = progress.files
                    if (!silent) {
                        _state.value = _state.value.copy(
                            folders = progress.folders,
                            files = progress.files,
                            detail = progress.current,
                        )
                    }
                }
            } catch (cancel: CancellationException) {
                if (!silent) _state.value = State()
                deliver(onDone, Outcome.Cancelled(folders, files))
                return@launch
            } catch (error: Throwable) {
                if (!silent) _state.value = State()
                deliver(onDone, Outcome.Failed)
                return@launch
            }
            if (!silent) _state.value = State()
            deliver(onDone, if (result == null) Outcome.Failed else Outcome.Done(result))
        }
    }

    private suspend fun deliver(onDone: (Outcome) -> Unit, outcome: Outcome) {
        withContext(NonCancellable + Dispatchers.Main) {
            runCatching { onDone(outcome) }
        }
    }

    companion object {
        /** 导入类任务的结果文案。 */
        fun describe(outcome: Outcome): String = when (outcome) {
            is Outcome.Done -> buildString {
                val result = outcome.result
                append("已导入 ${result.folders} 个文件夹、${result.files} 个文件")
                if (result.skipped > 0) append("，${result.skipped} 个已在库里")
                if (result.truncated) append("（超过 2000 个，后面的没收录）")
            }

            is Outcome.Cancelled -> "已取消，已导入的 ${outcome.files} 个文件保留"
            Outcome.Failed -> "导入失败：文件夹可能已被移动，或没有读取权限"
            Outcome.Busy -> "已经有一个导入任务在跑，先等它结束"
        }

        /** 同步类任务的结果文案。 */
        fun describeSync(outcome: Outcome): String = when (outcome) {
            is Outcome.Done -> {
                val result = outcome.result
                when {
                    result.files == 0 && result.folders == 0 ->
                        "已是最新（${result.skipped} 个文件在库里）"

                    else -> "同步完成：新增 ${result.folders} 个文件夹、${result.files} 个文件"
                }
            }

            is Outcome.Cancelled -> "已取消同步，新增的 ${outcome.files} 个文件保留"
            Outcome.Failed -> "同步失败：源文件夹可能已被移动或删除"
            Outcome.Busy -> "已经有一个导入任务在跑"
        }
    }
}
