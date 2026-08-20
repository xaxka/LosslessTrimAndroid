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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.xixka.losslesstrim.data.AlignStrategy
import com.xixka.losslesstrim.data.OutputContainer
import com.xixka.losslesstrim.update.Updater
import com.xixka.losslesstrim.ui.theme.BlSurfaceVariant
import com.xixka.losslesstrim.ui.theme.BlChipShape
import com.xixka.losslesstrim.ui.theme.BlExt
import com.xixka.losslesstrim.util.Formats

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

            GroupLabel("更新")
            UpdateSection()
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
 * 检查更新卡片：当前版本 + 检查按钮，按 Updater 状态机展示
 * 新版本信息 / 下载进度 / 安装入口 / 错误提示。
 */
@Composable
private fun UpdateSection() {
    val context = LocalContext.current
    val updState by Updater.state.collectAsState()
    val current by remember { Updater.currentVersion(context) }

    // "允许安装未知应用"授权状态：从系统授权页返回（ON_RESUME）时刷新
    var installAllowed by remember { mutableStateOf(Updater.canInstall(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) installAllowed = Updater.canInstall(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SectionCard(title = null) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("当前版本", style = MaterialTheme.typography.bodyMedium, color = BlExt.textSecondary)
                Text(
                    "v${current.first}（${current.second}）",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            BlOutlinedButton(
                onClick = { Updater.check(context) },
                enabled = updState !is Updater.State.Checking && updState !is Updater.State.Downloading,
            ) { Text("检查更新") }
        }

        when (val s = updState) {
            Updater.State.Idle -> Text(
                "检查 GitHub Releases 上的最新发布版本",
                style = MaterialTheme.typography.labelSmall,
                color = BlExt.textSecondary,
            )

            Updater.State.Checking -> Text(
                "正在检查更新…",
                style = MaterialTheme.typography.labelSmall,
                color = BlExt.textSecondary,
            )

            Updater.State.UpToDate -> Text(
                "已是最新版本",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
            )

            is Updater.State.Available -> {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Text(
                    "发现新版本 v${s.info.versionName}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Text(
                    "更新包 ${Formats.size(s.info.apkSize)}，下载自 GitHub Releases",
                    style = MaterialTheme.typography.labelSmall,
                    color = BlExt.textSecondary,
                )
                BlOutlinedButton(
                    onClick = { Updater.download(context) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("下载更新包") }
            }

            is Updater.State.Downloading -> {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                val pct = if (s.total > 0) (s.received.toFloat() / s.total).coerceIn(0f, 1f) else null
                LinearProgressIndicator(
                    progress = { pct ?: 0f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(MaterialTheme.shapes.extraSmall),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = BlSurfaceVariant,
                )
                Text(
                    if (pct != null) {
                        "下载中 ${Formats.size(s.received)} / ${Formats.size(s.total)}（${(pct * 100).toInt()}%）"
                    } else {
                        "下载中 ${Formats.size(s.received)}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = BlExt.textSecondary,
                )
                BlOutlinedButton(
                    onClick = { Updater.cancelDownload() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("取消下载") }
            }

            is Updater.State.ReadyToInstall -> {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Text(
                    "v${s.info.versionName} 更新包已就绪",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                if (!installAllowed) {
                    Text(
                        "首次安装需允许本应用安装未知应用：点\"安装\"会跳转系统授权页，允许后返回再点一次安装",
                        style = MaterialTheme.typography.labelSmall,
                        color = BlExt.textSecondary,
                    )
                }
                BlOutlinedButton(
                    onClick = {
                        if (Updater.canInstall(context)) Updater.install(context)
                        else Updater.requestInstallPermission(context)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("安装") }
            }

            is Updater.State.Error -> Text(
                s.message,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
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
