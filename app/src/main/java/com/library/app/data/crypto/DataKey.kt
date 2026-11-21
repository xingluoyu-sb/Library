package com.library.app.data.crypto

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.annotation.WorkerThread
import com.library.app.data.db.AppDatabase
import com.library.app.data.db.Schema
import com.library.app.i18n.tr

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
 *
 * **绝不在 version = 2 时新建密钥。** 那意味着库里的 `v2:` 密文全都指向一个已经
 * 拿不到的 DEK，此时唯一正确的动作是报 [DekUnavailable] 让用户去恢复备份；
 * 覆盖掉旧的包裹记录只会把「还能救」变成「永远救不回」。
 */
class DataKey(
    private val context: Context,
    private val database: AppDatabase,
) {

    /**
     * 恢复备份时用的「已经包好的 DEK」，必须在覆盖原库之前拿到。
     *
     * [sourceVersion] 是**源库**的 crypto_version，恢复后要原样写回去：
     * 备份是在迁移还没跑完的库上导出的（版本 1、entries 还是 `v1:` 密文），
     * 恢复时若一律写成 2，读取路径就会要求 `v2:` 前缀而整片报错，
     * 迁移入口又会因为「已经是 2」直接返回，通道永久关闭。
     */
    class Adoption internal constructor(
        internal val wrapped: String,
        val sourceVersion: Int,
    )

    /** 本机拿不到数据密钥，且库已经迁移到 DEK 版本：只能靠备份恢复。 */
    class DekUnavailable(message: String) : Exception(message)

    /** 库里那条包裹记录的实际状态。**只有「根本没有这一行」才允许新建**。 */
    internal sealed interface Stored {
        data class Found(val dek: ByteArray) : Stored
        /** 表是空的，从来没有生成过 DEK。 */
        data object Absent : Stored
        /** 有这一行，但本机 Keystore 解不开（换机 / 密钥被作废 / 记录被改坏）。 */
        data object Unreadable : Stored
    }

    private val box = CryptoBox(context)

    @Volatile
    private var cached: ByteArray? = null

    @Volatile
    private var cachedVersion: Int? = null

    /** 1 = 旧 Keystore 密文；2 = DEK 密文。没有记录也算 1（老库刚升级上来的状态）。 */
    @WorkerThread
    fun version(): Int = versionOrNull() ?: VERSION_LEGACY

    @WorkerThread
    private fun versionOrNull(): Int? {
        cachedVersion?.let { return it }
        return runCatching {
            database.readableDatabase.rawQuery(
                "SELECT crypto_version FROM ${Schema.TABLE_CRYPTO_KEYS} WHERE id = 1",
                null,
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else VERSION_LEGACY }
        }.getOrNull()?.also { cachedVersion = it }
    }

    /**
     * 拿到这次要用的 DEK。
     *
     * 只有「`crypto_keys` 里根本没有这一行」才会新建。**有行就绝不再写**：
     * 那一行可能是换机后解不开的旧包裹，覆盖它就是把它彻底毁掉。
     * 版本 2 又没有可用密钥时抛 [DekUnavailable]，把用户引到备份恢复上。
     */
    @WorkerThread
    fun key(): ByteArray {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val version = versionOrNull() ?: throw DekUnavailable(UNAVAILABLE_UNREADABLE.tr())
            val dek = resolve(version, readStored()) { create() }
            cached = dek
            return dek
        }
    }

    @WorkerThread
    fun loadOrNull(): ByteArray? = cached ?: (readStored() as? Stored.Found)?.dek

    /**
     * 恢复备份用，**必须在覆盖原库之前调用**：先把容器里的 DEK 用本机 Keystore 包好，
     * 失败就在这里返回，原库一个字节都没动。包好之后落库只剩一条 INSERT，失败面小得多。
     */
    @WorkerThread
    fun prepareAdoption(dek: ByteArray, sourceVersion: Int): Adoption {
        require(dek.size == DEK_SIZE) { "数据密钥长度不对" }
        val version = adoptedVersion(sourceVersion)
        if (box.ensureKey() == CryptoBox.KeyState.FAILED) {
            throw DekUnavailable(UNAVAILABLE_KEYSTORE.tr())
        }
        val wrapped = runCatching { box.encryptBytes(dek, AAD_DEK) }.getOrElse {
            throw DekUnavailable(UNAVAILABLE_KEYSTORE.tr())
        }
        return Adoption(wrapped, version)
    }

    /**
     * 把 [prepareAdoption] 包好的 DEK 写进刚落盘的新库。
     * 这里直接对库文件动手，不能用 [database]（它已经被 close 了）。
     */
    @WorkerThread
    fun adoptInto(db: SQLiteDatabase, adoption: Adoption) {
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
        // 全项目唯一允许覆盖包裹记录的地方：新库是从别的设备带过来的，
        // 那行里包的是上一台机器的 DEK，本机永远解不开，接管它就是这里的目的。
        // 版本号跟着源库走，见 [Adoption.sourceVersion]：这里写死 2 会把没迁完的库锁死
        db.execSQL(
            "INSERT OR REPLACE INTO ${Schema.TABLE_CRYPTO_KEYS} " +
                "(id, wrapped_dek, crypto_version, created_at) VALUES (1, ?, ?, ?)",
            arrayOf<Any>(adoption.wrapped, adoption.sourceVersion, System.currentTimeMillis()),
        )
        // 密钥换了，缓存里的旧 DEK 一条都不能留，否则重启前这几秒会用错钥匙
        cached = null
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

    /** 读库里包着的 DEK，并区分「没有这行」和「有行但解不开」。 */
    @WorkerThread
    private fun readStored(): Stored = runCatching {
        database.readableDatabase.rawQuery(
            "SELECT wrapped_dek FROM ${Schema.TABLE_CRYPTO_KEYS} WHERE id = 1",
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use Stored.Absent
            val dek = runCatching { box.decryptBytes(cursor.getString(0), AAD_DEK) }.getOrNull()
            if (dek == null) Stored.Unreadable else Stored.Found(dek)
        }
    }.getOrDefault(Stored.Unreadable)

    @WorkerThread
    private fun create(): ByteArray {
        val dek = AesGcm.randomBytes(DEK_SIZE)
        // 故意用 INSERT 而不是 INSERT OR REPLACE：真有一行就让它撞主键约束直接失败。
        // 「有行就绝不再写」不能靠调用方自觉，要让数据库来兜底
        database.writableDatabase.execSQL(
            "INSERT INTO ${Schema.TABLE_CRYPTO_KEYS} " +
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

        private const val UNAVAILABLE_LOST =
            "这台设备上的数据密钥已经失效，之前加密的密码解不开了。请用之前导出的加密备份恢复（设置 → 数据与备份）。"
        private const val UNAVAILABLE_UNREADABLE =
            "读不到数据密钥的记录，数据库可能损坏。重启应用再试；如果一直这样，请用加密备份恢复。"
        private const val UNAVAILABLE_STALE_ROW =
            "本机存的密钥记录读不出来，不能覆盖它，暂时也存不了新密码。重启应用再试；如果一直这样，请用加密备份恢复。"
        private const val UNAVAILABLE_KEYSTORE =
            "本机的密钥系统不可用，没法接管这份备份。请重启设备后再试一次。"

        /**
         * 恢复后写回 `crypto_keys.crypto_version` 的值：源库是几就是几，**绝不顺手升到 2**。
         * 单独拆出来是为了能在 JVM 单测里钉住（这个模块的单测不跑 SQLite）。
         */
        internal fun adoptedVersion(sourceVersion: Int): Int {
            require(sourceVersion == VERSION_LEGACY || sourceVersion == VERSION_DEK) {
                "源库版本号不认识：$sourceVersion"
            }
            return sourceVersion
        }

        /**
         * 拿到 DEK 的唯一决策点，纯函数，单测专门钉住这几条：
         *
         * - 读到了就用；
         * - **有行但解不开 → 一律报错**，不管版本号是几（版本 1 时那行也没用了，
         *   但删了换新的同样属于「写」，宁可让用户看见）；
         * - 只有完全没有这一行、并且版本还不是 2，才允许新建。
         */
        internal fun resolve(version: Int, stored: Stored, create: () -> ByteArray): ByteArray =
            when (stored) {
                is Stored.Found -> stored.dek
                Stored.Absent ->
                    if (version == VERSION_DEK) throw DekUnavailable(UNAVAILABLE_LOST.tr()) else create()
                Stored.Unreadable ->
                    throw DekUnavailable(
                        (if (version == VERSION_DEK) UNAVAILABLE_LOST else UNAVAILABLE_STALE_ROW).tr(),
                    )
            }
    }
}
