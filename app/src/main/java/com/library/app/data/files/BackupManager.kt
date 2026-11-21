package com.library.app.data.files

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.MediaStore
import androidx.annotation.WorkerThread
import com.library.app.data.crypto.BackupCodec
import com.library.app.data.crypto.DataKey
import com.library.app.data.db.AppDatabase
import com.library.app.data.db.Schema
import com.library.app.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 备份与恢复。
 *
 * 导出的是加密容器 `.libbak`（见 [BackupCodec]），不是裸的 `.db`：
 * 记录正文、条目名、平台账号都经过口令派生的密钥加密，容器里还带着用同一口令包住的
 * 数据密钥，所以换手机恢复后密码条目照样能解开。
 *
 * 恢复兼容两种文件：老的明文 `.db`（密码无法解密）和新的 `.libbak`。
 * 整库替换必须：关连接 → 删 -wal/-shm → 覆盖 → fsync → 重启进程，
 * 中间任何一步失败都保留原库不动。
 */
class BackupManager(
    private val context: Context,
    private val database: AppDatabase,
    private val dataKey: DataKey,
    private val fileStore: FileStore,
) {

    sealed interface Export {
        data class Ok(val name: String) : Export
        data class Failed(val reason: String) : Export
    }

    sealed interface Restore {
        data object Ok : Restore

        /**
         * 库换成功了，但有件事必须告诉用户：密码模块还没迁完，或者密码是别的设备加的密。
         * 这不是失败，重启之后资料条目都是好的，只有密码要额外处理。
         */
        data class Partial(val reason: String) : Restore

        data class Failed(val reason: String) : Restore
    }

    enum class Kind { ENCRYPTED, PLAIN, UNKNOWN }

    companion object {
        const val EXTENSION = "libbak"
        const val MIN_PASSPHRASE = 8

        /** 覆盖原库之前留下的副本；恢复中途进程被杀，下次启动就靠它回退。 */
        private const val ROLLBACK_NAME = "restore-rollback.db"

        /** 重启自己：先把新实例拉起来，再杀掉旧进程，避免同一进程里重开 DAO。 */
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

    private fun rollbackFile(): File = File(context.cacheDir, ROLLBACK_NAME)

    private val _pendingRollback = MutableStateFlow(false)

    /**
     * 有「上次恢复没走完」的回退副本等着用户决定。
     *
     * 不能只靠 restoreFrom 里的局部变量：进程在
     * `replaceDatabase()` 和 `adoptInto()` 之间被杀时，那段栈连同变量一起没了，
     * 只剩磁盘上这份副本能说明发生过什么。
     */
    val pendingRollback: StateFlow<Boolean> = _pendingRollback

    /**
     * 启动时调用（AppDatabase 起来之后）。
     *
     * 判定条件就是「密钥拿不到 + 副本还在」：这时候库已经被换成新库，
     * 里面包的还是上一台设备的 DEK，用户看到的是密码全打不开。
     * 正常恢复成功时副本已删，恢复失败时原库已还原，两种情况都不会误报。
     */
    @WorkerThread
    fun detectInterruptedRestore() {
        val copy = rollbackFile()
        if (!copy.exists() || copy.length() == 0L) {
            _pendingRollback.value = false
            return
        }
        val lost = runCatching { dataKey.key() }.exceptionOrNull() is DataKey.DekUnavailable
        _pendingRollback.value = lost
    }

    /** 用户点了「退回」：用副本覆盖当前库，成功后清掉标记，由界面负责重启进程。 */
    suspend fun rollbackToPendingCopy(): Restore = withContext(Dispatchers.IO) {
        val copy = rollbackFile()
        if (!copy.exists() || copy.length() == 0L) {
            _pendingRollback.value = false
            return@withContext Restore.Failed("上次的副本已经不在了，没法退回".tr())
        }
        runCatching { replaceDatabase(copy) }.fold(
            onSuccess = {
                copy.delete()
                _pendingRollback.value = false
                Restore.Ok
            },
            onFailure = { Restore.Failed("退回失败：${it.brief()}".tr()) },
        )
    }

    /** 先看文件头，界面据此决定要不要问口令。 */
    suspend fun kindOf(uri: Uri): Kind = withContext(Dispatchers.IO) {
        val head = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(8)
                val read = input.read(buffer)
                if (read > 0) buffer.copyOf(read) else ByteArray(0)
            }
        }.getOrNull() ?: return@withContext Kind.UNKNOWN

        if (BackupCodec.looksLikeBackup(head)) Kind.ENCRYPTED else Kind.PLAIN
    }

    suspend fun exportEncrypted(passphrase: String): Export = withContext(Dispatchers.IO) {
        if (passphrase.length < MIN_PASSPHRASE) {
            return@withContext Export.Failed("口令至少 $MIN_PASSPHRASE 位".tr())
        }
        runCatching {
            // WAL 里没落盘的内容先并进主文件，否则最近改的东西不会进备份
            database.writableDatabase.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null)
                .use { it.moveToFirst() }

            val dbFile = context.getDatabasePath(AppDatabase.NAME)
            if (!dbFile.exists()) throw IOException("还没有数据库".tr())

            val resolver = context.contentResolver
            val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
            val name = "library-$stamp.$EXTENSION"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, fileStore.rootLabel())
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val target = resolver.insert(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                values,
            ) ?: throw IOException("写不进备份目录".tr())

            try {
                resolver.openOutputStream(target)?.use { out ->
                    BackupCodec.encodeTo(out, dbFile, dataKey.key(), passphrase)
                } ?: throw IOException("写不进备份目录".tr())
                resolver.update(
                    target,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                )
                name
            } catch (t: Throwable) {
                // 失败就删掉半截文件，别在目录里留一个打不开的东西
                runCatching { resolver.delete(target, null, null) }
                throw t
            }
        }.fold(
            onSuccess = { Export.Ok(it) },
            onFailure = { Export.Failed(it.brief()) },
        )
    }

    /** [passphrase] 为空表示这是老的明文 `.db`。 */
    suspend fun restoreFrom(uri: Uri, passphrase: String? = null): Restore = withContext(Dispatchers.IO) {
        val incoming = File(context.cacheDir, "backup-in.tmp")
        val decrypted = File(context.cacheDir, "backup-out.db")
        val rollback = rollbackFile()
        // 回滚也失败时，这份副本就是原库唯一的拷贝，绝不能删
        var keepRollback = false
        try {
            incoming.delete()
            decrypted.delete()
            rollback.delete()

            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(incoming).use { output -> input.copyTo(output, 64 * 1024) }
                } ?: throw IOException("读不到这个文件".tr())
            }.onFailure { return@withContext Restore.Failed("读不到这个文件".tr()) }

            if (!incoming.exists() || incoming.length() == 0L) {
                return@withContext Restore.Failed("文件是空的".tr())
            }

            val encrypted = incoming.inputStream().use { input ->
                val head = ByteArray(8)
                val read = input.read(head)
                BackupCodec.looksLikeBackup(if (read > 0) head.copyOf(read) else ByteArray(0))
            }

            val source: File
            var dek: ByteArray? = null
            if (encrypted) {
                if (passphrase.isNullOrEmpty()) return@withContext Restore.Failed("这份备份需要口令".tr())
                dek = runCatching {
                    incoming.inputStream().use { BackupCodec.decodeTo(it, decrypted, passphrase) }
                }.getOrElse { error ->
                    return@withContext Restore.Failed(
                        when (error) {
                            is BackupCodec.WrongPassphrase -> "口令不对，或者备份已经损坏".tr()
                            is BackupCodec.NotBackupFile -> "不像是资料库的备份".tr()
                            is BackupCodec.Damaged -> "备份文件不完整".tr()
                            else -> "解不开这份备份：${error.brief()}".tr()
                        },
                    )
                }
                source = decrypted
            } else {
                source = incoming
            }

            validate(source)?.let { return@withContext Restore.Failed(it) }

            // 源库是几版，恢复后就得是几版。在没迁完的库上导出的备份，
            // entries 还是 v1: 密文，写成 2 会让读取和迁移两条路一起死掉
            val sourceVersion = cryptoVersionOf(source)

            // ① 先把「新库里的 DEK 能不能被本机接管」验掉。
            //    这一步失败时原库一个字节都没动，用户还能照常用旧数据
            val adoption = dek?.let { raw ->
                runCatching { dataKey.prepareAdoption(raw, sourceVersion) }.getOrElse { error ->
                    return@withContext Restore.Failed(error.restoreReason())
                }
            }

            // ② 覆盖前把原库整份拷到 cacheDir，后面任何一步失败都能退回去
            val haveRollback = copyDatabaseTo(rollback)

            runCatching {
                replaceDatabase(source)
                if (adoption != null) {
                    SQLiteDatabase.openDatabase(
                        context.getDatabasePath(AppDatabase.NAME).path,
                        null,
                        SQLiteDatabase.OPEN_READWRITE,
                    ).use { dataKey.adoptInto(it, adoption) }
                }
            }.onFailure { error ->
                val restored = haveRollback && runCatching { replaceDatabase(rollback) }.isSuccess
                keepRollback = !restored
                return@withContext Restore.Failed(
                    if (restored) {
                        "没能写入新库（${error.brief()}），原来的资料库已经还原".tr()
                    } else {
                        "没能写入新库（${error.brief()}），原库也没能还原；请重启应用后再恢复一次".tr()
                    },
                )
            }

            _pendingRollback.value = false
            if (sourceVersion == DataKey.VERSION_LEGACY) {
                return@withContext Restore.Partial(
                    "资料库恢复了，但里面的密码还是旧格式，需要重新迁移一次；" +
                        "这些密文如果是另一台手机加密的，这台机器打不开".tr(),
                )
            }
            Restore.Ok
        } finally {
            // 明文数据库的临时副本必须在这里清掉，失败路径也不能留
            incoming.delete()
            decrypted.delete()
            if (!keepRollback) rollback.delete()
        }
    }

    /**
     * 覆盖前留一份原库。先把 WAL 并进主文件，否则拷出来的副本会缺最近写入的内容。
     * 返回 false 表示没留成（比如本来就没有库），调用方据此决定失败时怎么说。
     */
    private fun copyDatabaseTo(target: File): Boolean = runCatching {
        runCatching {
            database.writableDatabase.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null)
                .use { it.moveToFirst() }
        }
        val original = context.getDatabasePath(AppDatabase.NAME)
        if (!original.exists()) return@runCatching false
        target.delete()
        original.inputStream().use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output, 64 * 1024)
                output.flush()
                output.fd.sync()
            }
        }
        true
    }.getOrDefault(false)

    private fun Throwable.restoreReason(): String =
        (this as? DataKey.DekUnavailable)?.message?.takeIf { it.isNotBlank() }
            ?: "本机的密钥系统不可用，没法接管这份备份".tr()

    private fun replaceDatabase(source: File) {
        // 先放掉连接，否则旧句柄还会继续写老文件
        database.close()
        val target = context.getDatabasePath(AppDatabase.NAME)
        target.parentFile?.mkdirs()
        File(target.path + "-wal").delete()
        File(target.path + "-shm").delete()
        source.inputStream().use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output, 64 * 1024)
                output.flush()
                output.fd.sync()
            }
        }
    }

    /**
     * 源库的 crypto_version。表不存在（v7 及更早的库）或者读不出人话的值，
     * 一律当作 [DataKey.VERSION_LEGACY] —— 那是最保守的假设，不会把库标成迁完。
     *
     * 刻意不放 [BackupCodec] 里：那个类要能在纯 JVM 单测里跑，不能碰 android.database。
     */
    private fun cryptoVersionOf(dbFile: File): Int = runCatching {
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery(
                "SELECT crypto_version FROM ${Schema.TABLE_CRYPTO_KEYS} WHERE id = 1",
                null,
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else DataKey.VERSION_LEGACY }
        }
    }.getOrElse { DataKey.VERSION_LEGACY }
        .takeIf { it == DataKey.VERSION_LEGACY || it == DataKey.VERSION_DEK }
        ?: DataKey.VERSION_LEGACY

    /** 返回 null 表示没问题，否则是给人看的原因。 */
    private fun validate(file: File): String? = runCatching {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val version = db.version
            if (version !in 1..AppDatabase.VERSION) {
                return "这个备份是 v$version，当前只认 v1–v${AppDatabase.VERSION}".tr()
            }
            val tables = HashSet<String>()
            db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { cursor ->
                while (cursor.moveToNext()) tables.add(cursor.getString(0))
            }
            val missing = listOf("modules", "folders", "entries").filterNot(tables::contains)
            if (missing.isNotEmpty()) {
                return "不像是资料库的备份（缺 ${missing.joinToString()}）".tr()
            }
        }
        null
    }.getOrElse { "打不开这个文件：${it.brief()}".tr() }

    private fun Throwable.brief(): String = message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
}
