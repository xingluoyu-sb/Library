package com.library.app.data.crypto

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.annotation.WorkerThread
import com.library.app.data.db.AppDatabase
import com.library.app.data.db.Schema

/**
 * 数据密钥（DEK）的管家。
 *
 * 为什么要多一层：Android Keystore 里的密钥不可导出，直接拿它加密密码字段，
 * 备份换到别的手机上就是一堆永远解不开的密文。所以把「真正加密数据的密钥」抽成
 * 一段 32 字节随机数（DEK）：
 *
 * - 密码字段用 DEK 加密（`v2:` 前缀）；
 * - DEK 自己用 Keystore 里的密钥包一层，存进 `crypto_keys`，日常启动不用输任何口令；
 * - 导出备份时，DEK 再用「备份口令」派生的密钥包一层跟着文件走。
 *
 * Keystore 因此从「唯一的钥匙」降级成「本机免输口令的加速层」：它丢了只影响便捷性，
 * 只要还有一份带口令的加密备份，数据就还能解开。
 *
 * `crypto_version` 是唯一的版本真相：1 = 密文还是 Keystore 直加的旧格式，
 * 2 = 已经全量换成 DEK。读取路径只看它，不做「解不开就换一种」的试探。
 */
class DataKey(
    private val context: Context,
    private val database: AppDatabase,
) {

    private val box = CryptoBox(context)

    @Volatile
    private var cached: ByteArray? = null

    @Volatile
    private var cachedVersion: Int? = null

    /** 1 = 旧 Keystore 密文；2 = DEK 密文。没有记录也算 1（老库刚升级上来的状态）。 */
    @WorkerThread
    fun version(): Int {
        cachedVersion?.let { return it }
        val value = runCatching {
            database.readableDatabase.rawQuery(
                "SELECT crypto_version FROM ${Schema.TABLE_CRYPTO_KEYS} WHERE id = 1",
                null,
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else VERSION_LEGACY }
        }.getOrDefault(VERSION_LEGACY)
        cachedVersion = value
        return value
    }

    /** 没有就建一个（版本仍是 1，等迁移完成后才变 2）。 */
    @WorkerThread
    fun key(): ByteArray {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val dek = load() ?: create()
            cached = dek
            return dek
        }
    }

    @WorkerThread
    fun loadOrNull(): ByteArray? = cached ?: load()

    /**
     * 恢复备份后调用：把容器里带出来的 DEK 用本机 Keystore 重新包一遍写进新库。
     * 这里直接对刚落盘的库文件动手，不能用 [database]（它已经被 close 了）。
     */
    @WorkerThread
    fun adoptInto(db: SQLiteDatabase, dek: ByteArray) {
        require(dek.size == DEK_SIZE) { "数据密钥长度不对" }
        box.ensureKey()
        // 恢复来的可能是 v7 及更早的库，先把表补出来
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS ${Schema.TABLE_CRYPTO_KEYS} (
                id             INTEGER PRIMARY KEY CHECK (id = 1),
                wrapped_dek    TEXT    NOT NULL,
                crypto_version INTEGER NOT NULL DEFAULT 1,
                created_at     INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "INSERT OR REPLACE INTO ${Schema.TABLE_CRYPTO_KEYS} " +
                "(id, wrapped_dek, crypto_version, created_at) VALUES (1, ?, ?, ?)",
            arrayOf<Any>(box.encryptBytes(dek, AAD_DEK), VERSION_DEK, System.currentTimeMillis()),
        )
        invalidate()
    }

    /** 迁移事务里调用，和密文改写同一个事务，保证版本号和内容不会对不上。 */
    @WorkerThread
    fun markMigrated(db: SQLiteDatabase) {
        db.execSQL(
            "UPDATE ${Schema.TABLE_CRYPTO_KEYS} SET crypto_version = ? WHERE id = 1",
            arrayOf<Any>(VERSION_DEK),
        )
        invalidate()
    }

    @WorkerThread
    fun invalidate() {
        cachedVersion = null
    }

    @WorkerThread
    private fun load(): ByteArray? = runCatching {
        database.readableDatabase.rawQuery(
            "SELECT wrapped_dek FROM ${Schema.TABLE_CRYPTO_KEYS} WHERE id = 1",
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            // 换了设备或 Keystore 密钥被系统作废时解不开，当作「没有」重新生成；
            // 此时旧密文确实读不出来了，只能靠带口令的备份恢复
            runCatching { box.decryptBytes(cursor.getString(0), AAD_DEK) }.getOrNull()
        }
    }.getOrNull()

    @WorkerThread
    private fun create(): ByteArray {
        val dek = AesGcm.randomBytes(DEK_SIZE)
        database.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO ${Schema.TABLE_CRYPTO_KEYS} " +
                "(id, wrapped_dek, crypto_version, created_at) VALUES (1, ?, ?, ?)",
            arrayOf<Any>(box.encryptBytes(dek, AAD_DEK), VERSION_LEGACY, System.currentTimeMillis()),
        )
        cachedVersion = VERSION_LEGACY
        return dek
    }

    companion object {
        const val DEK_SIZE = 32

        const val VERSION_LEGACY = 1
        const val VERSION_DEK = 2

        /** 包 DEK 用的 AAD，和加密密码字段用的不是同一个值。 */
        val AAD_DEK = "dek-v1".toByteArray(Charsets.UTF_8)
    }
}
