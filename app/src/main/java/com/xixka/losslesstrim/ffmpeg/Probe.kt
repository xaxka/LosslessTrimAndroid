package com.xixka.losslesstrim.ffmpeg

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.antonkarpenko.ffmpegkit.AbstractSession
import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.FFmpegKitConfig
import com.antonkarpenko.ffmpegkit.FFprobeSession
import com.xixka.losslesstrim.data.ProbeResult
import com.xixka.losslesstrim.data.ProbeStore
import com.xixka.losslesstrim.data.StreamInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
 *
 * 稳定性设计（针对 ffmpeg-kit fork 日志通道的三层防线）：
 * 1. 数据输出不走内存驻留假设——执行返回后先"排水"等日志投递静默再收割
 *    （fork 的日志投递与会话执行返回不完全同步，实测 rc=success 但 JSON
 *    尾部整段丢失，"End of input at character 4700"）；
 * 2. 解析类失败自动重试（新会话再跑），超时不重试（慢文件重跑只会再等一轮）；
 * 3. 兜底换 Android 平台 API（MediaExtractor/MediaMetadataRetriever）——
 *    完全不经过 ffmpeg-kit 日志通道，从根上免疫此类截断。
 *
 * 探测结果两级缓存：进程内存 LRU（L1）+ Room 持久库（L2，跨重启有效），
 * 详见 [ProbeStore]。
 */
object Probe {

    /** 关键帧缓存上限（视频条目数）：防止长时间使用 / 换多个目录后无限增长 */
    private const val KEYFRAME_CACHE_MAX = 64

    /** 单次媒体信息探测超时：正常远小于 5s，超时（病态慢读/损坏文件）按失败处理防挂死 */
    private const val MEDIA_PROBE_TIMEOUT_MS = 45_000L

    /** 关键帧全量 packet 扫描超时：长视频合法耗时数分钟，上限放宽到 10 分钟 */
    private const val KEYFRAME_PROBE_TIMEOUT_MS = 600_000L

    /**
     * 切点邻域探测窗口半径（秒）：远大于常见 GOP（2~10s）。邻域内凑不齐
     * 对齐所需的关键帧（超长 GOP）时退回全量扫描，语义不将就。
     */
    private const val KEYFRAME_WINDOW_SEC = 60.0

    /** 日志排水：执行返回后轮询缓冲增长的间隔（静默两个采样窗口即认为投递完毕） */
    private const val LOG_DRAIN_POLL_MS = 40L

    /** 日志排水：总上限，超出按当前缓冲收割（正常路径一次轮询即静默） */
    private const val LOG_DRAIN_MAX_MS = 400L

    /** 磁盘溢写路径排水：写缓冲无法采样，固定等一小段 */
    private const val SPILL_DRAIN_MS = 200L

    /** 媒体信息探测重试次数（仅解析类失败重试，超时/非零 rc 不重试） */
    private const val PROBE_ATTEMPTS = 3

    /**
     * 看门狗：会话开始执行后若超时未完成，FFmpegKit.cancel(sessionId) 中止
     * 会话使其尽快返回。串行执行（SessionBridge 执行锁）下单个会话挂死会
     * 阻塞其后所有探测/剪辑，看门狗是扫描不至永久卡住的保底。
     */
    private val watchdog = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "probe-watchdog").apply { isDaemon = true }
    }

    /** 关键帧缓存（uri string → 升序关键帧时间列表，仅全量扫描），L1 + L2 见类注释 */
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
     * 收割前先排水（见类注释防线 1）。
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
     * 结束溢写前同样排水，防 CSV 尾部在途丢失。
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
                val timedOut = executeWithWatchdog(session, KEYFRAME_PROBE_TIMEOUT_MS) {
                    FFmpegKitConfig.ffprobeExecute(session)
                }
                val rc = session.returnCode
                // 排水：溢写缓冲不可采样，固定等一小段让在途日志落盘，
                // 再 flush+close，避免漏掉缓冲区尾部
                if (!timedOut && rc != null && rc.isValueSuccess) delay(SPILL_DRAIN_MS)
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
    private suspend fun runProbeLocked(session: FFprobeSession): ExecOutcome {
        SessionBridge.beginLogs(session.sessionId)
        var output = ""
        var timedOut = false
        try {
            timedOut = executeWithWatchdog(session, MEDIA_PROBE_TIMEOUT_MS) {
                FFmpegKitConfig.ffprobeExecute(session)
            }
            // 排水：正常投递同步时一次轮询即静默（≈40ms）；fork 偶发异步尾部
            // 时最多再等 LOG_DRAIN_MAX_MS，显著小于重跑整次探测的代价
            if (!timedOut) awaitLogQuiescence(session.sessionId)
        } finally {
            output = SessionBridge.endLogs(session.sessionId, retain = false)
        }
        val rc = session.returnCode
        return ExecOutcome(rc != null && rc.isValueSuccess, output, timedOut)
    }

    /**
     * 等待该会话日志投递静默：缓冲长度连续两个采样窗口不再增长即认为
     * 在途日志已全部落地。会话不存在（已被收割）立即返回。
     */
    private suspend fun awaitLogQuiescence(sessionId: Long) {
        var last = SessionBridge.logLength(sessionId)
        if (last < 0) return
        var waited = 0L
        while (waited < LOG_DRAIN_MAX_MS) {
            delay(LOG_DRAIN_POLL_MS)
            waited += LOG_DRAIN_POLL_MS
            val now = SessionBridge.logLength(sessionId)
            if (now < 0) return
            if (now == last) return
            last = now
        }
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
     * 探测指定 uri 的媒体信息（L1/L2 缓存优先）。
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
            // L2：Room 持久缓存（uri + 大小 + mtime 未变即视为同一文件）
            ProbeStore.loadProbe(context, uri.toString(), file)?.let { return@withContext it }
            val result = probeMediaPath(file.absolutePath)
            if (result.probeOk) ProbeStore.saveProbe(context, uri.toString(), file, result)
            result
        }
    }

    /**
     * 按绝对路径探测（直文件 I/O，无 SAF 开销）。
     *
     * 带重试与平台 API 兜底（类注释防线 2/3）：解析类失败（日志通道截断等）
     * 换新会话重试至多 [PROBE_ATTEMPTS] 次；全部失败再退 MediaExtractor
     * 兜底（时长 + 轨道粗粒度信息，title/channelLayout 等富字段缺失）。
     * 超时不重试——慢文件重跑只是再等一轮，直接走兜底。
     */
    suspend fun probeMediaPath(path: String): ProbeResult = withContext(Dispatchers.IO) {
        var result: ProbeResult? = null
        for (attempt in 1..PROBE_ATTEMPTS) {
            val outcome = runProbe(
                "-v error -show_streams -show_format -of json -i \"$path\""
            )
            result = when {
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
            if (result.probeOk) return@withContext result
            if (outcome.timedOut) break
        }
        // 平台 API 兜底：不经 ffmpeg-kit 日志通道，从根上免疫截断
        platformProbe(path) ?: result ?: ProbeResult(probeOk = false, error = "探测失败")
    }

    /** MediaExtractor 兜底探测：时长 + 轨道粗信息；任何异常返回 null（继续用 ffprobe 的错误信息） */
    private fun platformProbe(path: String): ProbeResult? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(path)
            if (extractor.trackCount <= 0) {
                null
            } else {
                // MediaExtractor 不暴露容器级时长：取各轨 KEY_DURATION 的最大值（µs）
                val formats = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                val durUs = formats.maxOfOrNull { f ->
                    if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else 0L
                } ?: 0L
                val durSec = durUs / 1_000_000.0
                if (durSec <= 0) {
                    null
                } else {
                    // 轨道顺序与容器轨序一致（MP4/MKV 常规情况同 ffprobe 的全局索引）；
                    // 仅兜底场景使用，title/channelLayout/封面标记等富字段缺失
                    val streams = formats.mapIndexed { i, f -> platformTrack(i, f) }
                    ProbeResult(probeOk = true, durationSec = durSec, streams = streams)
                }
            }
        } catch (_: Exception) {
            null
        } finally {
            try {
                extractor.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun platformTrack(index: Int, f: MediaFormat): StreamInfo {
        val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
        val type = when {
            mime.startsWith("video/") -> "video"
            mime.startsWith("audio/") -> "audio"
            mime.startsWith("text/") ||
                    mime == "application/x-subrip" ||
                    mime == "application/x-ssa" -> "subtitle"

            else -> "data"
        }
        fun intOrNull(key: String): Int? =
            if (f.containsKey(key)) f.getInteger(key) else null

        return StreamInfo(
            index = index,
            codecType = type,
            codecName = codecFromMime(mime),
            language = f.getString(MediaFormat.KEY_LANGUAGE)?.takeIf { it.isNotEmpty() && it != "und" },
            title = null,
            channels = intOrNull(MediaFormat.KEY_CHANNEL_COUNT),
            channelLayout = null,
            width = intOrNull(MediaFormat.KEY_WIDTH),
            height = intOrNull(MediaFormat.KEY_HEIGHT),
            attachedPic = false,
        )
    }

    /** 平台 mime → ffprobe 风格编码名（展示用；未识别取 mime 后缀段） */
    private fun codecFromMime(mime: String): String = when (mime) {
        "video/hevc" -> "hevc"
        "video/avc" -> "h264"
        "video/x-vnd.on2.vp8" -> "vp8"
        "video/x-vnd.on2.vp9" -> "vp9"
        "video/av01" -> "av1"
        "video/mp4v-es" -> "mpeg4"
        "video/3gpp" -> "h263"
        "audio/mp4a-latm" -> "aac"
        "audio/aac" -> "aac"
        "audio/mpeg" -> "mp3"
        "audio/ac3" -> "ac3"
        "audio/eac3" -> "eac3"
        "audio/opus" -> "opus"
        "audio/vorbis" -> "vorbis"
        "audio/flac" -> "flac"
        "audio/x-flac" -> "flac"
        "audio/true-hd" -> "truehd"
        "audio/x-dts" -> "dts"
        "application/x-subrip" -> "subrip"
        "text/vtt" -> "webvtt"
        else -> mime.substringAfter('/')
    }

    /** 关键帧全量扫描（L1/L2 缓存优先；仅直路径，SAF 通道已移除） */
    suspend fun probeKeyframes(context: Context, uri: Uri): List<Double> = withContext(Dispatchers.IO) {
        val key = uri.toString()
        keyframeCache[key]?.let { return@withContext it }
        val file = com.xixka.losslesstrim.util.StorageAccess
            .accessibleFile(context, uri) ?: return@withContext emptyList()
        // L2：Room 持久缓存（全量扫描成本高，跨重启复用收益最大）
        ProbeStore.loadKeyframes(context, key, file)?.let {
            keyframeCache[key] = it
            return@withContext it
        }
        try {
            val path = file.absolutePath
            // CSV 而非 JSON：长视频全量 packet 输出可达几十 MB，CSV 体积约减半，
            // 且免去 JSONObject 整树解析的内存峰值；输出经磁盘溢写流式解析
            val spillDir = File(context.cacheDir, "probe-spill")
            val kfs = ArrayList<Double>()
            val ok = runProbeToDisk(
                "-v error -select_streams v:0 -show_entries packet=pts_time,flags -of csv=p=0 -i \"$path\"",
                spillDir,
            ) { reader ->
                reader.forEachLine { line -> parseKeyframeLine(line, kfs) }
            }
            if (ok) {
                // 仅成功结果写缓存；失败不缓存，避免同一文件整个进程周期内都无法重试对齐
                kfs.sort()
                keyframeCache[key] = kfs
                ProbeStore.saveKeyframes(context, key, file, kfs)
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
     * 注意：窗口结果**不写任何缓存**（只覆盖切点附近，冒充全量会污染后续对齐）。
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
     * 输出终检（轻量）：优先 Android 平台 MediaMetadataRetriever——直接读
     * 容器头，完全不经过 ffmpeg-kit 日志通道（截断免疫），faststart 产物
     * 毫秒级返回。平台 API 打不开/读不到时长时退回 [probeMediaPath]
     * （自带重试 + MediaExtractor 兜底），判定语义一致：可解析 + 时长 > 0。
     */
    suspend fun verifyMedia(path: String): ProbeResult = withContext(Dispatchers.IO) {
        val fast = try {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(path)
                val durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toDoubleOrNull()
                if (durMs != null && durMs > 0) {
                    ProbeResult(probeOk = true, durationSec = durMs / 1000.0)
                } else null
            } finally {
                try {
                    mmr.release()
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
            null
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
