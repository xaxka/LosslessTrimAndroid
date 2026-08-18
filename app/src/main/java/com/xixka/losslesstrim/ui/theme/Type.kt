package com.xixka.losslesstrim.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Blue Light UI 排版 token：系统字体族，标题偏重、行高宽松，文字层级最多三级 */
val BlTypography = Typography(
    displaySmall = TextStyle(32.sp, 40.sp, FontWeight.Bold),
    headlineMedium = TextStyle(26.sp, 34.sp, FontWeight.SemiBold),
    headlineSmall = TextStyle(22.sp, 30.sp, FontWeight.SemiBold),
    titleLarge = TextStyle(20.sp, 28.sp, FontWeight.SemiBold),
    titleMedium = TextStyle(16.sp, 24.sp, FontWeight.Medium, letterSpacing = 0.15.sp),
    titleSmall = TextStyle(14.sp, 20.sp, FontWeight.Medium, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(16.sp, 24.sp, FontWeight.Normal, letterSpacing = 0.5.sp),
    bodyMedium = TextStyle(14.sp, 20.sp, FontWeight.Normal, letterSpacing = 0.25.sp),
    bodySmall = TextStyle(12.sp, 16.sp, FontWeight.Normal, letterSpacing = 0.4.sp),
    labelLarge = TextStyle(14.sp, 20.sp, FontWeight.Medium, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(12.sp, 16.sp, FontWeight.Medium, letterSpacing = 0.5.sp),
    labelSmall = TextStyle(11.sp, 16.sp, FontWeight.Medium, letterSpacing = 0.5.sp),
)

/** 技术文本（文件名/路径）等宽样式 */
val BlMono = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    lineHeight = 16.sp,
    fontWeight = FontWeight.Normal,
)
