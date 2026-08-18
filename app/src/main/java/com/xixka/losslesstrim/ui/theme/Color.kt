package com.xixka.losslesstrim.ui.theme

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Blue Light UI 颜色 token（固定浅色方案）。
 * 语义色（success/warning/error）只表达状态，禁止当品牌色使用。
 */

object BlExt {
    val success = Color(0xFF34C759)
    val warning = Color(0xFFFF9500)
    val error = Color(0xFFBA1A1A)
    val errorContainer = Color(0xFFFFDAD6)
    val onErrorContainer = Color(0xFF410002)

    val textPrimary = Color(0xFF1A1C1E)
    val textSecondary = Color(0xFF44474E)
    val textDisabled = Color(0xFF74777F)

    val scrim = Color(0x99000000)
}

val BlBg = Color(0xFFF7F7F9)
val BlSurface = Color(0xFFFFFFFF)
val BlSurfaceVariant = Color(0xFFE6E8EB)
val BlPrimary = Color(0xFF91C6FF)
val BlOnPrimary = Color(0xFF001D36)
val BlPrimaryContainer = Color(0xFFCFE6FF)
val BlOnPrimaryContainer = Color(0xFF001D36)
val BlSecondary = Color(0xFF5B7CC4)
val BlOnSecondary = Color(0xFFFFFFFF)
val BlSecondaryContainer = Color(0xFFCDE7F2)
val BlOnSecondaryContainer = Color(0xFF003543)
val BlOutline = Color(0xFF44474E)
val BlOutlineVariant = Color(0xFF74777F)
val BlInverseSurface = Color(0xFF2F3033)

val BlLightColors = lightColorScheme(
    primary = BlPrimary,
    onPrimary = BlOnPrimary,
    primaryContainer = BlPrimaryContainer,
    onPrimaryContainer = BlOnPrimaryContainer,
    secondary = BlSecondary,
    onSecondary = BlOnSecondary,
    secondaryContainer = BlSecondaryContainer,
    onSecondaryContainer = BlOnSecondaryContainer,
    tertiary = Color(0xFF7D5260),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD9E2),
    onTertiaryContainer = Color(0xFF31111D),
    background = BlBg,
    onBackground = BlExt.textPrimary,
    surface = BlSurface,
    onSurface = BlExt.textPrimary,
    surfaceVariant = BlSurfaceVariant,
    onSurfaceVariant = BlExt.textSecondary,
    outline = BlOutline,
    outlineVariant = BlOutlineVariant,
    error = BlExt.error,
    onError = Color(0xFFFFFFFF),
    errorContainer = BlExt.errorContainer,
    onErrorContainer = BlExt.onErrorContainer,
    inverseSurface = BlInverseSurface,
)
