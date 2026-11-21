package com.library.app.data.files

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.library.app.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 备份的「另一半」：能导出也要能导回来。
 *
 * 恢复是整库替换，所以必须先关连接、删掉 -wal/-shm，再把文件盖上去；
 * 盖完内存里那些 DAO 还指着旧文件，只能重启进程，没有别的干净做法。
 */
class BackupManager(
    private val context: Context,
    private val database: AppDatabase,
) {

    sealed interface Restore {
        data object Ok : Restore
        data class Failed(val reason: String) : Restore
    }

    suspend fun restoreFrom(uri: Uri): Restore = withContext(Dispatchers.IO) {
        val temp = File(context.cacheDir, "restore.db")
        temp.delete()

        val stream = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
            ?: return@withContext Restore.Failed("读不到这个文件")

        runCatching {
            stream.use { input -> temp.outputStream().use { output -> input.copyTo(output) } }
        }.onFailure { return@withContext Restore.Failed("复制失败：${it.brief()}") }

        if (!temp.exists() || temp.length() == 0L) {
            temp.delete()
            return@withContext Restore.Failed("文件是空的")
        }

        val problem = validate(temp)
        if (problem != null) {
            temp.delete()
            return@withContext Restore.Failed(problem)
        }

        runCatching {
            // 先放掉连接，否则盖过去的文件会被旧句柄继续用
            database.close()
            val target = context.getDatabasePath(AppDatabase.NAME)
            target.parentFile?.mkdirs()
            File(target.path + "-wal").delete()
            File(target.path + "-shm").delete()
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }.fold(
            onSuccess = { Restore.Ok },
            onFailure = { Restore.Failed("写入失败：${it.brief()}，请重启应用再试") },
        )
    }

    /** 返回 null 表示没问题，否则是给人看的原因。 */
    private fun validate(file: File): String? = runCatching {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val version = db.version
            if (version !in 1..AppDatabase.VERSION) {
                return "这个备份是 v$version，当前只认 v1–v${AppDatabase.VERSION}"
            }
            val tables = HashSet<String>()
            db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { cursor ->
                while (cursor.moveToNext()) tables.add(cursor.getString(0))
            }
            val missing = listOf("modules", "folders", "entries").filterNot(tables::contains)
            if (missing.isNotEmpty()) {
                return "不像是资料库的备份（缺 ${missing.joinToString()}）"
            }
        }
        null
    }.getOrElse { "打不开这个文件：${it.brief()}" }

    private fun Throwable.brief(): String = message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

    companion object {
        /** 重启自己：先把新实例拉起来，再杀掉旧进程。 */
        fun restart(context: Context) {
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
            if (intent != null) context.startActivity(intent)
            Handler(Looper.getMainLooper()).postDelayed(
                { Process.killProcess(Process.myPid()) },
                600L,
            )
        }
    }
}
