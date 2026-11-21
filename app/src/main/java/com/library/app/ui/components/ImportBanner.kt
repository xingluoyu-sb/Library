package com.library.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.library.app.data.files.ImportRunner
import com.library.app.i18n.tr

/**
 * 全局导入进度条：挂在根布局上，所以切页、返回都不会消失。
 * 以前进度弹窗长在页面里，页面一走导入就跟着被取消。
 */
@Composable
fun ImportBanner(
    state: ImportRunner.State,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.running) return

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    text = state.title,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
                Text(
                    text = if (state.detail.isBlank()) {
                        "准备中…".tr()
                    } else {
                        "${state.folders} 个文件夹 · ${state.files} 个文件 · ${state.detail}".tr()
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onCancel) { Text("取消".tr()) }
        }
    }
}
