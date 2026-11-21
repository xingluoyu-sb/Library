package com.library.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File

/**
 * 左上角头像：裁剪旋转之后的方图存到应用私有目录。
 * 解码结果直接存成状态，换图后是新对象，界面一定会跟着刷新
 * （文件名不变，靠路径做 key 会漏掉第二次以后的修改）。
 */
class BrandingStore(private val context: Context) {

    private val file = File(context.filesDir, "brand_logo")

    private val prefs = context.applicationContext
        .getSharedPreferences("library.branding", Context.MODE_PRIVATE)

    var logo: ImageBitmap? by mutableStateOf(decode())
        private set

    /** 头像右边那个昵称，默认 User。 */
    var nickname by mutableStateOf(prefs.getString(KEY_NICKNAME, null) ?: DEFAULT_NICKNAME)
        private set

    fun updateNickname(value: String) {
        val clean = value.trim().ifBlank { DEFAULT_NICKNAME }
        nickname = clean
        prefs.edit().putString(KEY_NICKNAME, clean).apply()
    }

    /** 存裁剪好的方图。 */
    fun update(square: Bitmap): Boolean = runCatching {
        file.outputStream().use { out -> square.compress(Bitmap.CompressFormat.PNG, 100, out) }
        logo = decode()
        logo != null
    }.getOrDefault(false)

    fun reset() {
        runCatching { file.delete() }
        logo = null
    }

    private fun decode(): ImageBitmap? = runCatching {
        if (!file.exists()) return null
        BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
    }.getOrNull()

    companion object {
        const val DEFAULT_NICKNAME = "User"
        private const val KEY_NICKNAME = "nickname"
    }
}
