package com.library.app.i18n

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 只有中英两种，tag 用来落盘。 */
enum class AppLanguage(val tag: String, val label: String) {
    ZH("zh", "中文"),
    EN("en", "English"),
}

/**
 * 当前界面语言。用状态存，切换语言后所有读过它的 Composable 会自己重组，
 * 不用重建 Activity（也就不会打断正在编辑的内容）。
 */
object Lang {

    var current by mutableStateOf(AppLanguage.ZH)
        private set

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("library.lang", Context.MODE_PRIVATE)
        prefs = p
        val saved = p.getString(KEY_LANG, null)
        current = AppLanguage.entries.firstOrNull { it.tag == saved } ?: AppLanguage.ZH
    }

    fun select(language: AppLanguage) {
        if (current == language) return
        current = language
        prefs?.edit()?.putString(KEY_LANG, language.tag)?.apply()
    }

    private const val KEY_LANG = "lang"
}

/**
 * 文案翻译：以中文原文为 key，查不到就原样返回。
 * 英文没覆盖到的地方会退成中文，不会出现空字符串。
 */
fun String.tr(): String {
    if (Lang.current == AppLanguage.ZH) return this
    EnStrings.map[this]?.let { return it }
    for ((regex, build) in EnStrings.patterns) {
        val match = regex.matchEntire(this) ?: continue
        return build(match)
    }
    return this
}
