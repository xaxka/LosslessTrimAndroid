package com.xixka.losslesstrim.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xixka.losslesstrim.data.AlignStrategy
import com.xixka.losslesstrim.data.OutputContainer
import com.xixka.losslesstrim.ui.theme.BlChipShape
import com.xixka.losslesstrim.ui.theme.BlExt

/** 设置页：分组卡片（组标题 labelM 灰字 + 白卡内多行项） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsState()

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
                    options = listOf("宁多切（默认）", "宁少切", "自动"),
                    selected = settings.alignment.ordinal,
                ) { idx -> vm.updateSettings { s -> s.copy(alignment = AlignStrategy.entries[idx]) } }
                Text(
                    "宁多切：起点对齐后一个关键帧、终点对齐前一个（多砍一点，保证广告清零）。宁少切反之，怕误伤正片。",
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
                SwitchRow(
                    title = "包含子目录",
                    subtitle = "修改后自动重新扫描",
                    checked = settings.includeSubdirs,
                    onChange = { v -> vm.updateSettings { it.copy(includeSubdirs = v) } },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ChoiceField(
                    label = "结束时间超片长时",
                    options = listOf("按片尾截断", "跳过该文件"),
                    selected = if (settings.truncateOverlong) 0 else 1,
                ) { idx -> vm.updateSettings { it.copy(truncateOverlong = idx == 0) } }
                Text(
                    "开始时间超片长时一律跳过该文件。",
                    style = MaterialTheme.typography.labelSmall,
                    color = BlExt.textSecondary,
                )
            }

            GroupLabel("参数记忆")
            SectionCard(title = null) {
                Text(
                    "两套模式参数各自独立记忆，切换模式不丢失；重启后沿用上次使用的模式与参数（DataStore）。单文件的自定义参数/轨道勾选仅在本次会话内生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = BlExt.textSecondary,
                )
            }
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
