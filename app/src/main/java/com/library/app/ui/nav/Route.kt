package com.library.app.ui.nav

/** 编辑器只有两种形态：密码表单 / 记录表单。资料库里的文件不进编辑器。 */
enum class EditorMode { PASSWORD, RECORD }

/**
 * 底部胶囊里的三个入口。
 * 搜索和设置不在底部，走顶部右上角，以二级页的形式 push。
 */
enum class Section {
    LIBRARY,
    RECORDS,
    PASSWORDS,
}

sealed interface Route {
    data object Search : Route
    data object Passwords : Route
    data object Records : Route
    data object Library : Route
    data object Settings : Route

    /** 回收站：软删除的条目在这里躺 30 天。 */
    data object Trash : Route

    /** 再看一遍新手引导（从设置里进）。 */
    data object Guide : Route

    /** 重置密码：设置页验证通过后才进来，只负责收新密码。 */
    data object SetPassword : Route

    /** 内置查看器：图片 / 视频 / 纯文本 / Markdown。 */
    data class Viewer(val entryId: Long) : Route

    data class Module(val moduleId: Long, val folderId: Long? = null) : Route

    /** 记录里的一个分组。 */
    data class RecordGroup(val folderId: Long) : Route

    data class Editor(
        val moduleId: Long,
        val folderId: Long?,
        val entryId: Long?,
        val mode: EditorMode,
    ) : Route
}

/** 只有三个一级页在底部有位置，其余都是压栈进来的二级页，胶囊自动收起。 */
val Route.section: Section?
    get() = when (this) {
        Route.Library -> Section.LIBRARY
        Route.Records -> Section.RECORDS
        Route.Passwords -> Section.PASSWORDS
        else -> null
    }

fun Section.toRoute(): Route = when (this) {
    Section.LIBRARY -> Route.Library
    Section.RECORDS -> Route.Records
    Section.PASSWORDS -> Route.Passwords
}
