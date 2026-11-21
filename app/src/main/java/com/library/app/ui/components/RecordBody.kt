package com.library.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.library.app.i18n.tr

/** 取正文里前 n 行非空内容，做成一行预览。 */
fun bodyPreview(body: String?, maxLines: Int = 2): String {
    if (body.isNullOrBlank()) return ""
    return body.lineSequence()
        .filter { it.isNotBlank() }
        .take(maxLines)
        .joinToString(" ")
        .trim()
}

/**
 * 正文预览：纯文本逐行显示，超出行数截断。
 * 旧版本把清单存成了 `- [ ] 文本`，这里只把标记剥掉，数据库内容不动。
 */
@Composable
fun RecordBodyPreview(
    body: String?,
    maxLines: Int,
    modifier: Modifier = Modifier,
) {
    if (body.isNullOrBlank()) {
        Text(
            text = "还没有内容".tr(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = modifier,
        )
        return
    }

    val visible = markdownPlain(body, maxLines).split("\n").map(::stripTodoMarker)

    Column(modifier = modifier) {
        visible.forEach { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
    }
}

private val TODO_MARKER = Regex("""^\s*(?:[-*+]\s*)?\[[ xX]]\s*""")

private fun stripTodoMarker(line: String): String =
    line.replace(TODO_MARKER, "").trim().ifEmpty { line.trim() }
