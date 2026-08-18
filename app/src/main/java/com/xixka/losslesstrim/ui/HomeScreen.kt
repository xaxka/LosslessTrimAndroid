package com.xixka.losslesstrim.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.xixka.losslesstrim.data.OutputContainer
import com.xixka.losslesstrim.data.TrimMode
import com.xixka.losslesstrim.data.VideoEntry
import com.xixka.losslesstrim.trim.TrimPlanner
import com.xixka.losslesstrim.util.Formats

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
    var waitingPerm by remember { mutableStateOf(false) }

    // ---- 文本输入状态（跟随全局设置，双向同步避免打断输入） ----
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

    // ---- 启动流程 ----
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
    ) { _ ->
        waitingPerm = false
        // 拒绝通知权限也允许继续（服务照常运行，只是没有进度通知）
        maybeConfirm()
    }

    fun tryStart() {
        hint = null
        if (com.xixka.losslesstrim.trim.TrimController.running) {
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
            waitingPerm = true
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
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("无损批量剪辑", fontWeight = FontWeight.SemiBold) },
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
            // ---------- 参数条 ----------
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
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
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 11.sp,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    ChoiceField(
                        label = "对齐",
                        options = listOf("宁多切", "宁少切", "自动"),
                        selected = settings.alignment.ordinal,
                        modifier = Modifier.weight(1f),
                    ) { vm.updateSettings { s -> s.copy(alignment = com.xixka.losslesstrim.data.AlignStrategy.entries[it]) } }
                    ChoiceField(
                        label = "容器",
                        options = listOf("原容器", "MP4", "MKV"),
                        selected = settings.container.ordinal,
                        modifier = Modifier.weight(1f),
                    ) { vm.updateSettings { s -> s.copy(container = OutputContainer.entries[it]) } }
                }
                Text(
                    if (settings.overwrite) "覆盖模式：直接替换原文件" else "保留模式：输出到 CutVideos/ 子目录",
                    fontSize = 11.sp,
                    color = Color(0xFF64748B),
                )
            }

            HorizontalDivider()

            // ---------- 目录与列表 ----------
            if (statuses.isEmpty() && !scanning) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        scanMsg ?: "选择一个包含视频的文件夹开始",
                        color = Color(0xFF64748B),
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(16.dp))
                    ExtendedFloatingActionButton(
                        onClick = { folderLauncher.launch(null) },
                        icon = { Icon(Icons.Default.Folder, contentDescription = null) },
                        text = { Text("选择文件夹") },
                    )
                }
            } else {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        scanMsg ?: "扫描中…",
                        fontSize = 12.sp,
                        color = Color(0xFF64748B),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { folderLauncher.launch(null) }) { Text("更换文件夹") }
                }
                if (scanning) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.width(20.dp).height(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("正在扫描并解析视频…", fontSize = 12.sp, color = Color(0xFF64748B))
                    }
                }
                LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 12.dp, end = 12.dp, bottom = 96.dp
                    ),
                ) {
                    itemsIndexed(statuses, key = { _, s -> s.entry.docUri.toString() }) { _, st ->
                        val e = st.entry
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = e.probe.probeOk) { onOpenAnalysis(e) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            VideoThumb(e.docUri)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    e.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val dur = e.probe.durationSec
                                Text(
                                    buildString {
                                        append(Formats.clock(dur))
                                        append(" · ").append(Formats.size(e.sizeBytes))
                                        append(" · ").append(e.probe.videoCodec ?: "?")
                                        append(" · ").append(e.probe.formatName.substringBefore(','))
                                    },
                                    fontSize = 11.sp,
                                    color = Color(0xFF64748B),
                                )
                                if (!e.probe.probeOk) {
                                    Text(
                                        "⚠ 不可处理（${e.probe.error ?: "解析失败"}）",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                } else if (st.plan.ok) {
                                    Text(
                                        "✓ 保留 ${Formats.clock(st.plan.requestedStart)} – ${Formats.clock(st.plan.requestedEnd)}" +
                                                if (st.plan.truncated) "（终点超片长已截断）" else "",
                                        fontSize = 11.sp,
                                        color = Color(0xFF059669),
                                    )
                                } else {
                                    Text(
                                        "跳过：${st.plan.skipReason}",
                                        fontSize = 11.sp,
                                        color = Color(0xFFD97706),
                                    )
                                }
                                if (st.override != null) {
                                    Text(
                                        "⚙ 本片已自定义参数/轨道",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    // ---------- 开始前确认 ----------
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
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showConfirm = false
                    if (settings.overwrite && !settings.overwriteConfirmed) vm.confirmOverwrite()
                    startInternal()
                }) { Text("开始") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("取消") }
            },
        )
    }
}
