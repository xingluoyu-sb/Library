package com.library.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.Checkbox
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.library.app.data.files.formatSize
import com.library.app.data.model.Entry
import com.library.app.data.model.EntryType
import com.library.app.i18n.tr

/**
 * 各列表页共用的条目行：类型图标 + 标题 + 一行摘要 + 收藏。
 * 标题允许为空，为空时用正文首行顶上。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EntryListRow(
    entry: Entry,
    onClick: () -> Unit,
    onFavorite: (() -> Unit)? = null,
    trailingLabel: String? = null,
    warning: String? = null,
    thumbUri: String? = null,
    selected: Boolean = false,
    selectable: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val title = entry.displayTitle.ifBlank { defaultTitle(entry) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                else androidx.compose.ui.graphics.Color.Transparent,
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = if (selectable) 6.dp else 20.dp, end = 8.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectable) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onClick() },
                modifier = Modifier.size(40.dp),
            )
        }
        if (thumbUri != null) {
            FileThumb(uriString = thumbUri, size = 36.dp)
        } else {
            Avatar(color = MaterialTheme.colorScheme.surfaceVariant, size = 36.dp) {
                Icon(
                    imageVector = AppIcons.forEntry(entry.type),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = warning ?: entrySubtitle(entry)
            if (subtitle.isNotEmpty() || trailingLabel != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = listOfNotNull(subtitle.takeIf { it.isNotEmpty() }, trailingLabel)
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (warning != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onFavorite != null) {
            IconButton(onClick = onFavorite, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = if (entry.isFavorite) AppIcons.Star else AppIcons.StarOutline,
                    contentDescription = if (entry.isFavorite) "取消收藏".tr() else "收藏".tr(),
                    tint = if (entry.isFavorite) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        trailing?.invoke()
    }
}

private fun defaultTitle(entry: Entry): String = when (entry.type) {
    EntryType.PASSWORD -> "未命名密码".tr()
    EntryType.FILE -> entry.fileName ?: "未命名文件".tr()
    EntryType.RECORD -> "无标题记录".tr()
}

fun entrySubtitle(entry: Entry): String = when (entry.type) {
    EntryType.PASSWORD -> entry.url?.takeIf { it.isNotBlank() }
        ?: bodyPreview(entry.body, 1).ifEmpty { "已加密保存".tr() }

    EntryType.RECORD -> {
        // 有标题时摘要显示正文；没标题时标题位已经是正文首行了，这里补第 2 行
        val preview = if (entry.title.isBlank()) {
            bodyPreview(entry.body?.lineSequence()?.drop(1)?.joinToString("\n"), 1)
        } else {
            bodyPreview(entry.body, 1)
        }
        preview.ifEmpty { "空白记录".tr() }
    }

    EntryType.FILE -> listOfNotNull(
        entry.fileName,
        formatSize(entry.fileSize).takeIf { it.isNotEmpty() },
    ).joinToString(" · ")
}
