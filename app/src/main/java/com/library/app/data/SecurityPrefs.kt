package com.library.app.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 少量安全开关，落在独立的 SharedPreferences 里。
 * 值本身就是 Compose 状态，改完界面立刻跟着变，不用额外通知。
 */
class SecurityPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("library.security", Context.MODE_PRIVATE)

    /** 打开后，查看密码要先过指纹 / 锁屏密码。默认关（沿用之前的行为）。 */
    var requireAuthToViewPassword by mutableStateOf(prefs.getBoolean(KEY_REQUIRE_AUTH, false))
        private set

    fun updateRequireAuth(value: Boolean) {
        requireAuthToViewPassword = value
        prefs.edit().putBoolean(KEY_REQUIRE_AUTH, value).apply()
    }

    private companion object {
        const val KEY_REQUIRE_AUTH = "require_auth_to_view_password"
    }
}
