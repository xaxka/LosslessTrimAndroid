package com.xixka.losslesstrim.ui

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton as OutlinedButtonM3
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.xixka.losslesstrim.ui.theme.BlExt
import com.xixka.losslesstrim.ui.theme.BlMono
import com.xixka.losslesstrim.ui.theme.BlSurfaceVariant
import com.xixka.losslesstrim.util.Formats
import com.xixka.losslesstrim.util.ThumbStore
import kotlinx.coroutines.delay
import kotlin.math.roundToLong

/**
 * Blue Light UI 基础组件。
 * 层级用色阶表达：页面内卡片 = 白底 + 1dp outlineVariant 描边 + 12dp 圆角，无阴影。
 */

/** 区块卡片（描边卡）：白底 + 1dp 描边 + 12dp 圆角 */
@Composable
fun SectionCard(
    title: String?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    content: @Composable () -> Unit,
) {
    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = BlExt.textSecondary)
                }
            }
            content()
        }
    }
}

/** 灰底统计卡（surfaceVariant 底、无描边），用于结果页统计 */
@Composable
fun StatCard(count: String, label: String, color: Color, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = BlSurfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(count, style = MaterialTheme.typography.headlineSmall, color = color)
            Spacer(Modifier.height(2.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = BlExt.textSecondary)
        }
    }
}

/** 统一空状态：72dp 图标（主色 60%）→ 标题 → 副标题 → 两个引导按钮 */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Default.PlayArrow,
    subtitle: String? = null,
    buttonText: String? = null,
    onButtonClick: (() -> Unit)? = null,
    button2Text: String? = null,
    onButton2Click: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
        )
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = BlExt.textSecondary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        if (buttonText != null && onButtonClick != null) {
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = onButtonClick) {
                Text(buttonText, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        if (button2Text != null && onButton2Click != null) {
            FilledTonalButton(onClick = onButton2Click) {
                Text(button2Text, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

/** 状态徽章：语义色文字 + 轻量底 */
@Composable
fun StatusTag(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** 下拉选择行（只读 + 箭头），触控目标 ≥44dp */
@Composable
fun ChoiceField(
    label: String,
    options: List<String>,
    selected: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable { expanded = true }
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = BlExt.textSecondary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                options.getOrElse(selected) { "?" },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(20.dp),
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEachIndexed { i, opt ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                opt,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (i == selected) MaterialTheme.colorScheme.secondary else BlExt.textPrimary,
                            )
                        },
                        onClick = {
                            expanded = false
                            onSelect(i)
                        },
                    )
                }
            }
        }
    }
}

/** 视频缩略图（surfaceVariant 占位）；走 ThumbStore 全局缓存，二次进入页面秒出、不重复抽帧 */
@Composable
fun VideoThumb(uri: android.net.Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val key = remember(uri) { ThumbStore.keyOf(uri) }
    var bmp by remember(uri) { mutableStateOf(ThumbStore.peek(key)) }
    LaunchedEffect(key) {
        if (bmp == null) {
            // 列表缩略图显示 96x54dp，按最高密度 ~3x 出 288px 宽小图即可
            bmp = ThumbStore.thumb(context, key, uri, timeMs = 0L, maxPx = 288)
        }
    }
    Box(
        modifier = modifier
            .size(width = 96.dp, height = 54.dp)
            .clip(MaterialTheme.shapes.small)
            .background(BlSurfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(width = 96.dp, height = 54.dp),
            )
        } else {
            Text("…", color = BlExt.textDisabled, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** 切点处抽帧预览；走 ThumbStore 缓存（同一时间点不重复抽帧、只出小图，杜绝拖动时内存暴涨） */
@Composable
fun FramePreview(uri: android.net.Uri, tSec: Double, label: String, timeLabel: String) {
    val context = LocalContext.current
    val timeMs = (tSec * 1000.0).roundToLong()
    val key = remember(uri, timeMs) { ThumbStore.keyOf(uri, timeMs) }
    var bmp by remember(uri, timeMs) { mutableStateOf(ThumbStore.peek(key)) }
    LaunchedEffect(key) {
        if (bmp == null) {
            // 拖动切点时 tSec 高频变化：先挂起 200ms，跳过中间值只抽最后一帧
            delay(200)
            if (bmp == null) {
                // 预览窗口 128x72dp，按最高密度出 384px 宽小图足够
                bmp = ThumbStore.thumb(context, key, uri, timeMs, maxPx = 384)
            }
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(width = 128.dp, height = 72.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(BlSurfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            val b = bmp
            if (b != null) {
                Image(
                    bitmap = b.asImageBitmap(),
                    contentDescription = label,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(width = 128.dp, height = 72.dp),
                )
            } else {
                Text("加载中…", color = BlExt.textDisabled, style = MaterialTheme.typography.labelSmall)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(timeLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
    }
}

/**
 * 视频预览面板（LosslessCut 简单模式）。
 * 五按钮：上一关键帧 / 设置开始 / 播放暂停 / 设置结束 / 下一关键帧。
 * Playhead ≠ Start ≠ End ≠ Keyframe 四个概念独立：
 *  - 关键帧按钮只移动播放位置，不改切点；
 *  - 设开始/结束只写 segment，不动播放位置。
 * seekRequest 外部（时间轴）驱动定位；onPositionChange 上报播放位置（供时间轴 Playhead 同步）。
 */
@Composable
fun VideoPlayerPanel(
    uri: android.net.Uri,
    startSec: Double,
    endSec: Double,
    keyframes: List<Double>,
    onSetStart: (Double) -> Unit,
    onSetEnd: (Double) -> Unit,
    onPositionChange: (Double) -> Unit,
    seekRequest: Long,          // 毫秒，变化时 seek 到该位置
) {
    val context = LocalContext.current
    var playing by remember { mutableStateOf(false) }
    var prepared by remember { mutableStateOf(false) }
    var posMs by remember { mutableStateOf(0L) }
    var durMs by remember { mutableStateOf(0L) }

    val player = remember(uri) { MediaPlayer() }
    // 预览加载失败（MediaPlayer 不支持的容器等）：提示但不崩溃，剪辑处理不受影响
    var loadError by remember(uri) { mutableStateOf(false) }

    DisposableEffect(uri) {
        player.setOnPreparedListener { mp ->
            prepared = true
            durMs = mp.duration.toLong().coerceAtLeast(0)
            mp.seekTo((startSec * 1000).toInt().coerceAtLeast(0))
            posMs = (startSec * 1000).toLong().coerceAtLeast(0)
            onPositionChange(posMs / 1000.0)
        }
        player.setOnCompletionListener {
            it.seekTo(0)
            playing = false
            // 播放结束回到开头：同步一次位置，否则时间轴 Playhead 卡在片尾
            posMs = 0L
            onPositionChange(0.0)
        }
        player.setOnErrorListener { _, _, _ ->
            loadError = true
            prepared = false
            true
        }
        try {
            player.setDataSource(context, uri)
            player.prepareAsync()
        } catch (e: Exception) {
            // ffprobe 能解析 ≠ MediaPlayer 能播（如部分 ts/mkv/wmv/ogv），失败只禁用预览
            loadError = true
        }
        onDispose {
            try {
                player.stop()
            } catch (_: Exception) {
            }
            player.release()
        }
    }

    // 播放中轮询位置（Playhead 自动移动）
    LaunchedEffect(playing, prepared) {
        while (playing && prepared) {
            val p = try { player.currentPosition.toLong() } catch (_: Exception) { posMs }
            posMs = p
            onPositionChange(p / 1000.0)
            kotlinx.coroutines.delay(100)
        }
    }

    // 外部 seek（时间轴点击/拖动 Playhead）
    LaunchedEffect(seekRequest) {
        if (seekRequest >= 0 && prepared) {
            try {
                player.seekTo(seekRequest.toInt())
                posMs = seekRequest
                onPositionChange(seekRequest / 1000.0)
            } catch (_: Exception) {
            }
        }
    }

    fun doSeek(sec: Double) {
        val ms = (sec * 1000).toLong().coerceIn(0L, if (durMs > 0) durMs else Long.MAX_VALUE)
        try {
            player.seekTo(ms.toInt())
            posMs = ms
            onPositionChange(ms / 1000.0)
        } catch (_: Exception) {
        }
    }

    fun togglePlay() {
        if (!prepared) return
        try {
            if (playing) {
                player.pause()
                playing = false
            } else {
                player.start()
                playing = true
            }
        } catch (_: Exception) {
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // 画面
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(MaterialTheme.shapes.medium)
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            // key(uri)：uri 变化时重建 TextureView，让新的 MediaPlayer 重新绑定 surface
            key(uri) {
                AndroidView(
                    factory = { ctx ->
                        TextureView(ctx).also { tv ->
                            tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                                    try { player.setSurface(Surface(st)) } catch (_: Exception) {}
                                }
                                override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {}
                                override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                                    try { player.setSurface(null) } catch (_: Exception) {}
                                    return true
                                }
                                override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (loadError) {
                Text(
                    "该格式无法预览（不影响剪辑处理）",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                )
            } else if (!prepared) {
                Text("加载视频中…", color = Color.White, style = MaterialTheme.typography.labelMedium)
            } else {
                // 当前播放位置（精确到毫秒）
                Text(
                    Formats.msFull(posMs),
                    color = Color.White.copy(alpha = 0.95f),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.45f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        // 五按钮（图标版，播放居中）：上一关键帧 | 设置开始 | 播放/暂停 | 设置结束 | 下一关键帧
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val posSec = posMs / 1000.0
            // ① 上一关键帧：只移动播放位置，不改切点
            FilledTonalIconButton(
                onClick = {
                    val prev = keyframes.filter { it < posSec - 0.05 }.maxOrNull() ?: 0.0
                    doSeek(prev)
                },
                enabled = prepared && keyframes.isNotEmpty(),
            ) { Icon(Icons.Default.SkipPrevious, contentDescription = "上一关键帧") }
            // ② 设置开始：segment.start = playhead
            FilledTonalIconButton(
                onClick = { onSetStart(posSec) },
                enabled = prepared,
            ) { Icon(Icons.Default.Bookmark, contentDescription = "设置起点") }
            // ③ 播放/暂停（正中央，主色强调）
            FilledIconButton(onClick = { togglePlay() }, enabled = prepared) {
                Icon(
                    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (playing) "暂停" else "播放",
                )
            }
            // ④ 设置结束：segment.end = playhead
            FilledTonalIconButton(
                onClick = { onSetEnd(posSec) },
                enabled = prepared,
            ) { Icon(Icons.Default.BookmarkBorder, contentDescription = "设置终点") }
            // ⑤ 下一关键帧：只移动播放位置，不改切点
            FilledTonalIconButton(
                onClick = {
                    val next = keyframes.filter { it > posSec + 0.05 }.minOrNull()
                        ?: (durMs / 1000.0)
                    doSeek(next)
                },
                enabled = prepared && keyframes.isNotEmpty(),
            ) { Icon(Icons.Default.SkipNext, contentDescription = "下一关键帧") }
        }
        Text(
            "跳转按钮只移动播放位置；书签按钮设定剪辑区间起止",
            style = MaterialTheme.typography.labelSmall,
            color = BlExt.textSecondary,
        )
    }
}

/** 等宽技术文本（文件名/路径） */
@Composable
fun MonoText(text: String, modifier: Modifier = Modifier, color: Color = BlExt.textSecondary) {
    Text(
        text = text,
        style = BlMono,
        color = color,
        modifier = modifier,
    )
}

/**
 * 文字/描边类按钮统一用 secondary（中蓝）作文字色：
 * primary #91C6FF 过浅，白底上对比度不足，仅作填充/容器色。
 */
@Composable
fun BlTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.textButtonColors(
            contentColor = MaterialTheme.colorScheme.secondary
        ),
        content = content,
    )
}

@Composable
fun BlOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    OutlinedButtonM3(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.secondary
        ),
        content = content,
    )
}

