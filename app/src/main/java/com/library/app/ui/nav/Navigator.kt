package com.library.app.ui.nav

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 自己维护的返回栈。没引 navigation-compose（本机缓存里没有，且这套需求不需要深层链接）。
 */
@Stable
class Navigator(start: Route) {

    private val stack = mutableStateListOf(start)

    var current: Route by mutableStateOf(start)
        private set

    /** 每次进出页面自增，页面用它来触发重新查库。 */
    var revision by mutableStateOf(0)
        private set

    fun push(route: Route) {
        stack.add(route)
        current = route
        revision++
    }

    /** 返回 true 表示消费了返回事件；false 表示已经在栈底，交给系统处理。 */
    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        current = stack.last()
        revision++
        return true
    }

    /** 从编辑器返回时用：一路弹到指定路由类型。 */
    fun popTo(predicate: (Route) -> Boolean): Boolean {
        while (stack.size > 1) {
            stack.removeAt(stack.lastIndex)
            if (predicate(stack.last())) {
                current = stack.last()
                return true
            }
        }
        current = stack.last()
        return false
    }

    fun resetTo(route: Route) {
        stack.clear()
        stack.add(route)
        current = route
        revision++
    }
}
