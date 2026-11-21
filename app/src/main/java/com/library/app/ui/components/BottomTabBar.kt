package com.library.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.library.app.ui.nav.Section
import com.library.app.i18n.tr

/**
 * 悬浮胶囊：不贴边、不占满宽度，浮在列表上方。
 * 三个入口是带上限的 —— 再多就只能去掉文字了。
 */
@Composable
fun BottomTabBar(
    selected: Section?,
    onSelect: (Section) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.height(BarHeight),
        shape = RoundedCornerShape(BarHeight / 2),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 8.dp,
        border = BorderStroke(
            width = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
        ),
    ) {
        Row(
            // 不写 fillMaxSize：宽度由三个药丸撑出来，胶囊才是「小」的
            modifier = Modifier.fillMaxHeight().padding(horizontal = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            TabItem("资料库".tr(), AppIcons.Archive, selected == Section.LIBRARY) { onSelect(Section.LIBRARY) }
            TabItem("记录".tr(), AppIcons.List, selected == Section.RECORDS) { onSelect(Section.RECORDS) }
            TabItem("密码".tr(), AppIcons.Key, selected == Section.PASSWORDS) { onSelect(Section.PASSWORDS) }
        }
    }
}

private val BarHeight = 54.dp
private val PillWidth = 58.dp
private val PillHeight = 46.dp

/**
 * 一个入口就是一整个药丸：图标在上、文字在下，选中态的底色、点击涟漪、
 * 可点范围三者是同一个矩形 —— 不能只框住图标，不然看着就错位。
 */
@Composable
private fun TabItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(PillWidth)
            .height(PillHeight)
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
            )
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            textAlign = TextAlign.Center,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}
