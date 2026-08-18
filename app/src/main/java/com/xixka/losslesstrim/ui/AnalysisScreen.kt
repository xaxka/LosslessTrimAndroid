package com.xixka.losslesstrim.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xixka.losslesstrim.data.PerFileOverride
import com.xixka.losslesstrim.data.TrimMode
import com.xixka.losslesstrim.data.VideoEntry
import com.xixka.losslesstrim.ffmpeg.Probe
import com.xixka.losslesstrim.trim.TrimPlanner
import com.xixka.losslesstrim.util.Formats
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalysisScreen(vm: AppViewModel, entry: VideoEntry, onClose: () -> Unit) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsState()
    val overrides by vm.overrides.collectAsState()
    val savedOverride = overrides[entry.docUri]
    val dur = entry.probe.durationSec

    // 关键帧探测（异步，带缓存）
    var keyframes by remember(entry.docUri) { mutableStateOf<List<Double>?>(null) }
    LaunchedEffect(entry.docUri) {
        keyframes = Probe.probeKeyframes(context, entry.docUri)
    }

    fun fmtSec(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else String.format(java.util.Locale.US, "%.1f", v)

    // 本片可编辑参数（初始：覆盖值 ?: 全局值）
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
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        entry.name,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        fontSize = 16.sp,
                    )
                },
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
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 概要
            SectionCard("文件信息") {
                Text(
                    "时长 ${Formats.clock(dur)} · 大小 ${Formats.size(entry.sizeBytes)} · " +
                            (entry.probe.videoCodec ?: "?") + " · " + entry.probe.formatName.substringBefore(','),
                    fontSize = 12.sp,
                )
            }

            // 时间轴
            SectionCard("时间轴与切点") {
                if (keyframes == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("关键帧探测中…（读取索引，几秒）", fontSize = 11.sp, color = Color(0xFF64748B))
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
                Text(
                    "拖动圆点调整切点（当前模式：${if (settings.mode == TrimMode.HEAD_TAIL) "头尾裁剪" else "区间保留"}）。" +
                            "红线 = 关键帧对齐后的实际切点；橙色竖线 = 关键帧。",
                    fontSize = 11.sp,
                    color = Color(0xFF64748B),
                )

                // 切点数值
                if (settings.mode == TrimMode.HEAD_TAIL) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = headText,
                            onValueChange = { headText = it },
                            label = { Text("片头(秒)") },
                            singleLine = true,
                            isError = Formats.parseSeconds(headText) == null,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = tailText,
                            onValueChange = { tailText = it },
                            label = { Text("片尾(秒)") },
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
                            append("（${if (dS >= 0) "+" else ""}${String.format(java.util.Locale.US, "%.1f", dS)}s）\n")
                            append("设定终点 ").append(Formats.clockMs(plan.requestedEnd))
                            append(" → 实际 ").append(Formats.clockMs(plan.actualEnd))
                            append("（${if (dE >= 0) "+" else ""}${String.format(java.util.Locale.US, "%.1f", dE)}s）\n")
                            append("保留时长 ≈ ").append(Formats.clock(plan.duration))
                            if (plan.truncated) append("（终点超片长已截断）")
                            if (kfs.isEmpty()) append("\n（无关键帧信息：音频文件或索引缺失，切点不做对齐）")
                        },
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    )
                } else {
                    Text(
                        "当前参数不可处理：${plan.skipReason}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            // 切点抽帧
            if (plan.ok) {
                SectionCard("切点抽帧确认") {
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

            // 轨道列表
            SectionCard("轨道（勾选保留，默认全保留）") {
                entry.probe.streams.forEach { s ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Checkbox(
                            checked = s.index !in dropped,
                            onCheckedChange = { checked ->
                                dropped = if (checked) dropped - s.index else dropped + s.index
                            },
                        )
                        Column(Modifier.padding(start = 4.dp)) {
                            Text(s.label(), fontSize = 13.sp)
                            if (s.isCover) {
                                Text("封面轨，勾选后原样带出", fontSize = 11.sp, color = Color(0xFF64748B))
                            }
                        }
                    }
                }
                if (dropped.isNotEmpty()) {
                    Text(
                        "将丢弃 ${dropped.size} 条轨道",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // 保存
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
                    TextButton(onClick = {
                        vm.setOverride(entry.docUri, null)
                        onClose()
                    }) { Text("恢复全局设置") }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 时间轴：关键帧 + 保留区 + 可拖动切点 + 对齐后实际切点 */
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

                // 轨道底色
                drawRoundRect(
                    color = Color(0xFFE2E8F0),
                    topLeft = Offset(0f, trackTop),
                    size = Size(w, 32f),
                    cornerRadius = CornerRadius(16f, 16f),
                )
                // 保留区（设定）
                val rx0 = x(reqStart)
                val rx1 = x(reqEnd)
                if (rx1 > rx0) {
                    drawRect(
                        color = Color(0xFF38BDF8).copy(alpha = 0.4f),
                        topLeft = Offset(rx0, trackTop),
                        size = Size(rx1 - rx0, 32f),
                    )
                }
                // 关键帧
                val step = if (keyframes.size > 1500) keyframes.size / 1500 + 1 else 1
                var i = 0
                while (i < keyframes.size) {
                    val kx = x(keyframes[i])
                    drawLine(
                        color = Color(0xFFF59E0B).copy(alpha = 0.75f),
                        start = Offset(kx, h / 2 - 24f),
                        end = Offset(kx, h / 2 + 24f),
                        strokeWidth = 1.5f,
                    )
                    i += step
                }
                // 实际切点（红线）
                drawLine(Color(0xFFDC2626), Offset(x(actStart), 4f), Offset(x(actStart), h - 4f), 3f)
                drawLine(Color(0xFFDC2626), Offset(x(actEnd), 4f), Offset(x(actEnd), h - 4f), 3f)
                // 拖动手柄
                drawCircle(Color(0xFF0284C7), radius = 11f, center = Offset(x(reqStart), h / 2))
                drawCircle(Color(0xFF0284C7), radius = 11f, center = Offset(x(reqEnd), h / 2))
                drawCircle(Color.White, radius = 4f, center = Offset(x(reqStart), h / 2))
                drawCircle(Color.White, radius = 4f, center = Offset(x(reqEnd), h / 2))
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("00:00", fontSize = 10.sp, color = Color(0xFF64748B))
            Text(
                "总时长 ${Formats.clock(dur)}",
                fontSize = 10.sp,
                color = Color(0xFF64748B),
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
