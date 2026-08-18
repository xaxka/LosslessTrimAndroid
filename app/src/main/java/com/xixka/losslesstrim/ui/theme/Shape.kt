package com.xixka.losslesstrim.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** Blue Light UI 圆角 token：默认卡片 12dp；chip 6dp 在使用处显式指定 */
val BlShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

val BlChipShape = RoundedCornerShape(6.dp)
