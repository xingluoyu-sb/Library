package com.library.app.data.crypto

/**
 * 全量改写密文的纯逻辑，单独拎出来是为了能脱离 Android 做测试。
 *
 * 语义只有一条：**要么整批都转好，要么一条都不返回**。
 * 任何一行失败就抛异常，调用方据此回滚事务——不存在「跳过这一条继续」的分支。
 */
object CipherRotator {

    data class Row(val id: Long, val ciphertext: String)

    class Failed(val entryId: Long, cause: Throwable) :
        Exception("第 $entryId 条改写失败：${cause.message ?: cause.javaClass.simpleName}", cause)

    /**
     * @param rows 待改写的行，调用方保证都是旧格式
     * @param decrypt 旧格式解密，失败必须抛异常而不是返回占位内容
     * @param encrypt 新格式加密
     * @return 与 [rows] 顺序、条数一一对应的新密文
     */
    fun rewrite(
        rows: List<Row>,
        decrypt: (String) -> String,
        encrypt: (String) -> String,
    ): List<Pair<Long, String>> {
        val result = ArrayList<Pair<Long, String>>(rows.size)
        rows.forEach { row ->
            val plain = try {
                decrypt(row.ciphertext)
            } catch (e: Exception) {
                throw Failed(row.id, e)
            }
            val upgraded = try {
                encrypt(plain)
            } catch (e: Exception) {
                throw Failed(row.id, e)
            }
            result.add(row.id to upgraded)
        }
        return result
    }
}
