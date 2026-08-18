package com.xixka.losslesstrim.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xixka.losslesstrim.data.AlignStrategy
import com.xixka.losslesstrim.data.OutputContainer
import com.xixka.losslesstrim.data.TrimMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置", fontWeight = FontWeight.SemiBold) },
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
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionCard("剪辑") {
                ChoiceField(
                    label = "默认剪辑模式",
                    options = listOf("头尾裁剪", "区间保留"),
                    selected = settings.mode.ordinal,
                ) { idx -> vm.updateSettings { s -> s.copy(mode = TrimMode.entries[idx]) } }
                ChoiceField(
                    label = "关键帧对齐策略",
                    options = listOf("宁多切（默认）", "宁少切", "自动"),
                    selected = settings.alignment.ordinal,
                ) { idx -> vm.updateSettings { s -> s.copy(alignment = AlignStrategy.entries[idx]) } }
                Text(
                    "宁多切：起点对齐后一个关键帧、终点对齐前一个（多砍一点，保证广告清零）。\n宁少切：反之，怕误伤正片。",
                    fontSize = 11.sp,
                    color = Color(0xFF64748B),
                )
            }

            SectionCard("输出") {
                ChoiceField(
                    label = "输出容器",
                    options = listOf("保持原容器", "MP4", "MKV"),
                    selected = settings.container.ordinal,
                ) { idx -> vm.updateSettings { s -> s.copy(container = OutputContainer.entries[idx]) } }
                Text(
                    "只换封装和扩展名，流仍为 -c copy 不转码。MP4 会启用 faststart。\n注意：srt 字幕 / DTS 音频放进 MP4 可能失败（会明确报错，不静默丢轨），此类文件请改回 MKV 或原容器。",
                    fontSize = 11.sp,
                    color = Color(0xFF64748B),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("覆盖原文件", fontSize = 14.sp)
                        Text(
                            if (settings.overwrite) "成功后删除原文件，原地替换，目录不多副本"
                            else "输出到 CutVideos/ 子目录，原文件保留",
                            fontSize = 11.sp,
                            color = Color(0xFF64748B),
                        )
                    }
                    Switch(
                        checked = settings.overwrite,
                        onCheckedChange = { v -> vm.updateSettings { it.copy(overwrite = v) } },
                    )
                }
            }

            SectionCard("扫描") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("包含子目录", fontSize = 14.sp)
                        Text("修改后自动重新扫描", fontSize = 11.sp, color = Color(0xFF64748B))
                    }
                    Switch(
                        checked = settings.includeSubdirs,
                        onCheckedChange = { v -> vm.updateSettings { it.copy(includeSubdirs = v) } },
                    )
                }
            }

            SectionCard("区间模式超片长") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.truncateOverlong,
                        onClick = { vm.updateSettings { it.copy(truncateOverlong = true) } },
                        label = { Text("按片尾截断") },
                    )
                    FilterChip(
                        selected = !settings.truncateOverlong,
                        onClick = { vm.updateSettings { it.copy(truncateOverlong = false) } },
                        label = { Text("跳过该文件") },
                    )
                }
                Text(
                    "结束时间超过某文件总时长时的处理方式（开始时间超片长一律跳过）。",
                    fontSize = 11.sp,
                    color = Color(0xFF64748B),
                )
            }

            SectionCard("参数记忆") {
                Text(
                    "两套模式参数各自独立记忆，切换模式不丢失；重启后沿用上次使用的模式与参数（DataStore）。\n单文件的自定义参数/轨道勾选仅在本次会话内生效，杀进程后重扫目录即恢复默认。",
                    fontSize = 11.sp,
                    color = Color(0xFF64748B),
                )
            }
        }
    }
}
