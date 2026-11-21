package com.library.app.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 会话状态：只存在内存里，进程被回收就回到锁定态。
 */
@Stable
class SessionState {
    var unlocked by mutableStateOf(false)

    /** 设备没设锁屏密码、或指纹/密码都不可用时的降级模式：能看结构和笔记，密码字段不可解密。 */
    var readOnly by mutableStateOf(false)

    /**
     * 本轮会话里是否已经为「查看密码」单独验证过。
     * 设置里开了开关才用得上；锁屏、回后台超时都会把它清掉。
     */
    var passwordVerified by mutableStateOf(false)

    /** 仅 debug：从 adb 直接打开某个条目的查看器，方便无人工验证。 */
    var devViewEntryId: Long? = null

    fun markPasswordVerified() {
        passwordVerified = true
    }

    fun unlock() {
        unlocked = true
        readOnly = false
        passwordVerified = false
    }

    fun enterReadOnly() {
        unlocked = true
        readOnly = true
        passwordVerified = false
    }

    fun lock() {
        unlocked = false
        passwordVerified = false
    }
}
