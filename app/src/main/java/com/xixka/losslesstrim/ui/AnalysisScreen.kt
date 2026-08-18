package com.xixka.losslesstrim.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xixka.losslesstrim.data.PerFileOverride
import com.xixka.losslesstrim.data.TrimMode
import com.xixka.losslesstrim.data.VideoEntry
import com.xixka.losslesstrim.ffmpeg.Probe
import com.xixka.losslesstrim.trim.TrimPlanner
import com.xixka.losslesstrim.ui.theme.BlExt
import com.xixka.losslesstrim.ui.theme.BlPrimary
import com.xixka.losslesstrim.ui.theme.BlSecondary
import com.xixka.losslesstrim.ui.theme.BlSurfaceVariant
import com.xixka.losslesstrim.util.Formats
import java.util.Locale
import kotlin.math.abs

/** 单文件分析视图：时间轴 + 关键帧 + 切点抽帧 + 轨道勾选 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalysisScreen(vm: AppViewModel, entry: VideoEntry, onClose: () -> Unit) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsState()
    val overrides by vm.overrides.collectAsState()
    val savedOverride = overrides[entry.docUri]
    val dur = entry.probe.durationSec

    var keyframes by remember(entry.docUri) { mutableStateOf<List<Double>?>(null) }
    LaunchedEffect(entry.docUri) {
        keyframes = Probe.probeKeyframes(context, entry.docUri)
    }

    fun fmtSec(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else String.format(Locale.US, "%.1f", v)

    var headText by remember { mutableStateOf(fmtSec(savedOverride?.headSec ?: settings.headSec)) }
    var tailText by remember { mutableStateOf(fmtSec(savedOverride?.tailSec ?: settings.tailSec)) }
    var startText by remember { mutableStateOf(Formats.clock(savedOverride?.intervalStartSec ?: settings.intervalStartSec)) }
    var endText by remember { mutableStateOf(Formats.clock(savedOverride?.intervalEndSec ?: settings.intervalEndSec)) }
    var dropped by remember { mutableStateOf(savedOverride?.droppedStreams ?: emptySet<Int>()) }

    val head = Formats.parseSeconds(headText) ?: settings.headSec
    val tail = Formats.parseSeconds(tailText) ?: settings.tailSec
    val start = Formats.parseTime(startText) ?: settings.intervalStartSec
    val end = Formats.parseTime(endText) ?: settings.intervalEndSec

    val kfs = keyframes ?: emptyList()
    val plan = remember(head, tail, start, end, settings, kfs) {
        TrimPlanner.alignedPlan(
            entry, settings,
            PerFileOverride(headSec = head, tailSec = tail, intervalStartSec = start, intervalEndSec = end),
            kfs
        )
    }

    fun onDragPoint(isStart: Boolean, t: Double) {
        val clamped = t.coerceIn(0.0, dur)
        when {
            settings.mode == TrimMode.HEAD_TAIL && isStart -> headText = fmtSec(clamped)
            settings.mode == TrimMode.HEAD_TAIL -> tailText = fmtSec((dur - clamped).coerceIn(0.0, dur))
            isStart -> startText = Formats.clock(clamped)
            else -> endText = Formats.clock(clamped)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        MonoText(entry.name)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                navigationIcon = {
                    IconButton(onClick = onClose) {
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard(
                title = "文件信息",
                subtitle = "时长 ${Formats.clock(dur)} · 大小 ${Formats.size(entry.sizeBytes)} · " +
                        (entry.probe.videoCodec ?: "?") + " · " + entry.probe.formatName.substringBefore(','),
            ) {}

            SectionCard(
                title = "时间轴与切点",
                subtitle = "橙线 = 关键帧（无损剪辑的物理边界）；红线 = 关键帧对齐后的实际切点；拖动圆点微调",
            ) {
                if (keyframes == null) {
                    LinearProgressIndicator(
                        Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = BlSurfaceVariant,
                    )
                    Text(
                        "关键帧探测中…（读取索引，几秒）",
                        style = MaterialTheme.typography.bodySmall,
                        color = BlExt.textSecondary,
                    )
                }
                TimelineBar(
                    dur = dur,
                    keyframes = kfs,
                    reqStart = plan.requestedStart,
                    reqEnd = plan.requestedEnd,
                    actStart = plan.actualStart,
                    actEnd = plan.actualEnd,
                    onDragPoint = { isStart, t -> onDragPoint(isStart, t) },
                )
                if (settings.mode == TrimMode.HEAD_TAIL) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = headText,
                            onValueChange = { headText = it },
                            label = { Text("距片头(秒)") },
                            singleLine = true,
                            isError = Formats.parseSeconds(headText) == null,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = tailText,
                            onValueChange = { tailText = it },
                            label = { Text("距片尾(秒)") },
                            singleLine = true,
                            isError = Formats.parseSeconds(tailText) == null,
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = startText,
                            onValueChange = { startText = it },
                            label = { Text("开始(分:秒)") },
                            singleLine = true,
                            isError = start >= end,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = endText,
                            onValueChange = { endText = it },
                            label = { Text("结束(分:秒)") },
                            singleLine = true,
                            isError = start >= end,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                if (plan.ok) {
                    val dS = plan.actualStart - plan.requestedStart
                    val dE = plan.actualEnd - plan.requestedEnd
                    Text(
                        buildString {
                            append("设定起点 ").append(Formats.clockMs(plan.requestedStart))
                            append(" → 实际 ").append(Formats.clockMs(plan.actualStart))
                            append("（${if (dS >= 0) "+" else ""}${String.format(Locale.US, "%.1f", dS)}s）\n")
                            append("设定终点 ").append(Formats.clockMs(plan.requestedEnd))
                            append(" → 实际 ").append(Formats.clockMs(plan.actualEnd))
                            append("（${if (dE >= 0) "+" else ""}${String.format(Locale.US, "%.1f", dE)}s）\n")
                            append("保留时长 ≈ ").append(Formats.clock(plan.duration))
                            if (plan.truncated) append("（终点超片长已截断）")
                            if (kfs.isEmpty()) append("\n（无关键帧信息：音频文件或索引缺失，切点不做对齐）")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = BlExt.textSecondary,
                    )
                } else {
                    Text(
                        "当前参数不可处理：${plan.skipReason}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (plan.ok) {
                SectionCard(title = "切点抽帧确认", subtitle = "肉眼确认切的位置对不对") {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        FramePreview(
                            uri = entry.docUri,
                            tSec = plan.actualStart,
                            label = "起点后第 1 帧",
                            timeLabel = Formats.clockMs(plan.actualStart),
                        )
                        FramePreview(
                            uri = entry.docUri,
                            tSec = (plan.actualEnd - 0.2).coerceAtLeast(plan.actualStart),
                            label = "终点前第 1 帧",
                            timeLabel = Formats.clockMs(plan.actualEnd),
                        )
                    }
                }
            }

            SectionCard(title = "轨道", subtitle = "勾选保留，默认全保留；未动过的文件按全保留处理") {
                entry.probe.streams.forEach { s ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 44.dp),
                    ) {
                        Checkbox(
                            checked = s.index !in dropped,
                            onCheckedChange = { checked ->
                                dropped = if (checked) dropped - s.index else dropped + s.index
                            },
                        )
                        Column(Modifier.padding(start = 4.dp)) {
                            Text(s.label(), style = MaterialTheme.typography.bodyMedium)
                            if (s.isCover) {
                                Text(
                                    "封面轨，勾选后原样带出",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = BlExt.textSecondary,
                                )
                            }
                        }
                    }
                }
                if (dropped.isNotEmpty()) {
                    Text(
                        "将丢弃 ${dropped.size} 条轨道",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        val o = PerFileOverride(
                            headSec = if (head == settings.headSec) null else head,
                            tailSec = if (tail == settings.tailSec) null else tail,
                            intervalStartSec = if (start == settings.intervalStartSec) null else start,
                            intervalEndSec = if (end == settings.intervalEndSec) null else end,
                            droppedStreams = dropped,
                        )
                        vm.setOverride(entry.docUri, o)
                        onClose()
                    },
                    enabled = plan.ok,
                    modifier = Modifier.weight(1f),
                ) { Text("保存本片设置") }
                if (savedOverride != null) {
                    BlTextButton(onClick = {
                        vm.setOverride(entry.docUri, null)
                        onClose()
                    }) { Text("恢复全局设置") }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 时间轴：关键帧（橙）+ 保留区（蓝）+ 实际切点（红）+ 可拖动手柄 */
@Composable
fun TimelineBar(
    dur: Double,
    keyframes: List<Double>,
    reqStart: Double,
    reqEnd: Double,
    actStart: Double,
    actEnd: Double,
    onDragPoint: (isStart: Boolean, t: Double) -> Unit,
) {
    var widthPx by remember { mutableStateOf(0f) }
    var draggingStart by remember { mutableStateOf(true) }

    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .height(72.dp)
                .pointerInput(dur) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            if (widthPx > 0 && dur > 0) {
                                val xs = (reqStart / dur * widthPx).toFloat()
                                val xe = (reqEnd / dur * widthPx).toFloat()
                                draggingStart = abs(offset.x - xs) <= abs(offset.x - xe)
                            }
                        },
                        onDrag = { change, _ ->
                            if (widthPx > 0 && dur > 0) {
                                change.consume()
                                val t = (change.position.x / widthPx * dur).coerceIn(0.0, dur)
                                onDragPoint(draggingStart, t)
                            }
                        },
                    )
                }
        ) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .onSizeChanged { widthPx = it.width.toFloat() }
            ) {
                val w = size.width
                val h = size.height
                val trackTop = (h - 32f) / 2f
                fun x(t: Double): Float = (t / dur * w).toFloat().coerceIn(0f, w)

                // 轨道底（surfaceVariant）
                drawRoundRect(
                    color = BlSurfaceVariant,
                    topLeft = Offset(0f, trackTop),
                    size = Size(w, 32f),
                    cornerRadius = CornerRadius(16f, 16f),
                )
                // 保留区（设定，primary 55%）
                val rx0 = x(reqStart)
                val rx1 = x(reqEnd)
                if (rx1 > rx0) {
                    drawRect(
                        color = BlPrimary.copy(alpha = 0.55f),
                        topLeft = Offset(rx0, trackTop),
                        size = Size(rx1 - rx0, 32f),
                    )
                }
                // 关键帧（warning 橙）
                val step = if (keyframes.size > 1500) keyframes.size / 1500 + 1 else 1
                var i = 0
                while (i < keyframes.size) {
                    val kx = x(keyframes[i])
                    drawLine(
                        color = BlExt.warning.copy(alpha = 0.75f),
                        start = Offset(kx, h / 2 - 24f),
                        end = Offset(kx, h / 2 + 24f),
                        strokeWidth = 1.5f,
                    )
                    i += step
                }
                // 实际切点（error 红）
                drawLine(BlExt.error, Offset(x(actStart), 4f), Offset(x(actStart), h - 4f), 2f)
                drawLine(BlExt.error, Offset(x(actEnd), 4f), Offset(x(actEnd), h - 4f), 2f)
                // 拖动手柄（secondary）
                drawCircle(BlSecondary, radius = 11f, center = Offset(x(reqStart), h / 2))
                drawCircle(BlSecondary, radius = 11f, center = Offset(x(reqEnd), h / 2))
                drawCircle(Color.White, radius = 4f, center = Offset(x(reqStart), h / 2))
                drawCircle(Color.White, radius = 4f, center = Offset(x(reqEnd), h / 2))
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("00:00", style = MaterialTheme.typography.labelSmall, color = BlExt.textSecondary)
            Text(
                "总时长 ${Formats.clock(dur)}",
                style = MaterialTheme.typography.labelSmall,
                color = BlExt.textSecondary,
            )
        }
    }
}
