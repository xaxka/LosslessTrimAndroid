package com.xixka.losslesstrim.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xixka.losslesstrim.data.AlignStrategy
import com.xixka.losslesstrim.data.OutputContainer
import com.xixka.losslesstrim.ui.theme.BlSurfaceVariant
import com.xixka.losslesstrim.ui.theme.BlExt
import com.xixka.losslesstrim.util.Formats

/** 设置页：分组卡片（组标题 labelM 灰字 + 白卡内多行项） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsState()
    // 进入设置页时刷新缓存概览（估算 thumbs/ffmpeg-thumb/ 字节数 + Room 行数）
    LaunchedEffect(Unit) { vm.refreshCacheInfo() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("设置", style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            GroupLabel("剪辑")
            SectionCard(title = null) {
                ChoiceField(
                    label = "关键帧对齐策略",
                    options = listOf("多切", "少切（默认）", "自动"),
                    selected = settings.alignment.ordinal,
                ) { idx -> vm.updateSettings { s -> s.copy(alignment = AlignStrategy.entries[idx]) } }
                Text(
                    "多切：起点对齐后一个关键帧、终点对齐前一个（多砍一点，保证广告清零）。少切反之，怕误伤正片。",
                    style = MaterialTheme.typography.labelSmall,
                    color = BlExt.textSecondary,
                )
            }

            GroupLabel("输出")
            SectionCard(title = null) {
                ChoiceField(
                    label = "输出容器",
                    options = listOf("保持原容器", "MP4", "MKV"),
                    selected = settings.container.ordinal,
                ) { idx -> vm.updateSettings { s -> s.copy(container = OutputContainer.entries[idx]) } }
                Text(
                    "只换封装和扩展名，流仍 -c copy 不转码；MP4 启用 faststart。srt 字幕 / DTS 音频进 MP4 可能失败（明确报错，不静默丢轨），此类文件请改回 MKV 或原容器。",
                    style = MaterialTheme.typography.labelSmall,
                    color = BlExt.textSecondary,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                SwitchRow(
                    title = "覆盖原文件",
                    subtitle = if (settings.overwrite) "成功后删除原文件，原地替换，目录不多副本"
                    else "输出到 CutVideos/ 子目录，原文件保留",
                    checked = settings.overwrite,
                    onChange = { v -> vm.updateSettings { it.copy(overwrite = v) } },
                    danger = settings.overwrite,
                )
            }

            GroupLabel("扫描")
            SectionCard(title = null) {
                ChoiceField(
                    label = "结束时间超片长时",
                    options = listOf("按片尾截断", "跳过该文件"),
                    selected = if (settings.truncateOverlong) 0 else 1,
                ) { idx -> vm.updateSettings { it.copy(truncateOverlong = idx == 0) } }
                Text(
                    "仅扫描所选目录下的视频文件，不包含子目录。开始时间超片长时一律跳过该文件。",
                    style = MaterialTheme.typography.labelSmall,
                    color = BlExt.textSecondary,
                )
            }

            GroupLabel("预览")
            SectionCard(title = null) {
                // 两种解码方式二选一：原“FFmpeg 硬解（-hwaccel mediacodec copyback）”
                // 已移除——单帧抽帧负载下 GOP 中间帧每帧显存拷回 + JNI 轮询，实测
                // 比软解更慢且 10-bit 颜色不可靠，硬件路线由 MediaCodec 直解取代。
                ChoiceField(
                    label = "缩略图解码方式",
                    options = listOf("FFmpeg 软解（默认）", "MediaCodec 直解（实验）"),
                    selected = if (settings.mcDecodeThumbs) 1 else 0,
                ) { idx ->
                    vm.updateSettings { s -> s.copy(mcDecodeThumbs = idx == 1) }
                }
                Text(
                    "软解颜色最稳（默认）；直解绕开 FFmpeg 用系统硬解码器：10-bit 走 P010→8bit 转换、" +
                            "HDR 请求 tone-map，失败自动回退 FFmpeg 软解，结果可从下方诊断日志导出查看。",
                    style = MaterialTheme.typography.labelSmall,
                    color = BlExt.textSecondary,
                )
            }

            GroupLabel("维护")
            CacheSection(vm)
        }
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = BlExt.textSecondary,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )
}

/**
 * 清除缓存卡片：展示当前磁盘缓存占用（缩略图 JPEG + Probe Room 缓存估算），
 * 点击"清除缓存"清空 ThumbStore 内存 LruCache + 磁盘 thumbs/ffmpeg-thumb 临时目录 +
 * ProbeStore Room 缓存（probe/keyframe/near 三表） + Probe 进程内 keyframeCache，
 * 下次进入页面重新抽帧/探测。修复前的花屏画面在清缓存后会被删除重新抽。
 *
 * 不清 app/data 目录与 MediaStore（这些不属"缓存"语义）。
 */
@Composable
private fun CacheSection(vm: AppViewModel) {
    val context = LocalContext.current
    val cacheInfo by vm.cacheInfo.collectAsState()
    val clearing by vm.clearingCache.collectAsState()
    val diagPath by vm.diagPath.collectAsState()
    val exporting by vm.exportingDiag.collectAsState()

    SectionCard(title = null) {
        Text(
            "磁盘缓存 ${Formats.size(cacheInfo.diskBytes)}（缩略图 ${cacheInfo.thumbFiles} 张" +
                    "${if (cacheInfo.roomRows > 0) " · 探测缓存 ${cacheInfo.roomRows} 行" else ""}）",
            style = MaterialTheme.typography.bodyMedium,
            color = BlExt.textPrimary,
        )
        Text(
            "包含切点抽帧缩略图、媒体探测与关键帧缓存。清除后下次进入页面重新抽帧/探测" +
                    "（修复前的花屏画面会被删掉重新抽）。",
            style = MaterialTheme.typography.labelSmall,
            color = BlExt.textSecondary,
        )
        BlOutlinedButton(
            onClick = { vm.clearCache() },
            enabled = !clearing && (cacheInfo.diskBytes > 0 || cacheInfo.roomRows > 0),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (clearing) "清除中…" else "清除缓存") }

        cacheInfo.lastClearedAt?.let { ts ->
            Text(
                "上次清除 ${Formats.timeAgo(ts)}",
                style = MaterialTheme.typography.labelSmall,
                color = BlExt.textSecondary,
            )
        }

        androidx.compose.material3.HorizontalDivider(
            modifier = Modifier.padding(vertical = 4.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        )

        Text(
            "诊断日志记录 ffmpeg 抽帧失败现场：命令、returnCode、stderr、源文件路径。",
            style = MaterialTheme.typography.labelSmall,
            color = BlExt.textSecondary,
        )
        BlOutlinedButton(
            onClick = { vm.exportDiagnostics() },
            enabled = !exporting,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (exporting) "导出中…" else "导出诊断日志") }

        diagPath?.let { p ->
            Text(
                "已导出：$p",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            Text(
                "用文件管理器进 Movies/LosslessTrim/ 找到 .txt 分享给我",
                style = MaterialTheme.typography.labelSmall,
                color = BlExt.textSecondary,
            )
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    danger: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (danger) MaterialTheme.colorScheme.error else BlExt.textPrimary,
            )
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = BlExt.textSecondary)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
