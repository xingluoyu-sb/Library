package com.library.app.data.crypto

import android.app.KeyguardManager
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import androidx.annotation.WorkerThread
import com.library.app.data.db.AppDatabase

sealed interface SecretResult {
    data class Ok(val value: String) : SecretResult
    data object Cancelled : SecretResult
    data class Failed(val message: String) : SecretResult
}

/**
 * 加解密入口。进入应用时已经验证过一次身份（锁屏页），所以保存/查看不再二次弹验证。
 *
 * 读路径**只看 [DataKey.version]**：
 * - `crypto_version = 1` → 密文必须是 `v1:`（Keystore 直加），否则报错；
 * - `crypto_version = 2` → 密文必须是 `v2:`（DEK 加密），否则报错。
 *
 * 绝不「试一种不行再换另一种」：那样一旦两套密钥同时存在，会静默读出错误结果。
 */
class SecretCrypto(
    private val context: Context,
    private val database: AppDatabase,
    private val dataKey: DataKey,
) {

    private val box = CryptoBox(context)

    fun keyState(): CryptoBox.KeyState = box.ensureKey()

    fun hasKey(): Boolean = box.hasKey()

    fun isSecureLockConfigured(): Boolean {
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return keyguard.isDeviceSecure
    }

    /** 不弹验证：保存时用。写哪种格式跟着当前 crypto_version 走，避免迁移窗口里写出混合版本。 */
    fun encryptNow(plain: String): SecretResult {
        if (plain.isEmpty()) return SecretResult.Ok("")
        return runCatching {
            if (dataKey.version() == DataKey.VERSION_DEK) {
                encryptWithDek(plain)
            } else {
                // 迁移还没完成，继续用老格式写，等迁移时一起转
                box.encrypt(plain)
            }
        }.fold(
            onSuccess = { SecretResult.Ok(it) },
            onFailure = {
                SecretResult.Failed("加密失败：${it.javaClass.simpleName} ${it.message.orEmpty()}".trim())
            },
        )
    }

    /** 不弹验证：查看时用。 */
    fun decryptNow(blob: String): SecretResult {
        if (blob.isEmpty()) return SecretResult.Ok("")
        val version = dataKey.version()
        return runCatching {
            when (version) {
                DataKey.VERSION_DEK -> {
                    require(blob.startsWith(PREFIX_V2)) { "密文是旧格式，但库版本已是 2" }
                    decryptWithDek(blob)
                }
                else -> {
                    require(blob.startsWith(CryptoBox.PREFIX)) { "密文是新格式，但库版本还是 1" }
                    box.decrypt(blob)
                }
            }
        }.fold(
            onSuccess = { SecretResult.Ok(it) },
            onFailure = {
                SecretResult.Failed("解密失败：${it.javaClass.simpleName} ${it.message.orEmpty()}".trim())
            },
        )
    }

    /**
     * 把历史遗留的 `v1:` 密文整体改写成 `v2:`，并在**同一个事务**里把 crypto_version 提到 2。
     *
     * - 任何一行失败 → 抛异常 → 整个事务回滚，版本号仍是 1，旧密文照旧可读；
     * - 迁移期间不会删除 Keystore 里的旧 key，否则回滚后就再也读不出旧密文了。
     *
     * @return 改写的条数；已经迁移过或无事可做时返回 0。
     */
    @WorkerThread
    fun migrateLegacyCiphertext(): Int {
        if (dataKey.version() == DataKey.VERSION_DEK) return 0

        val db = database.writableDatabase
        var migrated = 0
        // 读旧行、改写、改版本号必须在同一个事务里：
        // 否则「读完」到「提交」之间新写入的 v1 密文会被漏掉，而版本号已经跳到 2
        db.beginTransaction()
        try {
            val legacyRows = ArrayList<CipherRotator.Row>()
            var newFormatCount = 0
            db.rawQuery(
                "SELECT id, secret_ct FROM entries WHERE secret_ct IS NOT NULL AND secret_ct <> ''",
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val ct = cursor.getString(1) ?: continue
                    if (ct.startsWith(PREFIX_V2)) {
                        newFormatCount++
                    } else {
                        legacyRows.add(CipherRotator.Row(id, ct))
                    }
                }
            }

            // 版本 1 的库里出现 v2 密文，说明上次迁移只写了一半；报错让人看见，不猜
            check(newFormatCount == 0) {
                "库里有 $newFormatCount 条新格式密文，但 crypto_version 还是 1，状态不一致"
            }

            // DEK 还没有就现在建（写在同一事务里，回滚时一起消失）
            dataKey.key()

            if (legacyRows.isEmpty()) {
                dataKey.markMigrated(db)
                db.setTransactionSuccessful()
                return 0
            }

            // 旧 key 必须还在。没有就等于旧密文已经不可恢复，硬转会把它彻底毁掉
            check(box.hasKey()) { "Keystore 里没有旧密钥，无法迁移旧密文" }

            val rewritten = CipherRotator.rewrite(
                rows = legacyRows,
                decrypt = { box.decrypt(it) },
                encrypt = { encryptWithDek(it) },
            )

            rewritten.forEach { (id, ct) ->
                db.execSQL("UPDATE entries SET secret_ct = ? WHERE id = ?", arrayOf<Any>(ct, id))
            }
            dataKey.markMigrated(db)
            db.setTransactionSuccessful()
            migrated = rewritten.size
        } finally {
            db.endTransaction()
        }
        // 旧 alias 保留：删掉换不来任何好处，万一别处还留着 v1 密文，它就是最后的救命绳
        return migrated
    }

    private fun encryptWithDek(plain: String): String {
        val raw = AesGcm.encrypt(dataKey.key(), plain.toByteArray(Charsets.UTF_8), AAD_SECRET)
        return PREFIX_V2 + Base64.encodeToString(raw, Base64.NO_WRAP)
    }

    private fun decryptWithDek(blob: String): String {
        val raw = Base64.decode(blob.removePrefix(PREFIX_V2), Base64.NO_WRAP)
        return AesGcm.decrypt(dataKey.key(), raw, AAD_SECRET).toString(Charsets.UTF_8)
    }

    companion object {
        const val PREFIX_V2 = "v2:"

        /** 加密密码字段专用 AAD，和包 DEK 的 `dek-v1` 分开，改动即等于换协议。 */
        val AAD_SECRET = "secret-v2".toByteArray(Charsets.UTF_8)

        /** 仅供测试与诊断用，业务代码不要碰这张表。 */
        fun setVersionForTest(db: SQLiteDatabase, version: Int) {
            db.execSQL("UPDATE crypto_keys SET crypto_version = ? WHERE id = 1", arrayOf<Any>(version))
        }
    }
}
