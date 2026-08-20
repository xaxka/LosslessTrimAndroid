package com.xixka.losslesstrim.ffmpeg

import android.content.Context
import android.net.Uri
import com.antonkarpenko.ffmpegkit.AbstractSession
import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.FFmpegKitConfig
import com.antonkarpenko.ffmpegkit.FFprobeSession
import com.xixka.losslesstrim.data.ProbeResult
import com.xixka.losslesstrim.data.StreamInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.Reader
import java.io.StringReader
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ffprobe 封装：媒体信息（流列表）+ 关键帧位置探测。
 * 全部通过 ffmpeg-kit 的 saf: 协议直接读 SAF 文档。
 */
object Probe {

    /** 关键帧缓存上限（视频条目数）：防止长时间使用 / 换多个目录后无限增长 */
    private const val KEYFRAME_CACHE_MAX = 64

    /** 单次媒体信息探测超时：正常远小于 5s，超时（病态 SAF 慢读/损坏文件）按失败处理防挂死 */
    private const val MEDIA_PROBE_TIMEOUT_MS = 45_000L

    /** 关键帧全量 packet 扫描超时：长视频合法耗时数分钟，上限放宽到 10 分钟 */
    private const val KEYFRAME_PROBE_TIMEOUT_MS = 600_000L

    /**
     * 切点邻域探测窗口半径（秒）：远大于常见 GOP（2~10s）。邻域内凑不齐
     * 对齐所需的关键帧（超长 GOP）时退回全量扫描，语义不将就。
     */
    private const val KEYFRAME_WINDOW_SEC = 60.0

    /**
     * 看门狗：会话开始执行后若超时未完成，FFmpegKit.cancel(sessionId) 中止
     * 会话使其尽快返回。串行执行（SessionBridge 执行锁）下单个会话挂死会
     * 阻塞其后所有探测/剪辑，看门狗是扫描不至永久卡住的保底。
     */
    private val watchdog = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "probe-watchdog").apply { isDaemon = true }
    }

    /** 关键帧缓存（uri string → 升序关键帧时间列表），进程内复用，LRU 淘汰 */
    private val keyframeCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, List<Double>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Double>>?): Boolean =
                size > KEYFRAME_CACHE_MAX
        }
    )

    /** 单次 ffprobe 执行结果 */
    private class ExecOutcome(val ok: Boolean, val output: String, val timedOut: Boolean)

    /**
     * 同步执行 ffprobe 并取回**完整**输出（内存模式，适合小输出）。
     * 输出经 SessionBridge 的全局回调采集，不依赖 ffmpeg-kit 会话历史：
     * 修复并发扫描时正在运行的会话被挤出历史（history=2）导致输出被截断、
     * JSON 在半途突然结束（"End of input at character NNN"）的问题。
     * retain=false：探测输出立即消费，不驻留 doneLogs（防多份大输出积压）。
     * 执行全程持有 SessionBridge 全局执行锁：并发会话的日志会互相串扰。
     * 带超时看门狗：超时取消会话按失败返回，防止单文件挂死阻塞整个扫描。
     */
    private suspend fun runProbe(cmd: String): ExecOutcome {
        SessionBridge.init()
        val session = FFprobeSession.create(FFmpegKitConfig.parseArguments(cmd))
        return SessionBridge.withExecuteLock {
            runProbeLocked(session)
        }
    }

    /**
     * 同步执行 ffprobe，输出经 SessionBridge 磁盘溢写（cacheDir 临时文件），
     * 再流式逐行交给 [consume]——全程不把整份输出驻留内存。
     * 磁盘防护：packet 级 CSV 对长视频（数小时、多轨）可达几十 MB，旧实现
     * StringBuilder + toString 双份拷贝瞬时峰值可达几十 MB，是 OOM 诱因；
     * 溢写后内存峰值只剩解析结果本身。磁盘不可用时退回内存模式（降级不失效）。
     * 返回 false = 探测失败（含超时）或溢写损坏，调用方按失败处理。
     * 执行与结束溢写都在全局执行锁内：会话结束前不会有别的会话日志串进来。
     */
    private suspend fun runProbeToDisk(cmd: String, spillDir: File, consume: (Reader) -> Unit): Boolean {
        SessionBridge.init()
        val session = FFprobeSession.create(FFmpegKitConfig.parseArguments(cmd))
        var execOk = false
        var spill: SessionBridge.Spill? = null
        SessionBridge.withExecuteLock {
            // begin/execute/end 全程持锁；磁盘不可用（极端情况）时锁内退回内存模式
            val sp = SessionBridge.beginSpill(session.sessionId, spillDir)
            if (sp == null) {
                val outcome = runProbeLocked(session)
                if (outcome.ok) {
                    try {
                        consume(StringReader(outcome.output))
                        execOk = true
                    } catch (_: Exception) {
                        execOk = false
                    }
                }
                return@withExecuteLock
            }
            spill = sp
            try {
                executeWithWatchdog(session, KEYFRAME_PROBE_TIMEOUT_MS) {
                    FFmpegKitConfig.ffprobeExecute(session)
                }
                val rc = session.returnCode
                // 先结束溢写（flush + close）再读，避免漏掉缓冲区尾部
                SessionBridge.endSpill(session.sessionId)
                execOk = rc != null && rc.isValueSuccess && !sp.failed
            } catch (_: Exception) {
                execOk = false
            }
        }
        // 读取/解析在锁外进行（可能耗时较久，不阻塞其他会话执行）
        if (execOk && spill != null) {
            try {
                spill!!.file.bufferedReader().use { consume(it) }
            } catch (_: Exception) {
                execOk = false
            }
        }
        SessionBridge.endSpill(session.sessionId)   // 幂等：已结束则 no-op
        spill?.delete()
        return execOk
    }

    /** [runProbe] 的已持锁变体：调用方必须已持有 SessionBridge 执行锁 */
    private fun runProbeLocked(session: FFprobeSession): ExecOutcome {
        SessionBridge.beginLogs(session.sessionId)
        var output = ""
        var timedOut = false
        try {
            timedOut = executeWithWatchdog(session, MEDIA_PROBE_TIMEOUT_MS) {
                FFmpegKitConfig.ffprobeExecute(session)
            }
        } finally {
            output = SessionBridge.endLogs(session.sessionId, retain = false)
        }
        val rc = session.returnCode
        return ExecOutcome(rc != null && rc.isValueSuccess, output, timedOut)
    }

    /** 执行 [execute]，超时未完成则取消会话使其尽快返回；返回是否发生超时 */
    private fun executeWithWatchdog(
        session: AbstractSession,
        timeoutMs: Long,
        execute: () -> Unit,
    ): Boolean {
        val finished = AtomicBoolean(false)
        val timedOut = AtomicBoolean(false)
        val future = watchdog.schedule({
            if (!finished.get()) {
                timedOut.set(true)
                try {
                    FFmpegKit.cancel(session.sessionId)
                } catch (_: Exception) {
                }
            }
        }, timeoutMs, TimeUnit.MILLISECONDS)
        try {
            execute()
        } finally {
            finished.set(true)
            future.cancel(false)
        }
        return timedOut.get()
    }

    /**
     * 探测指定 uri 的媒体信息。
     *
     * SAF（saf:）数据通道已彻底移除：ffmpeg-kit fork 在其 SAF 参数构造/读取
     * 路径上存在越界崩溃（"探测异常: length=N; index=N"）与慢速描述符读，
     * 未授予"所有文件"权限时直接报错引导授权，不再静默降级。
     */
    suspend fun probeMedia(context: Context, uri: Uri): ProbeResult = withContext(Dispatchers.IO) {
        val file = com.xixka.losslesstrim.util.StorageAccess.accessibleFile(context, uri)
        if (file == null) {
            ProbeResult(
                probeOk = false,
                error = "无法定位文件路径（未授予\u201c所有文件\u201d权限或非本地存储），SAF 通道已移除"
            )
        } else {
            probeMediaPath(file.absolutePath)
        }
    }

    /** 按绝对路径探测（直文件 I/O，无 SAF 开销），参数与 [probeMedia] 一致 */
    suspend fun probeMediaPath(path: String): ProbeResult = withContext(Dispatchers.IO) {
        try {
            val outcome = runProbe(
                "-v error -show_streams -show_format -of json -i \"$path\""
            )
            when {
                outcome.timedOut -> ProbeResult(
                    probeOk = false,
                    error = "探测超时（读取过慢或文件损坏），可重试"
                )

                !outcome.ok || outcome.output.isBlank() -> ProbeResult(
                    probeOk = false,
                    error = tailOf(outcome.output.ifBlank { "ffprobe 无输出" }, 200)
                )

                else -> parseMediaJson(outcome.output)
            }
        } catch (e: Exception) {
            ProbeResult(probeOk = false, error = "探测异常: ${e.message}")
        }
    }

    suspend fun probeKeyframes(context: Context, uri: Uri): List<Double> = withContext(Dispatchers.IO) {
        keyframeCache[uri.toString()]?.let { return@withContext it }
        try {
            // SAF 通道已移除：关键帧 packet 级扫描只在直路径上进行（更快更稳）；
            // 定位失败（未授权/云盘）返回空列表，调用方按"无法对齐关键帧"处理
            val path = com.xixka.losslesstrim.util.StorageAccess
                .accessibleFile(context, uri)?.absolutePath
                ?: return@withContext emptyList()
            // CSV 而非 JSON：长视频全量 packet 输出可达几十 MB，CSV 体积约减半，
            // 且免去 JSONObject 整树解析的内存峰值（这是批处理时 OOM 的主要诱因之一）
            // 输出经磁盘溢写 + 流式解析：整份 CSV 不再驻留内存
            val spillDir = File(context.cacheDir, "probe-spill")
            val kfs = ArrayList<Double>()
            val ok = runProbeToDisk(
                "-v error -select_streams v:0 -show_entries packet=pts_time,flags -of csv=p=0 -i \"$path\"",
                spillDir,
            ) { reader ->
                reader.forEachLine { line -> parseKeyframeLine(line, kfs) }
            }
            if (ok) {
                // 仅成功结果写入缓存；失败不缓存，避免同一文件整个进程周期内都无法重试对齐
                kfs.sort()
                keyframeCache[uri.toString()] = kfs
                kfs
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 切点邻域关键帧探测：用 -read_intervals 只读各切点前后 [KEYFRAME_WINDOW_SEC]
     * 的 packet，代替整文件全量扫描（[probeKeyframes]）。GB 级 4K 长片全量扫描
     * 要顺序读完整文件（每片数十秒），批量队列"每处理完一个文件干等半天"的
     * 主因之一；定点读通常只触碰文件几 MB。
     *
     * 结果校验：每个有效切点的邻域内必须同时存在 ≤t 与 ≥t 的关键帧（切点贴着
     * 0 或片长时只查存在侧），凑不齐视为超长 GOP 或 seek 失败，退回全量扫描，
     * 保证 [com.xixka.losslesstrim.trim.TrimPlanner] 对齐结果与全量一致。
     */
    suspend fun probeKeyframesNear(
        context: Context,
        uri: Uri,
        points: List<Double>,
        durSec: Double,
    ): List<Double> = withContext(Dispatchers.IO) {
        val path = com.xixka.losslesstrim.util.StorageAccess
            .accessibleFile(context, uri)?.absolutePath ?: return@withContext emptyList()
        if (durSec <= 0 || points.isEmpty()) return@withContext emptyList()
        try {
            val clamped = points.map { it.coerceIn(0.0, durSec) }.distinct().sorted()
            // 窗口按起点排序后合并重叠/相邻区间：两切点邻近时一次区间读掉
            val raw = clamped.map {
                (it - KEYFRAME_WINDOW_SEC).coerceAtLeast(0.0) to
                        (it + KEYFRAME_WINDOW_SEC).coerceAtMost(durSec)
            }.sortedBy { it.first }
            val wins = ArrayList<Pair<Double, Double>>()
            for (w in raw) {
                val last = wins.lastOrNull()
                if (last != null && w.first <= last.second + 0.5) {
                    wins[wins.size - 1] = last.first to maxOf(last.second, w.second)
                } else wins.add(w)
            }
            val intervals = wins.joinToString(",") {
                String.format(java.util.Locale.US, "%.3f%%%.3f", it.first, it.second)
            }
            val spillDir = File(context.cacheDir, "probe-spill")
            val kfs = ArrayList<Double>()
            val ok = runProbeToDisk(
                "-v error -select_streams v:0 -show_entries packet=pts_time,flags " +
                        "-of csv=p=0 -read_intervals \"$intervals\" -i \"$path\"",
                spillDir,
            ) { reader ->
                reader.forEachLine { line -> parseKeyframeLine(line, kfs) }
            }
            // 执行失败（容器不支持区间 seek 等）→ 全量兜底
            if (!ok) return@withContext probeKeyframes(context, uri)
            val sorted = kfs.distinct().sorted()
            val valid = clamped.all { t ->
                when {
                    t <= 0.05 -> sorted.isNotEmpty()
                    t >= durSec - 0.05 -> sorted.any { it <= t }
                    else -> sorted.any { it <= t } && sorted.any { it >= t }
                }
            }
            if (!valid) return@withContext probeKeyframes(context, uri)
            sorted
        } catch (e: Exception) {
            probeKeyframes(context, uri)
        }
    }

    /**
     * 输出终检（轻量）：只解析容器级时长与流存在性——不逐流解参数。
     * -show_streams 对 hev1 标记（参数集内嵌码流）的 HEVC 会去读码流包才能
     * 组出流信息，GB 级输出上明显拖慢每个文件的收尾；此处只读头即可判定
     * "产物可解析、时长正常、有流"。任一步不确信（超时/解析不出/时长异常）
     * 即退回全量 [probeMediaPath]，判定语义与全量探测一致。
     */
    suspend fun verifyMedia(path: String): ProbeResult = withContext(Dispatchers.IO) {
        val outcome = runProbe(
            "-v error -show_entries format=duration:stream=index -of json -i \"$path\""
        )
        val fast = if (outcome.timedOut || !outcome.ok || outcome.output.isBlank()) {
            null
        } else {
            try {
                val root = JSONObject(outcome.output)
                val dur = root.optJSONObject("format")?.optString("duration")?.toDoubleOrNull()
                val streamCount = root.optJSONArray("streams")?.length() ?: 0
                if (dur != null && dur > 0 && streamCount > 0) {
                    ProbeResult(probeOk = true, durationSec = dur)
                } else null
            } catch (_: Exception) {
                null
            }
        }
        fast ?: probeMediaPath(path)
    }

    private fun parseMediaJson(json: String): ProbeResult {
        return try {
            val root = JSONObject(json)
            val format = root.optJSONObject("format")
            var duration = format?.optString("duration")?.toDoubleOrNull()
            val streamsArr = root.optJSONArray("streams") ?: return ProbeResult(
                probeOk = false, error = "无流信息"
            )
            val streams = ArrayList<StreamInfo>()
            for (i in 0 until streamsArr.length()) {
                val s = streamsArr.optJSONObject(i) ?: continue
                val tags = s.optJSONObject("tags")
                val disp = s.optJSONObject("disposition")
                streams.add(
                    StreamInfo(
                        index = s.optInt("index", -1),
                        codecType = s.optString("codec_type", ""),
                        codecName = s.optString("codec_name", "未知"),
                        language = tags?.optString("language")?.takeIf { it.isNotEmpty() },
                        title = tags?.optString("title")?.takeIf { it.isNotEmpty() },
                        channels = if (s.has("channels")) s.optInt("channels") else null,
                        channelLayout = s.optString("channel_layout").takeIf { it.isNotEmpty() },
                        width = if (s.has("width")) s.optInt("width") else null,
                        height = if (s.has("height")) s.optInt("height") else null,
                        attachedPic = (disp?.optInt("attached_pic", 0) ?: 0) == 1,
                    )
                )
                if (duration == null) {
                    duration = s.optString("duration").toDoubleOrNull()
                }
            }
            if (duration == null || duration <= 0 || streams.isEmpty()) {
                ProbeResult(probeOk = false, error = "时长或流解析失败")
            } else {
                ProbeResult(
                    probeOk = true,
                    durationSec = duration,
                    formatName = format?.optString("format_name") ?: "",
                    streams = streams.sortedBy { it.index },
                )
            }
        } catch (e: Exception) {
            ProbeResult(probeOk = false, error = "JSON 解析失败: ${e.message}")
        }
    }

    /**
     * 解析 `-show_entries packet=pts_time,flags -of csv=p=0` 的单行输出：
     * p=0 时形如 `0.000000,__K`（部分版本会带 `packet,` 前缀），
     * 末列 flags 含 K 即关键帧；在前面各列里取第一个可解析的时间。
     * 容错：跳过乱入的错误日志行与 pts_time 缺失的行。
     */
    private fun parseKeyframeLine(line: String, out: MutableList<Double>) {
        if (line.isEmpty()) return
        val parts = line.split(',')
        if (parts.size < 2) return
        val flags = parts.last().trim()
        if (!flags.contains('K')) return
        val t = parts.dropLast(1)
            .firstNotNullOfOrNull { it.trim().toDoubleOrNull() }
        if (t != null && t >= 0) out.add(t)
    }

    private fun tailOf(text: String, max: Int): String {
        val clean = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return if (clean.size <= 3) clean.joinToString("; ")
        else clean.takeLast(3).joinToString("; ").take(max)
    }
}
