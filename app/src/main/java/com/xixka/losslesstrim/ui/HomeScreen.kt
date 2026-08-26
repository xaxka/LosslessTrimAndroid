package com.xixka.losslesstrim.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.xixka.losslesstrim.data.ThumbSource
import com.xixka.losslesstrim.data.TrimMode
import com.xixka.losslesstrim.data.VideoEntry
import com.xixka.losslesstrim.trim.TrimController
import com.xixka.losslesstrim.ui.theme.BlExt
import com.xixka.losslesstrim.util.Formats
import com.xixka.losslesstrim.util.StorageAccess

/** 主页：模式切换（FilterChip）+ 参数卡 + 双入口（文件夹批量 / 单文件编辑）+ 文件列表 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: AppViewModel,
    onOpenAnalysis: (VideoEntry) -> Unit,
    onOpenSettings: () -> Unit,
    onStartProcessing: () -> Unit,
) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsState()
    val statuses by vm.statuses.collectAsState()
    val scanning by vm.scanning.collectAsState()
    val scanProgress by vm.scanProgress.collectAsState()
    val scanMsg by vm.scanMsg.collectAsState()
    val orphans by vm.orphans.collectAsState()
    val processable by vm.processableCount.collectAsState()
    val spaceWarning by vm.spaceWarning.collectAsState()
    val treeUri by vm.treeUri.collectAsState()

    val snackbar = remember { SnackbarHostState() }
    var showConfirm by remember { mutableStateOf(false) }
    var showAllFilesDialog by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    var afterPermAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val isSingle = vm.isSingleFile

    // SAF 数据通道已移除：选文件夹/单文件前必须先有"所有文件"权限，
    // 否则扫描/探测/转码都无法直路径读写——入口处直接拦截引导授权
    fun launchPicker(pick: () -> Unit) {
        if (StorageAccess.hasAllFilesAccess(context)) {
            pick()
        } else {
            showAllFilesDialog = true
        }
    }

    fun startInternal(outputUri: android.net.Uri? = null) {
        if (vm.startBatch(outputUri)) {
            onStartProcessing()
        } else if (TrimController.running) {
            // 已有队列在跑：跳转去看进度，而不是静默丢弃本次点击
            onStartProcessing()
        } else {
            hint = "处理服务启动失败，请重试"
        }
    }

    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        val act = afterPermAction
        afterPermAction = null
        act?.invoke()
    }

    // Android 10 及以下：直接文件读写需要传统存储运行时权限
    val storagePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val act = afterPermAction
        afterPermAction = null
        if (!granted) hint = "未授予存储权限，将退回 SAF 模式（部分设备可能转码失败）"
        act?.invoke()
    }

    // 单文件另存为目标
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("video/*")
    ) { uri ->
        if (uri != null) startInternal(uri)
    }

    fun maybeConfirm() {
        if (!isSingle && settings.overwrite && !settings.overwriteConfirmed) showConfirm = true
        else if (isSingle) {
            // 单文件：先弹另存为对话框
            val e = statuses.firstOrNull()?.entry ?: return
            val ext = when (settings.container) {
                com.xixka.losslesstrim.data.OutputContainer.MP4 -> "mp4"
                com.xixka.losslesstrim.data.OutputContainer.MKV -> "mkv"
                else -> e.ext.ifEmpty { "mp4" }
            }
            saveLauncher.launch("${e.baseName}.$ext")
        } else startInternal()
    }

    fun tryStart() {
        hint = null
        if (TrimController.running) {
            onStartProcessing()
            return
        }
        if (statuses.isEmpty()) {
            hint = "请先选择文件夹或视频文件"
            return
        }
        if (processable == 0) {
            hint = "没有可处理的文件"
            return
        }
        // 直接文件读写优先：SAF(saf:)只写描述符上 faststart 收尾回移不可靠，
        // 是"转码成功但输出校验失败（moov atom not found）"的根因
        if (!StorageAccess.hasAllFilesAccess(context)) {
            if (Build.VERSION.SDK_INT >= 30) {
                showAllFilesDialog = true
            } else {
                afterPermAction = { maybeConfirm() }
                storagePermLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            afterPermAction = { maybeConfirm() }
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        maybeConfirm()
    }

    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) vm.onFolderPicked(uri) }

    val singleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) vm.onSingleFilePicked(uri) }

    LaunchedEffect(hint) {
        hint?.let {
            snackbar.showSnackbar(it)
            hint = null
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("无损批量剪辑", style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                actions = {
                    if (treeUri != null && !scanning) {
                        IconButton(onClick = { vm.rescan() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "重新扫描")
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                },
            )
        },
        bottomBar = {
            // ---------- 底部操作栏：片头/片尾缩略图切换（左）+ 开始批量处理（右） ----------
            // 空状态（未选文件且不在扫描）时不占屏——空态自身已有两个大按钮引导
            if (statuses.isNotEmpty() || scanning) {
                Surface(
                    color = MaterialTheme.colorScheme.background,
                    tonalElevation = 3.dp,
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // 缩略图抽取位置：片头（首帧，默认）/片尾（计划终点关键帧快照，随裁剪参数重抽）
                        FilterChip(
                            selected = settings.batchThumbSource == ThumbSource.START,
                            onClick = { vm.updateSettings { it.copy(batchThumbSource = ThumbSource.START) } },
                            label = { Text("片头") },
                        )
                        FilterChip(
                            selected = settings.batchThumbSource == ThumbSource.END,
                            onClick = { vm.updateSettings { it.copy(batchThumbSource = ThumbSource.END) } },
                            label = { Text("片尾") },
                        )
                        Spacer(Modifier.weight(1f))
                        Button(
                            onClick = { tryStart() },
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                        ) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (isSingle) "开始剪辑" else "开始批量处理（$processable）",
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ---------- 模式切换 ----------
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = settings.mode == TrimMode.HEAD_TAIL,
                    onClick = { vm.updateSettings { it.copy(mode = TrimMode.HEAD_TAIL) } },
                    label = { Text("头尾裁剪") },
                )
                FilterChip(
                    selected = settings.mode == TrimMode.INTERVAL,
                    onClick = { vm.updateSettings { it.copy(mode = TrimMode.INTERVAL) } },
                    label = { Text("区间保留") },
                )
            }

            // ---------- 空状态 / 列表 ----------
            if (statuses.isEmpty() && !scanning) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    EmptyState(
                        title = if (scanMsg?.contains("没有") == true) "空文件夹" else "选择视频来源",
                        subtitle = scanMsg ?: "批量处理整个文件夹，或选择单个视频精细编辑",
                        icon = Icons.Default.PlayArrow,
                        buttonText = "选择文件夹（批量）",
                        onButtonClick = { launchPicker { folderLauncher.launch(null) } },
                        button2Text = "选择视频文件（单个）",
                        onButton2Click = { launchPicker { singleLauncher.launch(arrayOf("video/*")) } },
                    )
                }
            } else {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        scanMsg ?: "扫描中…",
                        style = MaterialTheme.typography.labelMedium,
                        color = BlExt.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    BlTextButton(onClick = {
                        launchPicker {
                            if (isSingle) singleLauncher.launch(arrayOf("video/*"))
                            else folderLauncher.launch(null)
                        }
                    }) { Text(if (isSingle) "重新选择文件" else "更换文件夹") }
                }
                if (orphans.isNotEmpty()) {
                    Text(
                        "⚠ 发现上次中断遗留的临时文件：" +
                                orphans.take(2).joinToString("、") +
                                (if (orphans.size > 2) " 等 ${orphans.size} 个" else "") +
                                "。若原文件损坏，可把对应的 .trimbackup 备份改名还原",
                        style = MaterialTheme.typography.labelSmall,
                        color = BlExt.warning,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 2.dp),
                        maxLines = 3,
                    )
                }
                if (scanning) {
                    // 串行探测下大文件夹扫描要 1~3 分钟：显示"第 X/N 个 + 当前文件名
                    // + 进度条"，避免只有转圈被误认为卡死
                    val p = scanProgress
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.padding(6.dp))
                            Text(
                                if (p != null && p.total > 0) {
                                    "正在解析视频 ${p.parsed + 1}/${p.total}…"
                                } else {
                                    "正在解析视频…"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = BlExt.textSecondary,
                            )
                        }
                        if (p != null && p.total > 0) {
                            Text(
                                p.current,
                                style = MaterialTheme.typography.labelSmall,
                                color = BlExt.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            LinearProgressIndicator(
                                progress = { (p.parsed + 1f) / p.total },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                OutlinedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .weight(1f),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.outlinedCardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                ) {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        itemsIndexed(statuses, key = { _, s -> s.entry.docUri.toString() }) { _, st ->
                            val e = st.entry
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = e.probe.probeOk) { onOpenAnalysis(e) }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // 缩略图抽取位置：片头（首帧）/片尾（计划终点，先夹进文件内——
                                // requestedEnd≈duration 会越过末帧，FFmpeg 全链空输出、
                                // MMR 也不稳，此前片尾缩略图又慢又出不来就是它）。
                                // approximate=取关键帧快照：只解 1 帧（~0.3s），不再
                                // 从关键帧精确前向解码整个 GOP（4K10 长片 ~4s/张）。
                                val endThumb = settings.batchThumbSource == ThumbSource.END
                                val thumbMs = if (endThumb) {
                                    val durMs = (e.probe.durationSec * 1000.0).toLong()
                                    val endMs = (st.plan.requestedEnd * 1000.0).toLong().coerceAtLeast(0L)
                                    endMs.coerceAtMost((durMs - 600L).coerceAtLeast(0L))
                                } else 0L
                                // 10-bit 文件硬解颜色不可靠 → 不给硬解资格（ProbeResult.hwThumbEligible）
                                VideoThumb(
                                    e.docUri,
                                    identity = e.sizeBytes.toString(),
                                    allowHw = e.probe.hwThumbEligible,
                                    timeMs = thumbMs,
                                    approximate = endThumb,
                                )
                                Spacer(Modifier.padding(6.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        e.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        buildString {
                                            append(Formats.clock(e.probe.durationSec))
                                            append(" · ").append(Formats.size(e.sizeBytes))
                                            append(" · ").append(e.probe.videoCodec ?: "?")
                                            // 主视频轨分辨率（信息密度提升，列表页一眼看全）
                                            e.probe.videoStream?.let { v ->
                                                if (v.width != null && v.height != null) {
                                                    append(" · ").append(v.width).append('×').append(v.height)
                                                }
                                            }
                                            // 主音频轨采样率（kHz 整数）+ 比特率（如能拿到）
                                            e.probe.audioStream?.let { a ->
                                                a.sampleRate?.let { append(" · ").append(it / 1000).append("kHz") }
                                                a.bitRate?.takeIf { it > 0 }?.let {
                                                    append(" · ").append(Formats.bitrate(it))
                                                }
                                            }
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = BlExt.textSecondary,
                                        maxLines = 1,
                                    )
                                    // 标记文字按当前模式重新计算（切换模式即刷新）
                                    val modeCustomized = if (settings.mode == TrimMode.HEAD_TAIL) {
                                        st.override?.headSec != null || st.override?.tailSec != null
                                    } else {
                                        st.override?.intervalStartSec != null || st.override?.intervalEndSec != null
                                    }
                                    val hasDropped = st.override?.droppedStreams?.isNotEmpty() == true
                                    when {
                                        !e.probe.probeOk -> Text(
                                            "⚠ 不可处理（${e.probe.error ?: "解析失败"}）",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )

                                        st.plan.ok -> if (modeCustomized) {
                                            Text(
                                                "✓ 保留 ${Formats.clock(st.plan.requestedStart)} – ${Formats.clock(st.plan.requestedEnd)}" +
                                                        if (st.plan.truncated) "（终点超片长已截断）" else "",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = BlExt.success,
                                            )
                                        } else {
                                            Text(
                                                if (settings.mode == TrimMode.HEAD_TAIL) "未设置片头/片尾，本片不裁剪"
                                                else "未设置区间，本片保留全片",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = BlExt.textSecondary,
                                            )
                                        }

                                        else -> Text(
                                            "跳过：${st.plan.skipReason}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = BlExt.warning,
                                        )
                                    }
                                    if (modeCustomized || hasDropped) {
                                        Text(
                                            "⚙ " + when {
                                                modeCustomized && settings.mode == TrimMode.HEAD_TAIL ->
                                                    if (hasDropped) "本片已自定义片头/片尾 · 已选丢弃轨道" else "本片已自定义片头/片尾"
                                                modeCustomized ->
                                                    if (hasDropped) "本片已自定义开始/结束 · 已选丢弃轨道" else "本片已自定义开始/结束"
                                                else -> "本片已选丢弃轨道"
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.secondary,
                                        )
                                    }
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        }
                    }
                }
            }
        }
    }

    // ---------- 开始前确认（批量覆盖模式才有；单文件直接走另存为） ----------
    if (showConfirm) {
        val modeLabel = if (settings.mode == TrimMode.HEAD_TAIL) {
            val customized = statuses.count { it.override?.headSec != null || it.override?.tailSec != null }
            "头尾裁剪：片头/片尾按各视频单独设置（已自定义 $customized 个，其余不裁剪）"
        } else {
            val customized = statuses.count { it.override?.intervalStartSec != null || it.override?.intervalEndSec != null }
            "区间保留：开始/结束按各视频单独设置（已自定义 $customized 个，其余保留全片）"
        }
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("开始批量处理？") },
            text = {
                Text(
                    buildString {
                        append("共 $processable 个文件\n")
                        append(modeLabel)
                        if (settings.overwrite) {
                            append("\n\n⚠ 覆盖模式将删除原文件，不可恢复！")
                        } else {
                            append("\n\n输出到 CutVideos/ 子目录，原文件保留。")
                        }
                        spaceWarning?.let { append("\n⚠ $it") }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showConfirm = false
                    if (settings.overwrite && !settings.overwriteConfirmed) vm.confirmOverwrite()
                    startInternal()
                }) {
                    Text(
                        "开始",
                        color = if (settings.overwrite) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.secondary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showConfirm = false }) { Text("取消") }
            },
        )
    }

    // ---------- "所有文件"权限引导（直接文件路径读写的前提） ----------
    if (showAllFilesDialog) {
        AlertDialog(
            onDismissRequest = { showAllFilesDialog = false },
            title = { Text("需要\u201c所有文件\u201d权限") },
            text = {
                Text(
                    "部分设备上，经系统存储框架（SAF）写出 MP4 会在收尾阶段损坏文件" +
                            "（moov atom not found），导致转码结果被安全校验拦截删除。\n\n" +
                            "授予\u201c允许管理所有文件\u201d后，应用将直接读写真实文件路径，" +
                            "彻底规避该问题；不授权仍可处理，但可能复现此故障。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showAllFilesDialog = false
                    try {
                        context.startActivity(StorageAccess.allFilesAccessIntent(context))
                    } catch (_: Exception) {
                        hint = "无法打开授权页，请到系统设置手动开启"
                    }
                }) {
                    Text(
                        "去授权",
                        color = MaterialTheme.colorScheme.secondary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showAllFilesDialog = false }) {
                    Text("暂不")
                }
            },
        )
    }
}
