package com.library.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val default = Typography()

val LibraryTypography = Typography(
    headlineMedium = default.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    headlineSmall = default.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = default.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = default.titleMedium.copy(fontWeight = FontWeight.Medium),
    bodyLarge = default.bodyLarge.copy(lineHeight = 24.sp),
    bodyMedium = default.bodyMedium.copy(lineHeight = 21.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
)
