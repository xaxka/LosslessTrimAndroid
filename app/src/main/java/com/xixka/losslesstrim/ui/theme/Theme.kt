package com.xixka.losslesstrim.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * Blue Light UI 主题：固定浅色、禁用动态取色（darkTheme=false / dynamicColor=false）。
 * token 唯一来源，见 Color.kt / Type.kt / Shape.kt。
 */
@Composable
fun LosslessTrimTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = BlLightColors,
        typography = BlTypography,
        shapes = BlShapes,
        content = content,
    )
}
