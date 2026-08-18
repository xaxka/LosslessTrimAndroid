package com.xixka.losslesstrim.ui

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

/** 吸附到最近的关键帧（阈值 0.5s 内），便于在时间轴上精准对齐切点 */
private fun snapToKeyframe(t: Double, kfs: List<Double>): Double {
    if (kfs.isEmpty()) return t
    var best = kfs[0]
    var bestDiff = Double.MAX_VALUE
    for (k in kfs) {
        val d = abs(k - t)
        if (d < bestDiff) {
            bestDiff = d
            best = k
        }
    }
    return if (bestDiff < 0.5) best else t
}

/** 单文件分析视图：视频预览（五按钮）+ 时间轴 + 关键帧 + 切点抽帧 + 轨道勾选 */
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

    // 区间模式 -1（不切）原样显示
    fun initIntervalText(v: Double): String = if (v < 0) "-1" else Formats.clockMs(v)

    var headText by remember { mutableStateOf(fmtSec(savedOverride?.headSec ?: 0.0)) }
    var tailText by remember { mutableStateOf(fmtSec(savedOverride?.tailSec ?: 0.0)) }
    var startText by remember { mutableStateOf(initIntervalText(savedOverride?.intervalStartSec ?: -1.0)) }
    var endText by remember { mutableStateOf(initIntervalText(savedOverride?.intervalEndSec ?: -1.0)) }
    var dropped by remember { mutableStateOf(savedOverride?.droppedStreams ?: emptySet<Int>()) }

    val head = (Formats.parseSeconds(headText) ?: 0.0).coerceAtLeast(0.0)
    val tail = (Formats.parseSeconds(tailText) ?: 0.0).coerceAtLeast(0.0)
    // 原始区间值（-1 = 不切），保存/应用到全部时保持 -1 语义；计算计划时归一化
    val startRaw = Formats.parseTime(startText) ?: -1.0
    val endRaw = Formats.parseTime(endText) ?: -1.0
    val start = startRaw.coerceAtLeast(0.0)
    val end = if (endRaw < 0) dur else endRaw

    val kfs = keyframes ?: emptyList()
    val plan = remember(head, tail, start, end, settings, kfs) {
        TrimPlanner.alignedPlan(
            entry, settings,
            PerFileOverride(headSec = head, tailSec = tail, intervalStartSec = start, intervalEndSec = end),
            kfs
        )
    }

    // 视频面板 seek 请求（毫秒，<0 忽略）；playheadSec = 当前播放位置（≠切点）
    var seekReq by remember { mutableLongStateOf(-1L) }
    var playheadSec by remember { mutableStateOf(0.0) }

    fun onDragPoint(isStart: Boolean, t: Double) {
        val clamped = t.coerceIn(0.0, dur)
        // 拖动时吸附到最近的关键帧（0.5s 内），便于精准对齐
        val snapped = snapToKeyframe(clamped, kfs)
        when {
            settings.mode == TrimMode.HEAD_TAIL && isStart -> headText = fmtSec(snapped)
            settings.mode == TrimMode.HEAD_TAIL -> tailText = fmtSec((dur - snapped).coerceIn(0.0, dur))
            isStart -> startText = Formats.clockMs(snapped)
            else -> endText = Formats.clockMs(snapped)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { MonoText(entry.name) },
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
            // ---------- 视频预览面板（五按钮：上一关键帧 / 设开始 / 播放暂停 / 设结束 / 下一关键帧） ----------
            VideoPlayerPanel(
                uri = entry.docUri,
                startSec = plan.requestedStart,
                endSec = plan.requestedEnd,
                keyframes = kfs,
                onSetStart = { pos ->
                    // 头尾模式：设片头；区间模式：设开始
                    if (settings.mode == TrimMode.HEAD_TAIL) headText = fmtSec(pos.coerceIn(0.0, dur))
                    else startText = Formats.clockMs(pos.coerceIn(0.0, dur))
                },
                onSetEnd = { pos ->
                    if (settings.mode == TrimMode.HEAD_TAIL) tailText = fmtSec((dur - pos).coerceIn(0.0, dur))
                    else endText = Formats.clockMs(pos.coerceIn(0.0, dur))
                },
                onPositionChange = { playheadSec = it },
                seekRequest = seekReq,
            )

            // ---------- 时间轴 ----------
            SectionCard(
                title = "时间轴与切点",
                subtitle = "点击时间轴定位播放；拖动圆点调整切点（自动吸附关键帧）；红线 = 对齐后实际切点",
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
                    uri = entry.docUri,
                    dur = dur,
                    reqStart = plan.requestedStart,
                    reqEnd = plan.requestedEnd,
                    actStart = plan.actualStart,
                    actEnd = plan.actualEnd,
                    playheadSec = playheadSec,
                    onDragPoint = { isStart, t -> onDragPoint(isStart, t) },
                    onSeek = { t -> seekReq = (t * 1000).toLong() },
                )
                if (settings.mode == TrimMode.HEAD_TAIL) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = headText,
                            onValueChange = { headText = it },
                            label = { Text("片头(秒)") },
                            supportingText = { Text("0 = 不切") },
                            singleLine = true,
                            isError = (Formats.parseSeconds(headText) ?: -1.0) < 0,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = tailText,
                            onValueChange = { tailText = it },
                            label = { Text("片尾(秒)") },
                            supportingText = { Text("0 = 不切") },
                            singleLine = true,
                            isError = (Formats.parseSeconds(tailText) ?: -1.0) < 0,
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = startText,
                            onValueChange = { startText = it },
                            label = { Text("开始(分:秒)") },
                            supportingText = { Text("-1 = 从头") },
                            singleLine = true,
                            isError = start >= end,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = endText,
                            onValueChange = { endText = it },
                            label = { Text("结束(分:秒)") },
                            supportingText = { Text("-1 = 到片尾") },
                            singleLine = true,
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
                            if (kfs.isEmpty()) append("\n（无关键帧信息：切点不做对齐）")
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

            // ---------- 切点抽帧 ----------
            if (plan.ok) {
                SectionCard(title = "切点抽帧确认") {
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

            // ---------- 轨道 ----------
            SectionCard(title = "轨道", subtitle = "勾选保留，默认全保留") {
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
                        // 已无全局默认：0/未设置即存 null，全空时不产生覆盖记录
                        val o = PerFileOverride(
                            headSec = head.takeIf { it > 0.0 },
                            tailSec = tail.takeIf { it > 0.0 },
                            intervalStartSec = startRaw.takeIf { it >= 0.0 },
                            intervalEndSec = endRaw.takeIf { it >= 0.0 },
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
                    }) { Text("清除本片设置") }
                }
            }
            // 目录模式下：把本片的剪辑参数应用到全部视频（两种模式均写入每视频的单独设置）
            if (!entry.isSingleFile) {
                BlOutlinedButton(
                    onClick = {
                        if (settings.mode == TrimMode.HEAD_TAIL) {
                            vm.applyHeadTailToAll(head, tail)
                        } else {
                            vm.applyIntervalToAll(startRaw, endRaw)
                        }
                        onClose()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = plan.ok,
                ) { Text("应用到全部视频") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 时间轴：缩略图层 → Segment 高亮 → Start/End 手柄 → 实际切点 → Playhead（最上层） */
@Composable
fun TimelineBar(
    uri: android.net.Uri,
    dur: Double,
    reqStart: Double,
    reqEnd: Double,
    actStart: Double,
    actEnd: Double,
    playheadSec: Double,
    onDragPoint: (isStart: Boolean, t: Double) -> Unit,
    onSeek: (Double) -> Unit,
) {
    var widthPx by remember { mutableStateOf(0f) }
    // 0=拖 Start，1=拖 End，2=拖 Playhead
    var dragMode by remember { mutableStateOf(2) }

    // 用 rememberUpdatedState 保证拖动期间值变化不触发 pointerInput 重启
    val curReqStart by rememberUpdatedState(reqStart)
    val curReqEnd by rememberUpdatedState(reqEnd)
    val curPlayhead by rememberUpdatedState(playheadSec)

    fun xOf(t: Double): Float =
        if (widthPx > 0 && dur > 0) (t / dur * widthPx).toFloat().coerceIn(0f, widthPx) else 0f

    fun tOf(x: Float): Double =
        if (widthPx > 0 && dur > 0) (x / widthPx * dur).coerceIn(0.0, dur) else 0.0

    Column {
        // 缩略图条（等间距抽帧，不影响时间计算）
        ThumbnailStrip(uri = uri, dur = dur)
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .pointerInput(dur) {
                    detectTapGestures { offset ->
                        if (widthPx > 0 && dur > 0) {
                            onSeek(tOf(offset.x))
                        }
                    }
                }
                .pointerInput(dur) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            if (widthPx > 0 && dur > 0) {
                                val px = xOf(curPlayhead)
                                val sx = xOf(curReqStart)
                                val ex = xOf(curReqEnd)
                                dragMode = when {
                                    kotlin.math.abs(offset.x - px) <= 28f -> 2
                                    kotlin.math.abs(offset.x - sx) <= kotlin.math.abs(offset.x - ex) -> 0
                                    else -> 1
                                }
                            }
                        },
                        onDrag = { change, _ ->
                            if (widthPx > 0 && dur > 0) {
                                change.consume()
                                val t = tOf(change.position.x)
                                when (dragMode) {
                                    2 -> onSeek(t)
                                    // 拖切点时同时 seek 视频预览，方便查看对应画面
                                    0 -> { onDragPoint(true, t); onSeek(t) }
                                    else -> { onDragPoint(false, t); onSeek(t) }
                                }
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

                // 1. 轨道背景
                drawRoundRect(
                    color = BlSurfaceVariant,
                    topLeft = Offset(0f, trackTop),
                    size = Size(w, 32f),
                    cornerRadius = CornerRadius(16f, 16f),
                )
                // 2. Segment 高亮（保留区）
                val rx0 = x(reqStart)
                val rx1 = x(reqEnd)
                if (rx1 > rx0) {
                    drawRect(
                        color = BlPrimary.copy(alpha = 0.55f),
                        topLeft = Offset(rx0, trackTop),
                        size = Size(rx1 - rx0, 32f),
                    )
                }
                // 3. 实际切点（对齐后，红）
                drawLine(BlExt.error, Offset(x(actStart), 2f), Offset(x(actStart), h - 2f), 2f)
                drawLine(BlExt.error, Offset(x(actEnd), 2f), Offset(x(actEnd), h - 2f), 2f)
                // 4. Start / End 手柄
                drawCircle(BlSecondary, radius = 11f, center = Offset(x(reqStart), h / 2))
                drawCircle(BlSecondary, radius = 11f, center = Offset(x(reqEnd), h / 2))
                drawCircle(Color.White, radius = 4f, center = Offset(x(reqStart), h / 2))
                drawCircle(Color.White, radius = 4f, center = Offset(x(reqEnd), h / 2))
                // 5. Playhead（最上层，深色竖线 + 三角头）
                val phx = x(playheadSec)
                drawLine(
                    color = Color(0xFF1A1C1E),
                    start = Offset(phx, 0f),
                    end = Offset(phx, h),
                    strokeWidth = 3f,
                )
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(phx - 7f, 0f)
                    lineTo(phx + 7f, 0f)
                    lineTo(phx, 9f)
                    close()
                }
                drawPath(path, color = Color(0xFF1A1C1E))
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("00:00", style = MaterialTheme.typography.labelSmall, color = BlExt.textSecondary)
            Text(
                "Playhead ${Formats.msFull((playheadSec * 1000).toLong())}",
                style = MaterialTheme.typography.labelSmall,
                color = BlExt.textPrimary,
            )
            Text(
                "总时长 ${Formats.clock(dur)}",
                style = MaterialTheme.typography.labelSmall,
                color = BlExt.textSecondary,
            )
        }
    }
}

/** 缩略图条：等间距抽 12 帧（异步），仅视觉层，不参与时间计算 */
@Composable
fun ThumbnailStrip(uri: android.net.Uri, dur: Double) {
    val context = LocalContext.current
    val thumbs by produceState<List<Bitmap?>>(emptyList(), uri, dur) {
        value = withContext(Dispatchers.IO) {
            if (dur <= 0) return@withContext emptyList()
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(context, uri)
                val n = 12
                (0 until n).map { i ->
                    val t = ((i + 0.5) / n * dur * 1_000_000).toLong()
                    try {
                        mmr.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    } catch (e: Exception) {
                        null
                    }
                }
            } catch (e: Exception) {
                emptyList()
            } finally {
                try {
                    mmr.release()
                } catch (_: Exception) {
                }
            }
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(BlSurfaceVariant),
    ) {
        // 生成期间仅显示空白灰条，不做占位提示
        thumbs.forEach { b ->
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(0.5.dp)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(BlSurfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (b != null) {
                    Image(
                        bitmap = b.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}
