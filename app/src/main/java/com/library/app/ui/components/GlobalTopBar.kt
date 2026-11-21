package com.library.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.library.app.ui.LocalBranding
import com.library.app.i18n.tr

/**
 * 一级页（资料库 / 记录 / 密码）的顶栏。
 *
 * 左：头像（点一下进设置）+ 昵称；中：标题真正居中；右：搜索 + 加号。
 * 加号的具体动作各页自己定，所以留一个插槽。
 */
@Composable
fun PrimaryTopBar(
    title: String,
    onAvatarClick: () -> Unit,
    onSearch: () -> Unit,
    extraAction: (@Composable () -> Unit)? = null,
    add: @Composable () -> Unit,
) {
    val branding = LocalBranding.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            // 自定义顶栏不像 TopAppBar 那样自带 inset，不让开状态栏就会被时间和电池压住
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(64.dp)
            .padding(horizontal = 8.dp),
    ) {
        Row(
            modifier = Modifier.align(Alignment.CenterStart),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AvatarBox(onClick = onAvatarClick)
            Text(
                text = branding.nickname,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 74.dp),
            )
        }

        Text(
            text = title,
            modifier = Modifier
                .align(Alignment.Center)
                .widthIn(max = 120.dp),
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            extraAction?.invoke()
            IconButton(onClick = onSearch) {
                Icon(AppIcons.Search, contentDescription = "搜索".tr())
            }
            add()
        }
    }
}

/** 头像方块：没换过图就显示占位字，不至于空一块。 */
@Composable
fun AvatarBox(onClick: () -> Unit) {
    val logo = LocalBranding.current.logo

    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (logo != null) {
            Image(
                bitmap = logo,
                contentDescription = "设置".tr(),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = "库".tr(),
                color = MaterialTheme.colorScheme.onPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
            )
        }
    }
}
