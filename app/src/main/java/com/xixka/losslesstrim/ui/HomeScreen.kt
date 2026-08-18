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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import com.xixka.losslesstrim.data.TrimMode
import com.xixka.losslesstrim.data.VideoEntry
import com.xixka.losslesstrim.trim.TrimController
import com.xixka.losslesstrim.ui.theme.BlExt
import com.xixka.losslesstrim.util.Formats

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
    val scanMsg by vm.scanMsg.collectAsState()
    val processable by vm.processableCount.collectAsState()
    val spaceWarning by vm.spaceWarning.collectAsState()
    val treeUri by vm.treeUri.collectAsState()

    val snackbar = remember { SnackbarHostState() }
    var showConfirm by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    var afterPermAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val isSingle = vm.isSingleFile

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
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { tryStart() },
                icon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                text = { Text(if (isSingle) "开始剪辑" else "开始批量处理（$processable）") },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ---------- 模式切换（FilterChip，初版布局） ----------
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                        onButtonClick = { folderLauncher.launch(null) },
                        button2Text = "选择视频文件（单个）",
                        onButton2Click = { singleLauncher.launch(arrayOf("video/*")) },
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
                        if (isSingle) singleLauncher.launch(arrayOf("video/*"))
                        else folderLauncher.launch(null)
                    }) { Text(if (isSingle) "重新选择文件" else "更换文件夹") }
                }
                if (scanning) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.padding(6.dp))
                        Text(
                            "正在解析视频…",
                            style = MaterialTheme.typography.bodySmall,
                            color = BlExt.textSecondary,
                        )
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
                                VideoThumb(e.docUri)
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
                Spacer(Modifier.height(84.dp))
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
}
