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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.xixka.losslesstrim.data.AlignStrategy
import com.xixka.losslesstrim.data.OutputContainer
import com.xixka.losslesstrim.data.TrimMode
import com.xixka.losslesstrim.data.VideoEntry
import com.xixka.losslesstrim.trim.TrimController
import com.xixka.losslesstrim.ui.theme.BlChipShape
import com.xixka.losslesstrim.ui.theme.BlExt
import com.xixka.losslesstrim.util.Formats

/** 主页：参数卡 + 目录导入 + 文件列表（Blue Light UI 列表页模式） */
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
    val paramsValid by vm.paramsValid.collectAsState()
    val processable by vm.processableCount.collectAsState()
    val spaceWarning by vm.spaceWarning.collectAsState()

    val snackbar = remember { SnackbarHostState() }
    var showConfirm by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }

    fun numToStr(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    var headText by remember { mutableStateOf(numToStr(settings.headSec)) }
    var headSync by remember { mutableStateOf(settings.headSec) }
    var tailText by remember { mutableStateOf(numToStr(settings.tailSec)) }
    var tailSync by remember { mutableStateOf(settings.tailSec) }
    var startText by remember { mutableStateOf(Formats.clock(settings.intervalStartSec)) }
    var startSync by remember { mutableStateOf(settings.intervalStartSec) }
    var endText by remember { mutableStateOf(Formats.clock(settings.intervalEndSec)) }
    var endSync by remember { mutableStateOf(settings.intervalEndSec) }

    LaunchedEffect(settings.headSec) {
        if (headSync != settings.headSec) {
            headText = numToStr(settings.headSec); headSync = settings.headSec
        }
    }
    LaunchedEffect(settings.tailSec) {
        if (tailSync != settings.tailSec) {
            tailText = numToStr(settings.tailSec); tailSync = settings.tailSec
        }
    }
    LaunchedEffect(settings.intervalStartSec) {
        if (startSync != settings.intervalStartSec) {
            startText = Formats.clock(settings.intervalStartSec); startSync = settings.intervalStartSec
        }
    }
    LaunchedEffect(settings.intervalEndSec) {
        if (endSync != settings.intervalEndSec) {
            endText = Formats.clock(settings.intervalEndSec); endSync = settings.intervalEndSec
        }
    }

    fun startInternal() {
        vm.startBatch()
        onStartProcessing()
    }

    fun maybeConfirm() {
        if (settings.overwrite && !settings.overwriteConfirmed) showConfirm = true
        else startInternal()
    }

    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> maybeConfirm() }

    fun tryStart() {
        hint = null
        if (TrimController.running) {
            onStartProcessing()
            return
        }
        if (!paramsValid) {
            hint = "参数不合法：开始时间需小于结束时间"
            return
        }
        if (statuses.isEmpty()) {
            hint = "请先选择文件夹"
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
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        maybeConfirm()
    }

    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) vm.onFolderPicked(uri) }

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
                    if (vm.treeUri.collectAsState().value != null && !scanning) {
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
                text = { Text("开始批量处理（$processable）") },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ---------- 参数卡（白底描边卡） ----------
            SectionCard(
                title = if (settings.mode == TrimMode.HEAD_TAIL) "头尾裁剪" else "区间保留",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.mode == TrimMode.HEAD_TAIL,
                        onClick = { vm.updateSettings { it.copy(mode = TrimMode.HEAD_TAIL) } },
                        label = { Text("头尾裁剪") },
                        shape = BlChipShape,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                    FilterChip(
                        selected = settings.mode == TrimMode.INTERVAL,
                        onClick = { vm.updateSettings { it.copy(mode = TrimMode.INTERVAL) } },
                        label = { Text("区间保留") },
                        shape = BlChipShape,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (settings.mode == TrimMode.HEAD_TAIL) {
                        OutlinedTextField(
                            value = headText,
                            onValueChange = {
                                headText = it
                                Formats.parseSeconds(it)?.let { v ->
                                    headSync = v
                                    vm.updateSettings { s -> s.copy(headSec = v) }
                                }
                            },
                            label = { Text("片头(秒)") },
                            supportingText = { Text("从开头剪掉") },
                            singleLine = true,
                            isError = Formats.parseSeconds(headText) == null,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = tailText,
                            onValueChange = {
                                tailText = it
                                Formats.parseSeconds(it)?.let { v ->
                                    tailSync = v
                                    vm.updateSettings { s -> s.copy(tailSec = v) }
                                }
                            },
                            label = { Text("片尾(秒)") },
                            supportingText = { Text("从结尾剪掉") },
                            singleLine = true,
                            isError = Formats.parseSeconds(tailText) == null,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        OutlinedTextField(
                            value = startText,
                            onValueChange = {
                                startText = it
                                Formats.parseTime(it)?.let { v ->
                                    startSync = v
                                    vm.updateSettings { s -> s.copy(intervalStartSec = v) }
                                }
                            },
                            label = { Text("开始(分:秒)") },
                            singleLine = true,
                            isError = !paramsValid,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = endText,
                            onValueChange = {
                                endText = it
                                Formats.parseTime(it)?.let { v ->
                                    endSync = v
                                    vm.updateSettings { s -> s.copy(intervalEndSec = v) }
                                }
                            },
                            label = { Text("结束(分:秒)") },
                            singleLine = true,
                            isError = !paramsValid,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (!paramsValid) {
                    Text(
                        "参数非法：开始时间需小于结束时间，禁止开始批量",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ChoiceField(
                        label = "对齐",
                        options = listOf("宁多切", "宁少切", "自动"),
                        selected = settings.alignment.ordinal,
                        modifier = Modifier.weight(1f),
                    ) { idx -> vm.updateSettings { s -> s.copy(alignment = AlignStrategy.entries[idx]) } }
                    ChoiceField(
                        label = "容器",
                        options = listOf("原容器", "MP4", "MKV"),
                        selected = settings.container.ordinal,
                        modifier = Modifier.weight(1f),
                    ) { idx -> vm.updateSettings { s -> s.copy(container = OutputContainer.entries[idx]) } }
                }
                Text(
                    if (settings.overwrite) "覆盖模式：直接替换原文件"
                    else "保留模式：输出到 CutVideos/ 子目录",
                    style = MaterialTheme.typography.labelSmall,
                    color = BlExt.textSecondary,
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
                        title = if (scanMsg?.contains("没有") == true) "空文件夹" else "选择一个文件夹",
                        subtitle = scanMsg ?: "扫描后自动列出全部视频（含子目录可选）",
                        icon = Icons.Default.PlayArrow,
                        buttonText = "选择文件夹",
                        onButtonClick = { folderLauncher.launch(null) },
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
                    BlTextButton(onClick = { folderLauncher.launch(null) }) { Text("更换文件夹") }
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
                            "正在扫描并解析视频…",
                            style = MaterialTheme.typography.bodySmall,
                            color = BlExt.textSecondary,
                        )
                    }
                }
                // 列表收进一张白卡，行间分隔线
                androidx.compose.material3.OutlinedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .weight(1f),
                    shape = MaterialTheme.shapes.medium,
                    colors = androidx.compose.material3.CardDefaults.outlinedCardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    ),
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
                                    when {
                                        !e.probe.probeOk -> Text(
                                            "⚠ 不可处理（${e.probe.error ?: "解析失败"}）",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )

                                        st.plan.ok -> Text(
                                            "✓ 保留 ${Formats.clock(st.plan.requestedStart)} – ${Formats.clock(st.plan.requestedEnd)}" +
                                                    if (st.plan.truncated) "（终点超片长已截断）" else "",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = BlExt.success,
                                        )

                                        else -> Text(
                                            "跳过：${st.plan.skipReason}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = BlExt.warning,
                                        )
                                    }
                                    if (st.override != null) {
                                        Text(
                                            "⚙ 本片已自定义参数/轨道",
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
                Spacer(Modifier.height(88.dp))
            }
        }
    }

    // ---------- 开始前确认（破坏性操作：确认按钮 error 色） ----------
    if (showConfirm) {
        val modeLabel = if (settings.mode == TrimMode.HEAD_TAIL) {
            "头尾裁剪：片头 ${settings.headSec}s + 片尾 ${settings.tailSec}s"
        } else {
            "区间保留：${Formats.clock(settings.intervalStartSec)} – ${Formats.clock(settings.intervalEndSec)}"
        }
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("开始批量处理？") },
            text = {
                Text(
                    buildString {
                        append("共 $processable 个文件\n")
                        append(modeLabel)
                        append("\n对齐：${listOf("宁多切", "宁少切", "自动")[settings.alignment.ordinal]}")
                        append("\n容器：${listOf("原容器", "MP4", "MKV")[settings.container.ordinal]}")
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
                TextButton(onClick = {
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
                BlTextButton(onClick = { showConfirm = false }) { Text("取消") }
            },
        )
    }
}
