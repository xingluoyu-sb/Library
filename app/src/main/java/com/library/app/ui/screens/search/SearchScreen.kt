@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.library.app.ui.screens.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.library.app.data.SearchResult
import com.library.app.data.crypto.CryptoBox
import com.library.app.data.model.Entry
import com.library.app.data.model.EntryType
import com.library.app.data.model.Folder
import com.library.app.data.model.Module
import com.library.app.ui.LocalRepository
import com.library.app.ui.LocalSecurityPrefs
import com.library.app.ui.LocalSession
import com.library.app.ui.components.AppIcons
import com.library.app.ui.components.EmptyState
import com.library.app.ui.components.EntryListRow
import com.library.app.ui.components.LibraryTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.library.app.i18n.tr

@Composable
fun SearchScreen(
    onOpenModule: (Long) -> Unit,
    onOpenGroup: (Folder) -> Unit,
    onOpenEntry: (Entry) -> Unit,
    onBack: () -> Unit,
) {
    val repository = LocalRepository.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val crypto = remember(context) { CryptoBox(context) }
    val prefs = LocalSecurityPrefs.current
    val session = LocalSession.current
    var query by remember { mutableStateOf("") }
    var result by remember { mutableStateOf(SearchResult()) }

    // 会话内缓存明文：每输入一个字就重新解密全部密码，条目一多就会卡
    val plainCache = remember { HashMap<Long, String>() }

    LaunchedEffect(query) {
        val needle = query.trim()
        if (needle.isEmpty()) {
            result = SearchResult()
            return@LaunchedEffect
        }
        // 打字防抖：停手 250ms 才真正查一次
        delay(250L)

        val base = repository.search(needle)
        val allowPasswordSearch =
            !prefs.requireAuthToViewPassword || session.passwordVerified
        val passwordHits = if (!allowPasswordSearch) {
            emptyList()
        } else {
            withContext(Dispatchers.IO) {
                repository.listPasswords().filter { entry ->
                    base.entries.none { it.id == entry.id } &&
                        entry.secretCt?.let { ct ->
                            val plain = plainCache.getOrPut(entry.id) {
                                runCatching { crypto.decrypt(ct) }.getOrNull().orEmpty()
                            }
                            plain.contains(needle, ignoreCase = true)
                        } == true
                }
            }
        }
        result = if (passwordHits.isEmpty()) base else base.copy(entries = passwordHits + base.entries)
    }

    Scaffold(
        topBar = { LibraryTopBar(title = "搜索".tr(), onBack = onBack) },
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("资料库、分组、标题、账号、内容".tr()) },
                leadingIcon = { Icon(AppIcons.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            when {
                query.isBlank() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    EmptyState(
                        icon = AppIcons.Search,
                        title = "输入关键词开始搜索".tr(),
                        message = "资料库、分组、标题、账号、正文都能搜".tr(),
                    )
                }

                result.isEmpty -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    EmptyState(
                        icon = AppIcons.Search,
                        title = "没有匹配的内容".tr(),
                        message = "换个词试试".tr(),
                    )
                }

                else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    if (result.modules.isNotEmpty()) {
                        item { ResultHeader("资料库 · ${result.modules.size}".tr()) }
                        items(result.modules, key = { "m-${it.id}" }) { module ->
                            ModuleResultRow(module) { onOpenModule(module.id) }
                        }
                    }
                    if (result.folders.isNotEmpty()) {
                        item { ResultHeader("分组 / 文件夹 · ${result.folders.size}".tr()) }
                        items(result.folders, key = { "f-${it.id}" }) { folder ->
                            GroupResultRow(folder) { onOpenGroup(folder) }
                        }
                    }
                    if (result.entries.isNotEmpty()) {
                        item { ResultHeader("内容 · ${result.entries.size}".tr()) }
                        items(result.entries, key = { "e-${it.id}" }) { entry ->
                            EntryListRow(entry = entry, onClick = { onOpenEntry(entry) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun ModuleResultRow(module: Module, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(module.color)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = module.name.take(1),
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(module.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${module.entryCount} 项内容".tr(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            AppIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun GroupResultRow(folder: Folder, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            AppIcons.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(folder.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (folder.entryCount == 0) "空文件夹".tr() else "${folder.entryCount} 项".tr(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            AppIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(16.dp),
        )
    }
}
