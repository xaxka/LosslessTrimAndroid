package com.xixka.losslesstrim.trim

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentUris
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.FFmpegKitConfig
import com.antonkarpenko.ffmpegkit.FFmpegSession
import com.xixka.losslesstrim.data.FileResult
import com.xixka.losslesstrim.data.Outcome
import com.xixka.losslesstrim.data.ProbeResult
import com.xixka.losslesstrim.data.TrimPlan
import com.xixka.losslesstrim.data.VideoEntry
import com.xixka.losslesstrim.ffmpeg.Probe
import com.xixka.losslesstrim.ffmpeg.SessionBridge
import com.xixka.losslesstrim.ffmpeg.SyncSamples
import com.xixka.losslesstrim.util.Formats
import com.xixka.losslesstrim.util.StorageAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * 前台服务：串行执行无损剪辑队列。
 * 流程：ffprobe 关键帧 → 计算对齐切点 → ffmpeg -c copy 写 .part → 成功后删原文件并重命名。
 * 全直路径 I/O：须已授予"所有文件"权限，ffmpeg 直接读写真实路径（faststart
 * 可靠）；SAF(saf:) 通道已移除，路径不可定位直接失败并提示。
 */
class TrimService : Service() {

    companion object {
        const val ACTION_START = "com.xixka.losslesstrim.ACTION_START"
        const val ACTION_CANCEL = "com.xixka.losslesstrim.ACTION_CANCEL"
        const val CHANNEL_ID = "trim_queue"
        const val NOTIF_ID = 1001

        /**
         * MKV+B帧 seek 前移补偿量（秒）：3/23≈130.4ms 向上取整到 ms，抵消
         * [Formats.secs3] 三位小数舍入后仍留 ~66µs 余量。详见
         * docs/mkv-bframe-seek-offset.md。
         */
        const val SEEK_FUDGE_SEC = 0.131

        /**
         * 是否需要对 -ss 加补偿及补偿量（纯函数，单测覆盖）。
         * 门控：matroska/webm 输入 && 视频含 B 帧 && 起点在片中。
         *
         * ffmpeg 对未设 AVFMT_SEEK_TO_PTS 的封装（matroska/webm 等）在视频含
         * B 帧时会把 -ss 的 seek 目标前移 3/23s（DTS 启发修正，ffmpeg_demux.c），
         * Cues 向后搜索命中前一个关键帧 → 起点早落一个 GOP。
         *
         * 另有第二个偏移源常被忽略：ffmpeg 会把 ic->start_time 加进 seek 目标
         * （ffmpeg_demux.c: timestamp += ic->start_time）。AAC priming 等会让
         * 音/字幕轨起始 pts 为负 → start_time<0（实测样例 -23ms），等效于把目标
         * 又前移了 |start_time|，仅补 3/23s 不够、bug 复现。故：
         *
         *   fudge = SEEK_FUDGE_SEC + max(0, -start_time)
         *
         * start_time>0 时不缩小补偿（多补无害：只要 fudge < GOP 间距，落点不变；
         * 少补有风险）。start_time 未知（null）按 0 处理——探测路径必带该字段，
         * 仅旧缓存行为 null，且缓存有 24h TTL 自然刷新。
         *
         * hasBFrames 未知（null=旧缓存行/平台兜底）按含 B 帧处理：漏加会稳定
         * 复现本 bug，误加仅当 GOP<131ms 才可能出错（极罕见）。
         */
        fun seekFudgeSec(actualStart: Double, probe: ProbeResult): Double {
            if (actualStart <= 0.001) return 0.0
            val matroskaIn = probe.formatName.split(',').any {
                val f = it.trim(); f == "matroska" || f == "webm"
            }
            if (!matroskaIn) return 0.0
            // 无视频流（纯音频/仅封面）：无 video_delay，ffmpeg 不做前移，不补偿
            val video = probe.streams.firstOrNull { it.isVideo } ?: return 0.0
            val hasB = video.hasBFrames ?: 1
            if (hasB <= 0) return 0.0
            val st = probe.startTimeSec ?: 0.0
            return SEEK_FUDGE_SEC + (-st).coerceAtLeast(0.0)
        }

        /**
         * 实际传给 ffmpeg 的 -ss 值 = 关键帧对齐起点 + seek 前移补偿
         * （[seekFudgeSec]）。片头剪（actualStart≈0 → fudge=0）结果为 0，
         * [seekArgs] 会省略 -ss。
         *
         * 纯函数：命令装配（[assembleCommand]）与输出侧同步校验的源采样
         * 锚点（[Probe.probeSyncSamples]）共用——采样锚点必须与真实 -ss
         * 严格一致，锚点偏了 drift 就是假信号。
         */
        fun seekTargetSec(plan: TrimPlan, probe: ProbeResult): Double =
            plan.actualStart + seekFudgeSec(plan.actualStart, probe)

        /**
         * 输入侧 seek 参数（" -ss X -noaccurate_seek"）；片头剪切（ss≈0）返回空串——
         * **不传 -ss**（LosslessCut 同款策略：isCuttingStart 仅 cutFrom>0 才发 -ss）。
         *
         * 根因：-ss 0 时 ffmpeg 的 seek 目标 = 0 + start_time(常为负) − 3/23(B帧
         * 启发修正) < 0，落在视频索引首条目**之前**，matroska 定位失败后从
         * find_stream_info 预读位置续读并进入 skip_to_keyframe，把视频首个关键帧
         * **之前**的音频包整段丢弃。音频与视频起点齐平的片源无感（只丢 priming
         * 零头），但音频超前视频的片源（AAC priming/封装交错间隙，实测复现样例
         * 超前 0.8s）开头 ~0.72s 静音且时长同缩。不传 -ss 则从文件头顺序解复用，
         * 无此丢失。详见 docs/output-timeline.md。
         */
        fun seekArgs(ss: Double): String =
            if (ss > 0.001) " -ss ${Formats.secs3(ss)} -noaccurate_seek" else ""

        /*
         * 输出侧时间戳归零：**全程不传 -avoid_negative_ts**，交给 ffmpeg 默认的
         * auto（任何剪切位置、任何输出容器一致）。
         *
         * 中段剪曾传 make_zero（认为 seek 后首包是源片中段绝对时间戳需归零——
         * 这是误解：-ss 作输入项时 CLI 已用 ts_offset=-ss 把时间戳拉回 0 附近，
         * make_zero 反而添乱）：它把最小 DTS 钉到 0，首帧 PTS 仍残留 B 帧重排
         * 延迟（bf3@12.5fps 实测 start=0.160s，重排更大 → 0.2s+），mkv/mp4
         * 双双触发“起点未归零”校验失败（线上形态：输出校验失败
         * start=0.200s，用户成片被误判失败删除）；音频超前源还会因整体平移
         * 把容器时长撑大（实测 5.2s 窗口写出 6.358s）。
         *
         * auto 模式下各封装用原生机制表达重排延迟，矩阵实测（ffmpeg 4.4 与
         * master 双版本 × 纯视频bf3 / 音视频对齐bf8 / 音频超前0.8sbf8 三源）
         * start_time 全为 0.000、时长准确、音频包数完整、解码零错误：
         * - mp4/mov：edit list + 负 CTS（ctts），与相机直录的 B 帧 mp4 同构
         *   （基线实测：直接 x264 -bf 3 编码的首包 pts=0.000/dts=-0.160/
         *   start=0.000——负 dts 本就是 mp4 的标准形态，不是异常）；
         * - matroska/webm：muxer 以首包为基线归零；
         * - TS/AVI/FLV 等无负时间戳能力的封装：auto 等价“负则平移”，与
         *   make_zero 行为一致（本管线切点恒带 fudge 负偏移，首包必负、
         *   两者同样触发平移），无回退风险。
         */

        /**
         * 字幕包时长钳制 bsf（修复"时间轴结束还在不停播放"）：把每个字幕包的
         * duration 压到剪辑终点内 `max(min(DURATION, T-TS), 0)`（T 为 -t 值）。
         *
         * 症状：末尾长字幕 cue（如 59.5→70.0s）整包保留且 duration 原样写出，
         * 容器 Duration 被字幕末端撑大（实测 30s 成片显示 40s），播放器在画面
         * 结束后继续"播放"黑屏近 10s。音频/视频包 duration 只有 ~20-40ms，溢出
         * 可忽略，钳字幕即够。
         *
         * 表达式变量为 setts bsf 内置：TS/DURATION 是**当前流时基刻度**（非秒），
         * TB 为时基（秒/刻度），故 T 写作 (T秒/TB)；`\\,` 转义 bsf 序列里的逗号。
         * `if(gte(DURATION,0),…,0)` 防护无 duration 的字幕包（如部分 PGS 轨）：
         * DURATION 为 NOPTS 时不钳制写 0。不用 -shortest：短音轨片源会把视频
         * 硬截到音轨末端（LosslessCut 也因此只把它做成默认关闭的实验开关）。
         */
        fun subtitleClampBsf(durSec: Double): String =
            "setts=duration=if(gte(DURATION\\,0)\\,max(min(DURATION\\," +
                "(${Formats.secs3(durSec)}/TB)-TS)\\,0)\\,0)"

        /** 保留轨道中是否含字幕流（决定是否追加字幕钳制 bsf） */
        fun hasKeptSubtitle(probe: ProbeResult, kept: List<Int>): Boolean =
            probe.streams.any { it.isSubtitle && it.index in kept }

        /**
         * 音/字幕轨 disposition 重设。
         *
         * 音轨：用户可在分析页为每文件指定默认音轨（[defaultAudioIndex]，全局流索引）。
         *  - 指定的音轨在保留集中 → 标 default；其余保留音轨清 0
         *  - 未指定 / 指定轨被丢 → 兜底走旧逻辑：输出第一保留音轨 default、其余 0
         *  - 无保留音轨 → 不输出任何音轨 disposition
         *
         * 字幕轨：[defaultSubIndex] 为 null（默认）时不输出字幕 disposition，沿用源
         * disposition（与 matroska -default_mode infer_no_subs 配合，分享场景不强制
         * 弹字幕）。用户显式指定时，输出该轨 default + 其余保留字幕 0：保证全片
         * 只有一条 default 字幕，覆盖源可能存在的多默认字幕互相挤兑。
         *
         * 输出侧序号 = -map 顺序（kept 升序）中各类型流的相对位置。
         */
        fun dispositionArgs(
            probe: ProbeResult,
            kept: List<Int>,
            defaultAudioIndex: Int? = null,
            defaultSubIndex: Int? = null,
        ): String {
            val keptAudio = probe.streams
                .filter { it.isAudio && it.index in kept }
                .sortedBy { it.index }
            val keptSubs = probe.streams
                .filter { it.isSubtitle && it.index in kept }
                .sortedBy { it.index }
            if (keptAudio.isEmpty() && (keptSubs.isEmpty() || defaultSubIndex == null)) return ""
            return buildString {
                if (keptAudio.isNotEmpty()) {
                    // 兜底：用户未指定 / 指定轨被丢 → 默认第一保留音轨
                    val desired = defaultAudioIndex?.let { idx ->
                        keptAudio.indexOfFirst { it.index == idx }.takeIf { it >= 0 }
                    } ?: 0
                    keptAudio.forEachIndexed { i, _ ->
                        if (i == desired) append(" -disposition:a:").append(i).append(" default")
                        else append(" -disposition:a:").append(i).append(" 0")
                    }
                }
                if (keptSubs.isNotEmpty() && defaultSubIndex != null) {
                    val desired = keptSubs.indexOfFirst { it.index == defaultSubIndex }
                    if (desired >= 0) {
                        keptSubs.forEachIndexed { i, _ ->
                            if (i == desired) append(" -disposition:s:").append(i).append(" default")
                            else append(" -disposition:s:").append(i).append(" 0")
                        }
                    }
                }
            }
        }

        /**
         * 字幕轨兜底：probe 未检测到字幕流时追加 -map 0:s? 带出所有字幕轨。
         *
         * 根因：平台兜底 MediaExtractor 对 MKV 内嵌 ASS/PGS 等字幕不暴露 track，
         * probe.streams 里没有 → kept 里没有 → -map 0:index 不会带 → 字幕静默丢失。
         * ffprobe 严格路径检测到字幕时已在 kept 里显式 -map，不重复加避免冲突。
         * `?`=无字幕时不报错（与 -map 0:t? 附件轨同款约定）。
         */
        fun subtitleFallbackArgs(probe: ProbeResult): String =
            if (probe.streams.none { it.isSubtitle }) " -map 0:s?" else ""

        /**
         * MKV 输出保留附件轨（`-map 0:t?`，`?`=无附件时不报错）：ASS 字幕的
         * 字体、封面等挂在 attachment 流上，不显式 map 会整轨丢失——ASS 特效
         * 字幕没字体时排版/字体全毁，用户感知"字幕剪坏了"。
         * webm 不加：webm profile 严格校验流类型，附件会被拒。
         */
        fun attachmentArgs(target: OutputTarget): String =
            if (target.muxer == "matroska" && target.ext != "webm") " -map 0:t?" else ""

        /**
         * TS/PS 系容器初始偏移参数：mpegts 默认 muxdelay 会让成片 start_time
         * =1.4s（实测），清零后 0.000，校验管线才能用统一严格阈值。
         */
        fun muxDelayArgs(target: OutputTarget): String =
            if (target.muxer == "mpegts" || target.muxer == "mpeg") " -muxdelay 0 -muxpreload 0" else ""

        /**
         * matroska 输出不强制字幕 default（`-default_mode infer_no_subs`）：
         * 源里字幕非 default 时，剪出后不应被 muxer 强加成 default——
         * 分享场景强制弹字幕不友好（LosslessCut 同款，issue #972）。
         * webm 不加：webm profile 严格，私有选项可能被拒。
         */
        fun matroskaFlagsArgs(target: OutputTarget): String =
            if (target.muxer == "matroska" && target.ext != "webm") " -default_mode infer_no_subs" else ""

        /**
         * 成功但有兼容风险的非阻断提示（纯函数，单测覆盖）：
         * 1. 旋转元数据 × MKV 输出：数据层面 ffmpeg≥6.1 会写 Projection 元素
         *    保留，但部分播放器（硬解播放器/旧 Android）不识别 → 横屏显示；
         * 2. Dolby Vision：无损保留原样，但非 DV 设备播放可能偏色/黑屏。
         */
        fun timelineWarnings(probe: ProbeResult, kept: List<Int>, target: OutputTarget): List<String> {
            val w = ArrayList<String>()
            val keptVideo = probe.streams.filter { it.isVideo && it.index in kept }
            val rotated = keptVideo.any { ((it.rotation ?: 0) % 360 + 360) % 360 != 0 }
            if (rotated && target.muxer == "matroska") {
                w += "源含旋转元数据，部分播放器不识别 MKV 旋转标记可能横屏显示；如遇此问题建议 MP4 输出"
            }
            if (keptVideo.any { it.isDolbyVision }) {
                w += "源为 Dolby Vision，非 DV 设备播放可能偏色/黑屏（内容无损保留原样）"
            }
            return w
        }

        /**
         * 输出时间轴校验（纯函数，单测覆盖）。返回问题列表，空=通过。
         *
         * - 起点：|start_time| ≤ [startToleranceSec]（默认 0.1s）——防"时间不
         *   从 0 开始"类回归（make_zero 误用/-t 锚定漂移）；TS 系由调用方放宽
         *   （muxdelay 清零后仍可能有小 PCR 前导）。
         * - 时长：|duration − 期望| ≤ [DURATION_TOLERANCE_SEC]——同时覆盖字幕
         *   拖尾（容器被长 cue 撑大，实测 30s 成片显示 40s）与 -t 计算错误。
         * - 流存在性：保留的视频/音频流必须在输出里——防 -map/容器兼容翻车
         *   产生"假成功"空壳（ffmpeg 越界 seek 时退出码可能仍为 0）。
         */
        fun assessTimeline(
            start: Double?,
            dur: Double?,
            expectedDurSec: Double,
            hasVideo: Boolean,
            hasAudio: Boolean,
            videoKept: Boolean,
            audioKept: Boolean,
            startToleranceSec: Double = 0.1,
        ): List<String> {
            val issues = ArrayList<String>()
            when {
                start == null -> issues += "起始时间未知"
                start < -startToleranceSec || start > startToleranceSec ->
                    issues += "起点未归零(start=${Formats.secs3(start)}s)"
            }
            if (dur == null || abs(dur - expectedDurSec) > DURATION_TOLERANCE_SEC) {
                issues += "时长异常(${dur?.let { Formats.secs3(it) } ?: "?"}s，期望约${Formats.secs3(expectedDurSec)}s)"
            }
            if (videoKept && !hasVideo) issues += "输出缺少视频流"
            if (audioKept && !hasAudio) issues += "输出缺少音频流"
            return issues
        }

        /** 时长校验容差（秒）：GOP 对齐后实际时长与计划值的合法偏差上限 */
        const val DURATION_TOLERANCE_SEC = 2.0

        /** 音视频同步容差（秒）：|drift| 超过即判"音视频不同步"失败 */
        const val SYNC_DRIFT_TOLERANCE_SEC = 0.5

        /** 字幕时间轴窗口容差（秒）：首 cue 落在 [−0.5, ov+期望时长+0.5] 之外判异常 */
        const val SUBTITLE_WINDOW_TOLERANCE_SEC = 0.5

        /**
         * 采样防呆：源视频采样点与 -ss 锚点的最大合法偏离（秒）。
         * 落点=≤锚点的关键帧，合法偏离上限即 GOP 长度；超过 30s 视为区间
         * seek 失败落回文件头（采样值≈文件头首包），按未采到处理——不防呆
         * 会算出 ≈切点偏移量的假 drift 误杀好成片（残留边界：切点很浅时
         * seek 失败与超长 GOP 落点不可分，接受，由时长校验兜底）。
         */
        const val SYNC_SAMPLE_MAX_LAG_SEC = 30.0

        /** 采样防呆：源侧音画首包 pts 差的合法上限（秒）。真实片源音画偏移远小于此值，超过多为音频采样落回文件头 */
        const val SYNC_SRC_AV_OFFSET_MAX_SEC = 10.0

        /**
         * 输出同步校验（纯函数，单测覆盖）：时间轴（起点/时长/流存在性）
         * 之外，验证"剪完之后音画/字幕的时间还对得上"。返回问题列表，空=通过。
         *
         * 1) 音视频同步——两步采样 drift 模型：
         *
         *        drift_av = (oa − ov) − (sa − sv)
         *
         *    源/输出各取"切点时刻"的音画 pts 对（sv/sa 源侧锚定 -ss 落点，
         *    ov/oa 输出侧首包，采样见 [Probe.probeSyncSamples]）。同步保持
         *    时两组音画间隔相等（drift≈0，包粒度噪声 <0.1s）；两类真实坏
         *    形态都会放大成大 drift：头部音频被丢（-ss 0 + make_zero，实测
         *    drift=+0.743s）、音频/视频未随切点平移（drift≈切点偏移量）。
         *    容差 0.5s：AAC 帧粒度(~23ms)、mkv 首包基线、mp4 edit list 负
         *    CTS 等正常封装噪声都在 ±0.1s 内，与真实 desync 间隔一个数量级。
         *    任一采样为 null（纯视频/纯音频源、区间读失败、防呆拦截）→ 跳过：
         *    采样失败 ≠ 输出坏。
         *
         * 2) 字幕时间轴——输出侧绝对界：首个字幕包 pts 必须落在
         *
         *        [−0.5, ov + 期望时长 + 0.5]
         *
         *    上界锚定输出视频首帧 ov（平移免疫：音频超前源整体平移后 cue
         *    随 ov 平移，窗口跟着走）；**期望时长用对齐后实际区间
         *    （plan.duration）而非探测时长**——拖尾字幕会把容器时长撑大，
         *    用探测时长做界恰好放过越界字幕。窗口内无 cue（稀疏字幕）/
         *    未保留字幕 → 采样 null → 跳过。
         *    只查输出侧：源侧字幕采样不可行（ffprobe 字幕流区间 seek 不
         *    生效，从头读全量字幕包对 GB 级源=整文件 demux）；真实管线
         *    -ss 作输入项对所有流统一 ts_offset，字幕错位的现实形态
         *    （cue 整体越过窗口）恰被此界捕获。
         */
        fun assessSync(
            samples: SyncSamples,
            seekSec: Double,
            expectedDurSec: Double,
        ): List<String> {
            val issues = ArrayList<String>()
            // 采样防呆：见各常量注释；sv 被拦则 sa 锚点随之失效，连带跳过
            var sv = samples.srcVideoPts
            var sa = samples.srcAudioPts
            if (seekSec > 0.001 && sv != null && abs(sv - seekSec) > SYNC_SAMPLE_MAX_LAG_SEC) {
                sv = null
                sa = null
            }
            if (sv != null && sa != null && abs(sa - sv) > SYNC_SRC_AV_OFFSET_MAX_SEC) {
                sa = null
            }
            val ov = samples.outVideoPts
            val oa = samples.outAudioPts
            if (sv != null && sa != null && ov != null && oa != null) {
                val drift = (oa - ov) - (sa - sv)
                if (abs(drift) > SYNC_DRIFT_TOLERANCE_SEC) {
                    issues += "音视频不同步(drift=${Formats.secs3(drift)}s)"
                }
            }
            val sub = samples.outSubtitlePts
            if (sub != null) {
                val hi = (ov ?: 0.0) + expectedDurSec + SUBTITLE_WINDOW_TOLERANCE_SEC
                if (sub < -SUBTITLE_WINDOW_TOLERANCE_SEC || sub > hi) {
                    issues += "字幕时间轴异常(start=${Formats.secs3(sub)}s)"
                }
            }
            return issues
        }

        /**
         * 剪辑命令装配（纯函数，单测逐段断言——**这是防回退的关键锚点**：
         * 任何人在这里去掉 fudge/钳制/disposition 等装配步骤，TrimCommandTest
         * 直接红，不必等真机）。
         *
         * -ss 补偿见 [seekFudgeSec]；-t 锚定在 -ss 值上（停止条件 pts ≥ ss+t），
         * 须同步减去同量，终点才能仍落在 actualEnd。片头剪切不传 -ss（见
         * [seekArgs]）；全程不传 -avoid_negative_ts（见上方时间戳归零注释）；
         * 字幕时长钳制见 [subtitleClampBsf]（clampSubtitles=false 为校验失败
         * 后的降级重跑路径）；disposition/附件/章节/muxdelay 见各函数注释。
         */
        fun assembleCommand(
            inParam: String,
            outParam: String,
            plan: TrimPlan,
            kept: List<Int>,
            target: OutputTarget,
            probe: ProbeResult,
            clampSubtitles: Boolean = true,
            defaultAudioIndex: Int? = null,
            defaultSubIndex: Int? = null,
        ): String {
            val ss = seekTargetSec(plan, probe)
            val dur = (plan.actualEnd - ss).coerceAtLeast(0.001)
            val sb = StringBuilder()
            sb.append("-hide_banner -y")
            sb.append(seekArgs(ss))
            sb.append(" -i \"").append(inParam).append("\"")
            sb.append(" -t ").append(Formats.secs3(dur))
            for (i in kept) sb.append(" -map 0:").append(i)
            sb.append(subtitleFallbackArgs(probe))
            sb.append(attachmentArgs(target))
            sb.append(" -c copy -map_metadata 0 -map_chapters 0")
            if (clampSubtitles && hasKeptSubtitle(probe, kept)) {
                sb.append(" -bsf:s ").append(subtitleClampBsf(dur))
            }
            sb.append(dispositionArgs(probe, kept, defaultAudioIndex, defaultSubIndex))
            sb.append(muxDelayArgs(target))
            sb.append(matroskaFlagsArgs(target))
            if (target.muxer == "mp4") sb.append(" -movflags +faststart+use_metadata_tags")
            sb.append(" -ignore_unknown")
            sb.append(" -f ").append(target.muxer)
            sb.append(" \"").append(outParam).append("\"")
            return sb.toString()
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var lastNotifyAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                TrimController.cancel()
                // 立即中断正在运行的 ffmpeg 会话（否则当前文件会完整跑完才停）
                try {
                    FFmpegKit.cancel()
                } catch (_: Exception) {
                }
                if (!TrimController.running) stopSelf()
            }
            else -> {
                startAsForeground()
                // 认领启动请求：running 已被 start() 提前占位置 true（防重入），
                // 不能再用 !running 判断，否则 runQueue 永远不会被启动
                if (TrimController.takeStartRequest()) {
                    serviceScope.launch { runQueue() }
                } else if (!TrimController.running) {
                    // 防御：无待启动请求且无队列在跑（重复投递/启动失败回滚后的
                    // 残留投递），撤前台通知直接退出，避免"准备中…"僵尸通知
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        val ch = NotificationChannel(
            CHANNEL_ID, "批量剪辑进度", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "无损批量剪辑处理进度"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun startAsForeground() {
        val n = buildNotification(0, 0, "准备中…", 0f, "")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun buildNotification(done: Int, total: Int, name: String, progress: Float, speed: String): Notification {
        val cancelIntent = PendingIntent.getService(
            this, 2,
            Intent(this, TrimService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (total > 0) "无损批量剪辑 ${done.coerceAtMost(total)}/$total" else "无损批量剪辑"
        val text = buildString {
            append(name)
            if (speed.isNotEmpty()) append("  ").append(speed)
            if (progress > 0f) append("  ").append((progress * 100).toInt()).append("%")
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .apply {
                if (total > 0) setProgress(total, done.coerceAtMost(total), false)
                else setProgress(0, 0, true)
            }
            .addAction(0, "取消", cancelIntent)
            .build()
    }

    private fun notifyProgress(done: Int, total: Int, name: String, progress: Float, speed: String) {
        val now = System.currentTimeMillis()
        if (now - lastNotifyAt < 400 && progress < 1f) return
        lastNotifyAt = now
        try {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            nm.notify(NOTIF_ID, buildNotification(done, total, name, progress, speed))
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
    }

    private fun publishRunning(done: Int, total: Int, name: String, progress: Float, speed: String) {
        TrimController.queueUi.value = QueueUi.Running(done, total, name, progress, speed)
        notifyProgress(done, total, name, progress, speed)
    }

    // ---------------- 队列主流程 ----------------

    private suspend fun runQueue() {
        TrimController.running = true
        try {
            val jobs = TrimController.takeJobs()
            val results = ArrayList<FileResult>()
            var idx = 0
            var stopped = false
            while (idx < jobs.size) {
                if (TrimController.cancelRequested) {
                    stopped = true
                    break
                }
                val job = jobs[idx]
                val res = processJob(job, idx, jobs.size)
                results += res
                // 先自增再判取消：当前文件的结果已入列，补录从未处理的下一个开始，
                // 否则同一文件会被补录第二条 CANCELLED，导致结果页 key 冲突崩溃
                idx++
                if (res.outcome == Outcome.CANCELLED) {
                    stopped = true
                    break
                }
            }
            if (stopped) {
                for (j in idx until jobs.size) {
                    results += FileResult(
                        entry = jobs[j].entry,
                        plan = TrimPlanner.logicalPlan(jobs[j].entry, jobs[j].settings, jobs[j].override),
                        outcome = Outcome.CANCELLED,
                        origSize = jobs[j].entry.sizeBytes,
                        reason = "已取消（未处理）",
                    )
                }
            }
            TrimController.lastResults.value = results
            TrimController.queueUi.value = QueueUi.Finished(results)
        } finally {
            TrimController.running = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun processJob(job: TrimJob, idx: Int, total: Int): FileResult {
        val entry = job.entry
        val s = job.settings
        publishRunning(idx, total, entry.name, 0f, "")

        // 1. 逻辑计划先行：不可处理/剪完为空的文件直接跳过，不做任何扫描
        val logical = TrimPlanner.logicalPlan(entry, s, job.override)
        if (!logical.ok) {
            return FileResult(entry, logical, Outcome.SKIPPED, entry.sizeBytes, reason = logical.skipReason)
        }

        // 1b. 容器解析受限：ffprobe 严格失败 + platform 兜底拿到时长但拿不到
        //     stream 列表（典型：moov atom not found / 私有 codec_tag 异常）—
        //     列表页能识别（看到时长+大小），但剪辑侧没法逐轨 -map，提前判
        //     失败并给个比"未保留任何轨道"更准的提示（用户可去桌面 ffmpeg
        //     重新 mux 后再来）
        if (entry.probe.streams.isEmpty()) {
            return FileResult(
                entry, logical, Outcome.FAILED, entry.sizeBytes,
                reason = "容器解析受限（${entry.probe.error ?: "ffprobe 失败"}），可尝试桌面 ffmpeg 重新封装后再处理",
            )
        }

        // 2. 关键帧对齐：只对"真要切"的文件探测，且只读切点邻域（-read_intervals
        //    定点读几 MB）而非整文件全量扫描（GB 级 4K 片源每次整读数十秒，是
        //    "每处理完一个文件干等半天"的主因）。全片保留的文件对齐无意义，
        //    直接用逻辑计划（对齐 0/片长最多各缩一个 GOP，不值得为此读全片）
        val dur = entry.probe.durationSec
        val needTrim = logical.requestedStart > 0.001 || logical.requestedEnd < dur - 0.001
        val plan = if (needTrim) {
            val kfs = Probe.probeKeyframesNear(
                this, entry.docUri,
                listOf(logical.requestedStart, logical.requestedEnd), dur
            )
            TrimPlanner.alignedPlan(entry, s, job.override, kfs)
        } else {
            logical
        }
        if (!plan.ok) {
            return FileResult(entry, plan, Outcome.SKIPPED, entry.sizeBytes, reason = plan.skipReason)
        }

        // 5. 输出目标：单文件另存为 / 目录内 .part 流程
        val target = Containers.resolve(s.container, entry.ext)
            ?: return FileResult(
                entry, plan, Outcome.FAILED, entry.sizeBytes,
                reason = "不支持的容器 .${entry.ext}，请改用 MP4/MKV 输出"
            )

        // 轨道映射（按勾选逐轨 -map 0:i）
        val dropped = job.override?.droppedStreams ?: emptySet()
        val kept = entry.probe.streams.map { it.index }.filter { it !in dropped }
        if (kept.isEmpty()) {
            return FileResult(entry, plan, Outcome.FAILED, entry.sizeBytes, reason = "未保留任何轨道")
        }
        // 用户指定的默认音轨/字幕轨（全局流索引）；null 表示走兜底逻辑
        val defAudio = job.override?.defaultAudioIndex
        val defSub = job.override?.defaultSubIndex

        // 无实际改动（全片保留、未丢轨道、未改默认轨、容器不变且为覆盖模式）：
        // 跳过重写，避免"未设置裁剪"的文件被无意义地删除重建（覆盖模式下原文件会被替换）
        if (job.outputUri == null && s.overwrite &&
            plan.actualStart <= 0.001 &&
            plan.actualEnd >= entry.probe.durationSec - 0.001 &&
            target.ext == entry.ext && dropped.isEmpty() &&
            defAudio == null && defSub == null
        ) {
            return FileResult(
                entry, plan, Outcome.SKIPPED, entry.sizeBytes,
                reason = "全片保留、未丢轨道且容器不变，无需处理"
            )
        }

        // SAF(saf:)读通道已移除：fork 的 SAF 参数构造存在越界崩溃且描述符读慢。
        // filePath 必须存在（扫描时已按授权状态记录），缺失即判定为配置问题
        val inParam = entry.filePath?.let { File(it).takeIf { f -> f.exists() }?.absolutePath }
            ?: return FileResult(
                entry, plan, Outcome.FAILED, entry.sizeBytes,
                reason = "无法定位源文件路径（未授予\u201c所有文件\u201d权限？SAF 通道已移除，授权后请重扫）"
            )

        // ---- 单文件模式：直接写另存目标（无目录写权限，不走 .part/rename） ----
        // 直路径写出：绕开 saf: 只写描述符——faststart 收尾要回 seek 移数据重写
        // moov，只写 SAF fd 上不可靠，正是"输出校验失败 moov atom not found"的
        // 根因。SAF 写通道已移除，目标不可定位直接失败并提示。
        if (job.outputUri != null) {
            val outFile = StorageAccess.writableTarget(this, job.outputUri)
                ?: return FileResult(
                    entry, plan, Outcome.FAILED, entry.sizeBytes,
                    reason = "另存目标无法定位为本地路径（未授权或非本地存储），SAF 通道已移除"
                )
            val run = runTrimVerified(inParam, outFile.absolutePath, plan, kept, target, entry, idx, total, defAudio, defSub)
            val rc = run.session?.returnCode
            if (rc == null) {
                outFile.delete()
                return FileResult(entry, plan, Outcome.FAILED, entry.sizeBytes, reason = "ffmpeg 会话异常结束")
            }
            if (rc.isValueCancel || TrimController.cancelRequested) {
                outFile.delete()
                return FileResult(entry, plan, Outcome.CANCELLED, entry.sizeBytes, reason = "已取消（原文件未动）")
            }
            if (!rc.isValueSuccess) {
                outFile.delete()
                return FileResult(entry, plan, Outcome.FAILED, entry.sizeBytes, reason = extractError(run.session!!))
            }
            val newSize = outFile.length().coerceAtLeast(0)
            // 时间轴校验问题（起点/时长/流存在性）在 runTrimVerified 内已含降级重跑
            val issues = run.issues.orEmpty()
            if (newSize <= 0 || issues.isNotEmpty()) {
                outFile.delete()
                return FileResult(
                    entry, plan, Outcome.FAILED, entry.sizeBytes,
                    reason = "输出校验失败（${issues.firstOrNull() ?: "空文件"}），请重试"
                )
            }
            publishRunning(idx + 1, total, entry.name, 1f, "")
            refreshMediaStore(outFile.absolutePath)
            return FileResult(
                entry, plan, Outcome.SUCCESS, entry.sizeBytes, newSize,
                reason = "已另存为新文件",
                warnings = timelineWarnings(entry.probe, kept, target),
            )
        }

        // ---- 目录模式：输出目录（直路径）与目标文件名 ----
        // SAF 输出管线已移除：输出目录必须能定位为本地路径，否则直接失败。
        val inDirFile = StorageAccess.accessibleFile(this, entry.folderUri ?: return FileResult(
            entry, plan, Outcome.FAILED, entry.sizeBytes, reason = "缺少目录信息"
        ))?.takeIf { it.isDirectory }
            ?: return FileResult(
                entry, plan, Outcome.FAILED, entry.sizeBytes,
                reason = "输出目录无法定位为本地路径（未授权或非本地存储），SAF 通道已移除"
            )
        val outDirFile: File
        val finalName: String
        if (s.overwrite) {
            outDirFile = inDirFile
            finalName = if (target.ext == entry.ext) entry.name else "${entry.baseName}.${target.ext}"
        } else {
            outDirFile = ensureCutDir(inDirFile)
                ?: return FileResult(
                    entry, plan, Outcome.FAILED, entry.sizeBytes,
                    reason = "无法创建/访问 CutVideos 子目录"
                )
            finalName = "${entry.baseName}.${target.ext}"
        }

        // ---- 直路径管线：普通文件 I/O ----
        // 写 .part → File 改名替换 → 直路径终检。
        // 规避 saf: 只写描述符上 faststart 回移数据不可靠导致坏 MP4 的问题。
        val partFile = File(outDirFile, "$finalName.part")
        if (partFile.exists()) partFile.delete()

        val run = runTrimVerified(inParam, partFile.absolutePath, plan, kept, target, entry, idx, total, defAudio, defSub)
        val session = run.session

        val rc = session?.returnCode
        if (rc == null) {
            partFile.delete()
            return FileResult(entry, plan, Outcome.FAILED, entry.sizeBytes, reason = "ffmpeg 会话异常结束")
        }
        if (rc.isValueCancel || TrimController.cancelRequested) {
            partFile.delete()
            return FileResult(entry, plan, Outcome.CANCELLED, entry.sizeBytes, reason = "已取消（原文件未动）")
        }
        if (!rc.isValueSuccess) {
            partFile.delete()
            return FileResult(entry, plan, Outcome.FAILED, entry.sizeBytes, reason = extractError(session!!))
        }
        // 时间轴校验问题（起点/时长/流存在性）：降级重跑已在 runTrimVerified 内完成，
        // 仍不过说明输出真有问题，不进入替换原片的流程
        val timelineIssues = run.issues.orEmpty()
        if (timelineIssues.isNotEmpty()) {
            partFile.delete()
            return FileResult(
                entry, plan, Outcome.FAILED, entry.sizeBytes,
                reason = "输出校验失败（${timelineIssues.joinToString("；")}），请重试"
            )
        }

        // 成功：替换文件（铁律：备份未做成绝不动原片；最终文件未校验绝不删备份）
        val partLen = partFile.length().coerceAtLeast(0)
        val finalFile = File(outDirFile, finalName)
        val origFile = entry.filePath?.let { File(it) }?.takeIf { it.exists() }
            ?: File(outDirFile, entry.name)
        var backupFile: File? = null      // 覆盖模式：原片备份
        var displacedFile: File? = null   // CutVideos 模式：被顶替的旧成片
        if (s.overwrite) {
            if (origFile.exists()) {
                backupFile = File(outDirFile, "${entry.baseName}.trimbackup.${System.currentTimeMillis()}")
                if (!origFile.renameTo(backupFile)) {
                    // 备份改名失败：跳过此文件，原片不动（.part 清理）
                    partFile.delete()
                    return FileResult(
                        entry, plan, Outcome.FAILED, entry.sizeBytes,
                        reason = "无法备份原片（此目录不支持改名），已跳过，原文件未动"
                    )
                }
            }
        } else {
            if (finalFile.exists()) {
                displacedFile = File(outDirFile, "$finalName.oldtrim")
                if (!finalFile.renameTo(displacedFile)) finalFile.delete()
            }
        }
        if (!partFile.renameTo(finalFile)) {
            // 回滚：备份/旧成片还原原名，数据完整
            backupFile?.renameTo(origFile)
            displacedFile?.renameTo(finalFile)
            return FileResult(
                entry, plan, Outcome.FAILED, entry.sizeBytes,
                reason = "输出替换失败（数据完整保留在 ${partFile.name}，可手动改名）"
            )
        }

        // 终检一：最终文件字节数必须与 .part 一致；终检二：轻量探测必须可解析
        // （防 moov 缺失等坏文件冒充成功——直路径下 faststart 可靠，此检查退化为兜底）
        val finalLen = finalFile.length().coerceAtLeast(0)
        val sizeBad = partLen > 0 && finalLen != partLen
        val finalProbe = Probe.verifyMedia(finalFile.absolutePath)
        if (sizeBad || !finalProbe.probeOk) {
            finalFile.delete()
            backupFile?.renameTo(origFile)       // 还原原片
            displacedFile?.renameTo(finalFile)   // 还原旧成片
            return FileResult(
                entry, plan, Outcome.FAILED, entry.sizeBytes,
                reason = "输出校验失败（${finalProbe.error ?: "字节数不一致"}）${if (backupFile != null) "，已回滚为原文件" else ""}，请重试"
            )
        }
        // 校验通过，才允许删除备份与残留
        backupFile?.delete()
        displacedFile?.delete()
        // 刷新 MediaStore：同路径改名替换绕过了媒体库，相册/播放器否则仍按
        // 旧条目显示旧时间轴；覆盖 + 容器转换时旧扩展名路径已不存在，清残留行
        if (s.overwrite) refreshMediaStore(finalFile.absolutePath, origFile.absolutePath)
        else refreshMediaStore(finalFile.absolutePath)
        publishRunning(idx + 1, total, entry.name, 1f, "")
        return FileResult(
            entry, plan, Outcome.SUCCESS, entry.sizeBytes, finalLen,
            warnings = timelineWarnings(entry.probe, kept, target),
        )
    }

    /** CutVideos 子目录（直路径）：已存在直接复用，否则 mkdirs 创建 */
    private fun ensureCutDir(parent: File): File? {
        val dir = File(parent, "CutVideos")
        return when {
            dir.isDirectory -> dir
            dir.mkdir() || dir.mkdirs() -> dir
            else -> null
        }
    }

    /**
     * 剪辑落盘后刷新 MediaStore。覆盖模式成品与原片**同路径**（File 改名替换），
     * 全程绕过 MediaStore——系统相册/播放器读到的仍是旧条目（旧时长、旧缩略图），
     * 表现为"切完还显示原来的时间轴"。对仍存在的路径触发媒体扫描让元数据归位；
     * 已不存在的路径（容器转换换扩展名后原文件被改名走）顺手清残留行。
     */
    private fun refreshMediaStore(vararg paths: String) {
        val existing = paths.filter { File(it).exists() }.distinct()
        if (existing.isNotEmpty()) {
            try {
                MediaScannerConnection.scanFile(this, existing.toTypedArray(), null, null)
            } catch (_: Exception) {
            }
        }
        for (p in paths.filterNot { File(it).exists() }) removeStaleMediaRow(p)
    }

    /** 清掉指向已不存在文件的 MediaStore 残留行（API 29+；尽力而为，失败不影响剪辑结果） */
    private fun removeStaleMediaRow(path: String) {
        if (Build.VERSION.SDK_INT < 29) return
        try {
            val filesUri = MediaStore.Files.getContentUri("external")
            val ids = ArrayList<Long>()
            contentResolver.query(
                filesUri,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DATA}=?",
                arrayOf(path),
                null,
            )?.use { c ->
                while (c.moveToNext()) ids.add(c.getLong(0))
            }
            // 先收齐再删：避免边遍历边删导致游标窗口失效
            for (id in ids) {
                contentResolver.delete(ContentUris.withAppendedId(filesUri, id), null, null)
            }
        } catch (_: Exception) {
        }
    }

    /** 单次剪辑执行结果：issues=null 表示 ffmpeg 未成功（调用方按会话 rc 分支处理） */
    private class TrimRun(val session: FFmpegSession?, val issues: List<String>?)

    /**
     * 执行剪辑 + 输出时间轴校验（含降级重跑）。
     *
     * 成功路径（rc=success）跑 [verifyOutputTimeline]；有问题且本轮用过字幕
     * 钳制 bsf 时，去掉 bsf 重跑一次——钳制是加固项不是必需项：exotic 字幕
     * 轨上 bsf 翻车不应让整个文件失败，降级后最坏回退为"结尾可能拖尾"的
     * 旧行为（可接受），远好于直接失败。二次仍失败才交给调用方判 FAILED。
     */
    private suspend fun runTrimVerified(
        inParam: String,
        outParam: String,
        plan: TrimPlan,
        kept: List<Int>,
        target: OutputTarget,
        entry: VideoEntry,
        idx: Int,
        total: Int,
        defaultAudioIndex: Int? = null,
        defaultSubIndex: Int? = null,
    ): TrimRun {
        val durSec = plan.duration
        val useBsf = hasKeptSubtitle(entry.probe, kept)

        suspend fun once(clampSubtitles: Boolean): TrimRun {
            val cmd = buildCommand(inParam, outParam, plan, kept, target, entry, clampSubtitles, defaultAudioIndex, defaultSubIndex)
            val session = runFfmpeg(cmd) { timeMs, speed ->
                val p = (timeMs / 1000.0 / durSec).toFloat().coerceIn(0f, 1f)
                publishRunning(idx, total, entry.name, p, String.format(Locale.US, "%.1fx", speed))
            }
            val rc = session?.returnCode
            if (rc == null || !rc.isValueSuccess) return TrimRun(session, null)
            return TrimRun(session, verifyOutputTimeline(outParam, plan, kept, entry, target))
        }

        val first = once(useBsf)
        if (first.issues != null && first.issues.isNotEmpty() && useBsf) {
            val second = once(false)
            if (second.issues != null && second.issues.isEmpty()) return second
            return second
        }
        return first
    }

    /**
     * 输出时间轴校验：ffprobe 只读容器头（大文件也秒回）。TS/PS 系起点阈值
     * 放宽到 1.6s（muxdelay 已清零，但 PCR 前导仍有小偏移；广播流普遍如此，
     * 播放器按相对时间轴播放无感）。
     *
     * 时间轴检查之外再做同步校验（[assessSync]：音视频 drift + 字幕绝对界），
     * 采样 = 源侧两次区间定点读 + 输出侧首包读，均毫秒级；源采样锚点用
     * [seekTargetSec]（与命令真实 -ss 一致）。纯音频且未保留字幕时无可
     * 校验项，跳过采样。
     */
    private suspend fun verifyOutputTimeline(
        path: String,
        plan: TrimPlan,
        kept: List<Int>,
        entry: VideoEntry,
        target: OutputTarget,
    ): List<String> {
        val probe = Probe.probeMediaPath(path)
        if (!probe.probeOk) return listOf("输出无法解析（${probe.error ?: "?"}）")
        val videoKept = entry.probe.streams.any { it.isVideo && it.index in kept }
        val audioKept = entry.probe.streams.any { it.isAudio && it.index in kept }
        val subKept = entry.probe.streams.any { it.isSubtitle && it.index in kept }
        val tsFamily = target.muxer == "mpegts" || target.muxer == "mpeg" || target.muxer == "asf"
        val issues = ArrayList<String>()
        issues += assessTimeline(
            start = probe.startTimeSec,
            dur = probe.durationSec,
            expectedDurSec = plan.duration,
            hasVideo = probe.streams.any { it.isVideo },
            hasAudio = probe.streams.any { it.isAudio },
            videoKept = videoKept,
            audioKept = audioKept,
            startToleranceSec = if (tsFamily) 1.6 else 0.1,
        )
        val srcPath = entry.filePath
        if (srcPath != null && (videoKept || subKept)) {
            val seekSec = seekTargetSec(plan, entry.probe)
            val samples = Probe.probeSyncSamples(
                srcPath = srcPath,
                outPath = path,
                seekSec = seekSec,
                outHasSubtitle = probe.streams.any { it.isSubtitle },
            )
            issues += assessSync(samples, seekSec, plan.duration)
        }
        return issues
    }

    private fun buildCommand(
        inParam: String,
        outParam: String,
        plan: TrimPlan,
        kept: List<Int>,
        target: OutputTarget,
        entry: VideoEntry,
        clampSubtitles: Boolean = true,
        defaultAudioIndex: Int? = null,
        defaultSubIndex: Int? = null,
    ): String = assembleCommand(inParam, outParam, plan, kept, target, entry.probe, clampSubtitles, defaultAudioIndex, defaultSubIndex)

    private fun extractError(session: FFmpegSession): String {
        // 优先取 SessionBridge 定格的完整日志（会话被挤出 ffmpeg-kit 历史时
        // session.allLogsAsString 只有残缺片段），回退到会话自带日志
        val logs = SessionBridge.takeDoneLogs(session.sessionId)
            ?: session.allLogsAsString
            ?: return "ffmpeg 失败（无日志）"
        val lines = logs.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("[info]", true) }
        val interesting = lines.filter {
            it.contains("error", true) || it.contains("invalid", true) ||
                    it.contains("could not", true) || it.contains("failed", true) ||
                    it.contains("only", true) || it.contains("not", true)
        }
        val picked = (interesting.ifEmpty { lines.takeLast(4) }).takeLast(4)
        val reason = picked.joinToString(" | ").take(400)
        return reason.ifEmpty { "ffmpeg 失败（返回码 ${session.returnCode?.value}）" }
    }

    /**
     * 挂起等待 ffmpeg 完成，返回 session；协程取消时会触发 FFmpegKit.cancel。
     * 日志与进度统计均经 SessionBridge 全局回调采集/路由：ffmpeg 运行期间其他
     * ffprobe 会话可能把它挤出 ffmpeg-kit 的会话历史（history=2），会话级回调
     * 会因此丢失（进度冻结、错误日志残缺），全局回调不受影响。
     * 执行全程持有 SessionBridge 全局执行锁（至会话完成回调）：并发执行多个
     * 会话时日志的会话归属不可靠——扫描 ffprobe 与本 ffmpeg 重叠时，本会话
     * stderr 行会串进 ffprobe 的 JSON 输出（"不可处理"误判），反之亦然。
     */
    private suspend fun runFfmpeg(
        cmd: String,
        onStat: (timeMs: Double, speed: Double) -> Unit,
    ): FFmpegSession? = SessionBridge.withExecuteLock {
        suspendCancellableCoroutine { cont ->
            SessionBridge.init()
            val session = FFmpegSession.create(
                FFmpegKitConfig.parseArguments(cmd),
                { s ->
                    SessionBridge.endLogs(s.sessionId)
                    SessionBridge.endStats(s.sessionId)
                    if (cont.isActive) cont.resume(s)
                },
                null, // 日志经 SessionBridge 采集
                null, // 进度经 SessionBridge 路由
            )
            SessionBridge.beginLogs(session.sessionId)
            SessionBridge.beginStats(session.sessionId) { timeMs, speed -> onStat(timeMs, speed) }
            FFmpegKitConfig.asyncFFmpegExecute(session)
            cont.invokeOnCancellation {
                try {
                    FFmpegKit.cancel(session.sessionId)
                } catch (_: Exception) {
                }
                SessionBridge.cleanup(session.sessionId)
            }
        }
    }
}
