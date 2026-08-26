package com.xixka.losslesstrim.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.xixka.losslesstrim.data.PerFileOverride
import com.xixka.losslesstrim.data.TrimMode
import com.xixka.losslesstrim.data.VideoEntry
import com.xixka.losslesstrim.ffmpeg.Probe
import com.xixka.losslesstrim.trim.TrimPlanner
import com.xixka.losslesstrim.ui.theme.BlExt
import com.xixka.losslesstrim.ui.theme.BlOutlineVariant
import com.xixka.losslesstrim.ui.theme.BlSecondary
import com.xixka.losslesstrim.ui.theme.BlSurfaceVariant
import com.xixka.losslesstrim.util.Formats
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

    // UI 层只显示整数秒：小数精度由关键帧对齐层（TrimPlanner）持有，
    // ffmpeg 执行时用的仍是关键帧真实时间戳，用户无感
    fun fmtSec(v: Double): String = kotlin.math.round(v).toLong().toString()

    // 区间模式 -1（不切）原样显示；回显同样取整秒（clock 无小数）
    fun initIntervalText(v: Double): String = if (v < 0) "-1" else Formats.clock(v)

    var headText by remember { mutableStateOf(fmtSec(savedOverride?.headSec ?: 0.0)) }
    var tailText by remember { mutableStateOf(fmtSec(savedOverride?.tailSec ?: 0.0)) }
    var startText by remember { mutableStateOf(initIntervalText(savedOverride?.intervalStartSec ?: -1.0)) }
    var endText by remember { mutableStateOf(initIntervalText(savedOverride?.intervalEndSec ?: -1.0)) }
    var dropped by remember { mutableStateOf(savedOverride?.droppedStreams ?: emptySet<Int>()) }
    // 默认音轨/字幕轨（全局流索引）。null = 未选（音频兑底随首保留轨，字幕不设默认）
    var defaultAudioIdx by remember { mutableStateOf(savedOverride?.defaultAudioIndex) }
    var defaultSubIdx by remember { mutableStateOf(savedOverride?.defaultSubIndex) }

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
            isStart -> startText = Formats.clock(snapped)
            else -> endText = Formats.clock(snapped)
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
                    else startText = Formats.clock(pos.coerceIn(0.0, dur))
                    // 点击设起点后让 Playhead 跳转到该位置：LosslessCut / 剪映等都走这个交互，
                    // 便于用户继续从该位置预览设终点
                    seekReq = (pos.coerceIn(0.0, dur) * 1000).toLong()
                },
                onSetEnd = { pos ->
                    if (settings.mode == TrimMode.HEAD_TAIL) tailText = fmtSec((dur - pos).coerceIn(0.0, dur))
                    else endText = Formats.clock(pos.coerceIn(0.0, dur))
                    // 同上：设终点后 Playhead 跳转到该位置
                    seekReq = (pos.coerceIn(0.0, dur) * 1000).toLong()
                },
                onPositionChange = { playheadSec = it },
                seekRequest = seekReq,
            )

            // ---------- 时间轴 ----------
            SectionCard(title = null) {
                if (keyframes == null) {
                    LinearProgressIndicator(
                        Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = BlSurfaceVariant,
                    )
                    Text(
                        "关键帧探测中…（读取索引，几秒；结束后生成切点缩略图）",
                        style = MaterialTheme.typography.bodySmall,
                        color = BlExt.textSecondary,
                    )
                }
                TimelineBar(
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
                        SecondsStepperField(
                            value = headText,
                            onValueChange = { headText = it },
                            onStep = { d -> headText = stepSeconds(Formats.parseSeconds(headText), d) },
                            label = "片头(秒)",
                            isError = (Formats.parseSeconds(headText) ?: -1.0) < 0,
                            modifier = Modifier.weight(1f),
                        )
                        SecondsStepperField(
                            value = tailText,
                            onValueChange = { tailText = it },
                            onStep = { d -> tailText = stepSeconds(Formats.parseSeconds(tailText), d) },
                            label = "片尾(秒)",
                            isError = (Formats.parseSeconds(tailText) ?: -1.0) < 0,
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = startText,
                            // 分:秒口径同样取整：滤掉小数点，保留 -1/冒号语法
                            onValueChange = { startText = it.filter { c -> c != '.' } },
                            label = { Text("开始(分:秒)") },
                            singleLine = true,
                            isError = Formats.parseTime(startText) == null || start >= end,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = endText,
                            onValueChange = { endText = it.filter { c -> c != '.' } },
                            label = { Text("结束(分:秒)") },
                            singleLine = true,
                            isError = Formats.parseTime(endText) == null || start >= end,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                if (plan.ok) {
                    // ---------- 切点抽帧（合并进时间轴卡片，缩略图占满宽） ----------
                    // 起点预览：actualStart 是关键帧（新起点第一帧）→ 零解码瞬间出图
                    // 终点预览：actualEnd 是被剪掉的关键帧（丢弃的第一帧）→
                    //   看它前一帧 = 最后保留帧（actualEnd - 0.05，约 1 帧）
                    val endFrameSec = (plan.actualEnd - 0.05).coerceAtLeast(plan.actualStart)
                    val dS = plan.actualStart - plan.requestedStart
                    val dE = plan.actualEnd - plan.requestedEnd
                    // 10-bit 文件硬解颜色不可靠 → 两个切点预览都不给硬解资格
                    val allowHw = entry.probe.hwThumbEligible
                    if (keyframes == null) {
                        // 先探测关键帧、探测结束再生成缩略图：
                        // 1) 对齐前的 actual 切点还会随探测结果变化，提前抽帧必然作废重抽；
                        // 2) 拿到关键帧后 nearestKfSec 非空，ffmpeg 走两段式 seek，抽帧更快更稳；
                        // 3) 避免与 ffprobe 全量扫描并发抢 IO/CPU。
                        // 探测失败（空列表）也算"结束"，此时按未对齐切点只抽一次。
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ThumbPendingPlaceholder("新起点", Modifier.weight(1f))
                            ThumbPendingPlaceholder("新终点", Modifier.weight(1f))
                        }
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            FramePreview(
                                uri = entry.docUri,
                                tSec = plan.actualStart,
                                label = "新起点",
                                timeLabel = "${Formats.clockMs(plan.actualStart)}(${if (dS >= 0) "+" else ""}${String.format(Locale.US, "%.1f", dS)}s)",
                                identity = entry.sizeBytes.toString(),
                                modifier = Modifier.weight(1f),
                                nearestKfSec = kfs.lastOrNull { it <= plan.actualStart },
                                allowHw = allowHw,
                            )
                            FramePreview(
                                uri = entry.docUri,
                                tSec = endFrameSec,
                                label = "新终点",
                                timeLabel = "${Formats.clockMs(plan.actualEnd)}(${if (dE >= 0) "+" else ""}${String.format(Locale.US, "%.1f", dE)}s)",
                                identity = entry.sizeBytes.toString(),
                                modifier = Modifier.weight(1f),
                                nearestKfSec = kfs.lastOrNull { it <= endFrameSec },
                                allowHw = allowHw,
                            )
                        }
                    }

                    // 对齐偏差已拼进 timeLabel，这里只留警告
                    // （探测未结束时 kfs 为空只是暂态，不算"无关键帧信息"）
                    if ((keyframes != null && kfs.isEmpty()) || plan.truncated) {
                        Text(
                            buildString {
                                if (keyframes != null && kfs.isEmpty()) append("无关键帧信息，切点不做对齐")
                                if (plan.truncated) {
                                    if (length > 0) append("  · ")
                                    append("终点超片长已截断")
                                }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = BlExt.textSecondary,
                        )
                    }
                } else {
                    Text(
                        "当前参数不可处理：${plan.skipReason}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            // ---------- 轨道 ----------
            SectionCard(
                title = "轨道",
                subtitle = "勾选保留；音轨/字幕可选一条默认（同类型只可一条）",
            ) {
                entry.probe.streams.forEach { s ->
                    val kept = s.index !in dropped
                    val isAudio = s.isAudio
                    val isSubtitle = s.isSubtitle
                    val canSetDefault = kept && (isAudio || isSubtitle)
                    // 该轨是否为用户选中的默认轨
                    val isDefault = when {
                        isAudio -> defaultAudioIdx == s.index
                        isSubtitle -> defaultSubIdx == s.index
                        else -> false
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 44.dp),
                    ) {
                        Checkbox(
                            checked = kept,
                            onCheckedChange = { checked ->
                                if (checked) {
                                    dropped = dropped - s.index
                                } else {
                                    dropped = dropped + s.index
                                    // 丢掉的轨不能继续作为默认轨：同步清除
                                    if (defaultAudioIdx == s.index) defaultAudioIdx = null
                                    if (defaultSubIdx == s.index) defaultSubIdx = null
                                }
                            },
                        )
                        Column(
                            Modifier
                                .weight(1f)
                                .padding(start = 4.dp)
                        ) {
                            // 标题行：#index 类型 · 编码名 · 分辨率/声道等概要
                            Text(
                                s.label(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (kept) BlExt.textPrimary else BlExt.textSecondary,
                            )
                            // 第二行：标题/比特率/采样率等扩展属性（有则展示，无则不占行）
                            val extras = buildList {
                                s.title?.let { add("标题：$it") }
                                if (s.isAudio) {
                                    s.bitRate?.takeIf { it > 0 }?.let {
                                        add("比特率：${Formats.bitrate(it)}")
                                    }
                                    s.sampleRate?.let { add("采样率：${it / 1000.0} kHz") }
                                }
                                if (s.isVideo && s.width != null && s.height != null) {
                                    add("分辨率：${s.width}×${s.height}")
                                }
                                if (s.isSubtitle && s.width != null && s.height != null) {
                                    // PGS/VOBSUB 等图形字幕带分辨率信息
                                    add("分辨率：${s.width}×${s.height}")
                                }
                            }
                            if (extras.isNotEmpty()) {
                                Text(
                                    extras.joinToString("  ·  "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = BlExt.textSecondary,
                                )
                            }
                            if (s.isCover) {
                                Text(
                                    "封面轨，勾选后原样带出",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = BlExt.textSecondary,
                                )
                            }
                        }
                        // 默认轨选择（音轨/字幕单选；丢掉的轨不可选）
                        if (canSetDefault) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(MaterialTheme.shapes.small)
                                    .clickable {
                                        if (isAudio) {
                                            // 同一类型再点选中项 = 取消选中（回到兑底/不设默认）
                                            defaultAudioIdx = if (isDefault) null else s.index
                                        } else if (isSubtitle) {
                                            defaultSubIdx = if (isDefault) null else s.index
                                        }
                                    }
                                    .padding(start = 4.dp),
                            ) {
                                RadioButton(
                                    selected = isDefault,
                                    onClick = {
                                        if (isAudio) {
                                            defaultAudioIdx = if (isDefault) null else s.index
                                        } else if (isSubtitle) {
                                            defaultSubIdx = if (isDefault) null else s.index
                                        }
                                    },
                                )
                                Text(
                                    if (isAudio) "默认音轨" else "默认字幕",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isDefault) MaterialTheme.colorScheme.secondary else BlExt.textSecondary,
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
                        // 两模式参数互通：以当前模式输入为准，换算出另一模式字段一起写入
                        // （头尾 head↔开始、片尾 tail↔结束时间按 dur 换算），切换模式数值跟随
                        val o = if (settings.mode == TrimMode.HEAD_TAIL) {
                            PerFileOverride(
                                headSec = head.takeIf { it > 0.0 },
                                tailSec = tail.takeIf { it > 0.0 },
                                intervalStartSec = head.takeIf { it > 0.0 },
                                intervalEndSec = (dur - tail).takeIf { tail > 0.0 && it > 0.0 },
                                droppedStreams = dropped,
                                defaultAudioIndex = defaultAudioIdx,
                                defaultSubIndex = defaultSubIdx,
                            )
                        } else {
                            val endSec = if (endRaw < 0) dur else endRaw
                            PerFileOverride(
                                intervalStartSec = startRaw.takeIf { it >= 0.0 },
                                intervalEndSec = endRaw.takeIf { it >= 0.0 },
                                headSec = start.takeIf { it > 0.0 },
                                tailSec = (dur - endSec).takeIf { endSec < dur && it > 0.0 },
                                droppedStreams = dropped,
                                defaultAudioIndex = defaultAudioIdx,
                                defaultSubIndex = defaultSubIdx,
                            )
                        }
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
            // 目录模式下：把本片的剪辑参数（含丢弃轨道）应用到全部视频
            // （两种模式均写入每视频的单独设置，轨道勾选同步下发）
            if (!entry.isSingleFile) {
                BlOutlinedButton(
                    onClick = {
                        if (settings.mode == TrimMode.HEAD_TAIL) {
                            vm.applyHeadTailToAll(head, tail, dropped, defaultAudioIdx, defaultSubIdx)
                        } else {
                            vm.applyIntervalToAll(startRaw, endRaw, dropped, defaultAudioIdx, defaultSubIdx)
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

/**
 * 步进 ±1 秒（UI 层纯整数，无小数可清）；结果钳制 ≥ 0。
 * 兼容旧持久化值可能带小数：+ 向上进整、− 退到整数。
 */
private fun stepSeconds(cur: Double?, delta: Int): String {
    val v = cur ?: 0.0
    val next = if (delta > 0) kotlin.math.floor(v) + delta else kotlin.math.ceil(v) + delta
    return next.toInt().coerceAtLeast(0).toString()
}

/**
 * 片头/片尾秒数输入框 + 一体式数字调节器：上下箭头收进输入框 trailingIcon
 * 插槽（不再外挂按钮列），点击 ±1 秒且清零小数（[stepSeconds]）。
 */
@Composable
private fun SecondsStepperField(
    value: String,
    onValueChange: (String) -> Unit,
    onStep: (Int) -> Unit,
    label: String,
    isError: Boolean,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        // 只保留数字（含粘贴内容），小数点/负号/空格一律滤除——UI 层零小数
        onValueChange = { raw -> onValueChange(raw.filter { it.isDigit() }) },
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        // 纯整数秒：数字键盘无小数点，粘贴/输入过滤掉非数字字符
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        trailingIcon = {
            Column(
                Modifier.width(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                StepperArrow(Icons.Filled.KeyboardArrowUp, "$label +1 秒") { onStep(+1) }
                StepperArrow(Icons.Filled.KeyboardArrowDown, "$label -1 秒") { onStep(-1) }
            }
        },
        modifier = modifier,
    )
}

/**
 * 步进箭头（输入框内）：26dp 高的可点击区域 ×2 = 52dp，恰好收进
 * OutlinedTextField 56dp 的默认最小高度，不撑破输入框。
 * 不用 IconButton：它强制 48dp 最小触达目标，在插槽里会溢出。
 */
@Composable
private fun StepperArrow(
    icon: ImageVector,
    desc: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(width = 28.dp, height = 26.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = desc, modifier = Modifier.size(20.dp))
    }
}

/** 时间轴（三色语义）：浅灰轨道(原片全长) → 红斜纹(剪掉) + 蓝块(保留) → 白手柄 → 红虚线(实际切点) → Playhead */
@Composable
fun TimelineBar(
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
    // 命中阈值按 dp 换算成像素，不同屏幕密度下手感一致
    val grabRadiusPx = with(LocalDensity.current) { 28.dp.toPx() }

    fun xOf(t: Double): Float =
        if (widthPx > 0 && dur > 0) (t / dur * widthPx).toFloat().coerceIn(0f, widthPx) else 0f

    fun tOf(x: Float): Double =
        if (widthPx > 0 && dur > 0) (x / widthPx * dur).coerceIn(0.0, dur) else 0.0

    Column {
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
                                    kotlin.math.abs(offset.x - px) <= grabRadiusPx -> 2
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
                // 三色语义（参照 LosslessCut/剪映）：
                // 浅灰整条轨道 = 原视频全长；实心蓝块 = 保留；红斜纹区 = 剪掉
                val trackH = 44.dp.toPx()
                val trackTop = (h - trackH) / 2f
                val hairline = 1.dp.toPx()
                fun x(t: Double): Float = (t / dur * w).toFloat().coerceIn(0f, w)

                val rs = x(reqStart)
                val re = x(reqEnd)

                fun fmtCut(v: Double): String =
                    if (v < 60.0) String.format(Locale.US, "%.1fs", v) else Formats.clock(v)

                // 1. 轨道底（原视频全时长，浅灰）
                drawRoundRect(
                    color = BlSurfaceVariant.copy(alpha = 0.55f),
                    topLeft = Offset(0f, trackTop),
                    size = Size(w, trackH),
                    cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx()),
                )
                // 2. 剪掉区域：红色淡染 + 45° 斜纹（移除语义，一眼可辨）
                val hatchStep = 10.dp.toPx()
                listOf(0f to rs, re to w).forEach { (from, to) ->
                    if (to - from > 2f) {
                        drawRect(
                            color = BlExt.error.copy(alpha = 0.10f),
                            topLeft = Offset(from, trackTop),
                            size = Size(to - from, trackH),
                        )
                        var hx = from
                        while (hx < to) {
                            val x0 = maxOf(hx, from)
                            val x1 = minOf(hx + trackH, to)
                            drawLine(
                                color = BlExt.error.copy(alpha = 0.45f),
                                start = Offset(x0, trackTop + trackH),
                                end = Offset(x1, trackTop),
                                strokeWidth = 2 * hairline,
                            )
                            hx += hatchStep
                        }
                    }
                }
                // 3. 保留段：唯一实心彩色块（蓝），内缩 3px 形成"clip 块"感
                drawRoundRect(
                    color = BlSecondary,
                    topLeft = Offset(rs, trackTop + hairline),
                    size = Size(maxOf(re - rs, 4f), trackH - 2 * hairline),
                    cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
                )
                // 4. 实际切点（关键帧对齐后）：红色竖虚线，贯穿轨道上下
                val dashLen = 5.dp.toPx()
                val dashStep = 9.dp.toPx()
                listOf(x(actStart), x(actEnd)).forEach { cx ->
                    var dy = trackTop - 6 * hairline
                    val bottom = trackTop + trackH + 6 * hairline
                    while (dy < bottom) {
                        drawLine(
                            color = BlExt.error,
                            start = Offset(cx, dy),
                            end = Offset(cx, minOf(dy + dashLen, bottom)),
                            strokeWidth = 2 * hairline,
                        )
                        dy += dashStep
                    }
                }
                // 5. 白色抓取手柄（保留段两端，上下突出块外，带描边和抓取槽）
                val handleW = 14.dp.toPx()
                val handleProtrude = 9.dp.toPx()
                listOf(rs, re).forEach { hx ->
                    val left = (hx - handleW / 2).coerceIn(0f, w - handleW)
                    drawRoundRect(
                        color = Color.White,
                        topLeft = Offset(left, trackTop - handleProtrude),
                        size = Size(handleW, trackH + 2 * handleProtrude),
                        cornerRadius = CornerRadius(handleW / 2, handleW / 2),
                    )
                    drawRoundRect(
                        color = BlOutlineVariant.copy(alpha = 0.6f),
                        topLeft = Offset(left, trackTop - handleProtrude),
                        size = Size(handleW, trackH + 2 * handleProtrude),
                        cornerRadius = CornerRadius(handleW / 2, handleW / 2),
                        style = Stroke(width = 1.5f * hairline),
                    )
                    val gripGap = 8.dp.toPx()
                    listOf(-gripGap, 0f, gripGap).forEach { dy ->
                        drawLine(
                            color = BlOutlineVariant,
                            start = Offset(left + 4 * hairline, trackTop + trackH / 2 + dy),
                            end = Offset(left + handleW - 4 * hairline, trackTop + trackH / 2 + dy),
                            strokeWidth = 1.5f * hairline,
                        )
                    }
                }
                // 6. 文字标注：保留段白字时长；剪掉段红字 ✂ 时长
                val paint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    textAlign = android.graphics.Paint.Align.CENTER
                    textSize = 11.dp.toPx()
                }
                val cy = trackTop + trackH / 2f - (paint.ascent() + paint.descent()) / 2f
                if (re - rs > 90.dp.toPx()) {
                    paint.color = android.graphics.Color.WHITE
                    paint.isFakeBoldText = true
                    drawContext.canvas.nativeCanvas.drawText(
                        "保留 ${Formats.clock(reqEnd - reqStart)}",
                        (rs + re) / 2f, cy, paint,
                    )
                    paint.isFakeBoldText = false
                }
                paint.color = 0xFFBA1A1A.toInt()
                if (rs > 64.dp.toPx()) {
                    drawContext.canvas.nativeCanvas.drawText(
                        "✂ ${fmtCut(reqStart)}", rs / 2f, cy, paint,
                    )
                }
                if (w - re > 64.dp.toPx()) {
                    drawContext.canvas.nativeCanvas.drawText(
                        "✂ ${fmtCut(dur - reqEnd)}", (re + w) / 2f, cy, paint,
                    )
                }
                // 7. Playhead（最上层，深色竖线 + 三角头）
                val phx = x(playheadSec)
                drawLine(
                    color = Color(0xFF1A1C1E),
                    start = Offset(phx, 2.dp.toPx()),
                    end = Offset(phx, h),
                    strokeWidth = 2.dp.toPx(),
                )
                val tri = 7.dp.toPx()
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(phx - tri, 0f)
                    lineTo(phx + tri, 0f)
                    lineTo(phx, tri + 2.dp.toPx())
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

/**
 * 切点缩略图占位（关键帧探测结束前）。
 * 与 [FramePreview] 同尺寸同布局（16:9 + 标签行），探测结束后原位替换为真实抽帧，
 * 布局不跳动；占位阶段不启动任何 ffmpeg 抽帧。
 */
@Composable
private fun ThumbPendingPlaceholder(label: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(MaterialTheme.shapes.medium)
                .background(BlSurfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text("等关键帧…", color = BlExt.textDisabled, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}