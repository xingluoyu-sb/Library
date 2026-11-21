package com.library.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.library.app.i18n.tr

@Composable
fun TextInputDialog(
    title: String,
    label: String,
    initial: String = "",
    confirmText: String = "确定".tr(),
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    extra: (@Composable () -> Unit)? = null,
) {
    var value by remember {
        mutableStateOf(
            TextFieldValue(text = initial, selection = TextRange(0, initial.length)),
        )
    }
    val focusRequester = remember { FocusRequester() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(label) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                )
                if (extra != null) {
                    extra()
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (value.text.isNotBlank()) onConfirm(value.text.trim())
                },
                enabled = value.text.isNotBlank(),
            ) { Text(confirmText) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消".tr()) }
        },
    )

    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String = "删除".tr(),
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmText, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消".tr()) }
        },
    )
}

/**
 * 删除前先问一句：只移出资料库，还是连文件一起删。
 * 三个按钮挤一行，不做大卡片 —— 这个对话框出现的频率高，越省事越好。
 */
@Composable
fun DeleteChoiceDialog(
    title: String,
    name: String? = null,
    onMoveOut: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = name?.let {
            {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        confirmButton = {
            Row {
                TextButton(
                    onClick = onDismiss,
                    contentPadding = PaddingValues(horizontal = 10.dp),
                ) {
                    Text("取消".tr())
                }
                TextButton(
                    onClick = onMoveOut,
                    contentPadding = PaddingValues(horizontal = 10.dp),
                ) {
                    Text("移出资料库".tr())
                }
                TextButton(
                    onClick = onDelete,
                    contentPadding = PaddingValues(horizontal = 10.dp),
                ) {
                    Text("删除".tr(), color = MaterialTheme.colorScheme.error)
                }
            }
        },
    )
}
