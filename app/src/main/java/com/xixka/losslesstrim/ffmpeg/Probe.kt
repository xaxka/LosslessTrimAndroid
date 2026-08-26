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
 * 同步校验采样点（[Probe.probeSyncSamples] 的产物，判定见
 * com.xixka.losslesstrim.trim.TrimService.assessSync）。
 * null = 该项未采到（纯音频/纯视频源、容器不支持区间读、字幕稀疏、采样
 * 防呆拦截），判定侧跳过对应校验——采样失败 ≠ 输出坏。
 */
class SyncSamples(
    val srcVideoPts: Double? = null,
    val srcAudioPts: Double? = null,
    val outVideoPts: Double? = null,
    val outAudioPts: Double? = null,
    val outSubtitlePts: Double? = null,
)

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

    /**
     * 关键帧缓存上限（视频条目数）：防止长时间使用 / 换多个目录后无限增长。
     *
     * 旧值 64 对长视频过度富余：一部 2 小时电影的关键帧表可达 5k+ 条目 × 8 字节
     * ≈ 40KB，加邻域条目（同片 + 不同切点）总占用轻松上 MB；32 条上限足够覆盖
     * 用户在同一批文件间往返分析的场景，配合 onTrimMemory 释放更稳。
     */
    private const val KEYFRAME_CACHE_MAX = 32

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

    /**
     * L1 条目：结果 + 入缓时间（超 [ProbeStore.CACHE_TTL_MS] 过期，与 L2 同款时效）
     * + 文件身份（大小/修改时间）。键只有 uri（邻域条目再拼切点集合）——剪辑覆盖
     * 后 uri 不变但内容已换，条目必须过身份校验才能复用，否则切完的文件仍按
     * **旧布局**吐关键帧（分析页时间轴还是切之前的刻度、二次剪辑按已不存在的
     * 关键帧对齐）。与 L2（ProbeStore）同款双重陈旧防护。
     */
    private class CachedKfs(val kfs: List<Double>, val at: Long, val size: Long, val modified: Long)

    /** L1 条目可用：时效内 且 文件大小/修改时间与探测当时一致（文件没被换过） */
    private fun CachedKfs.validFor(now: Long, file: File): Boolean =
        now - at <= ProbeStore.CACHE_TTL_MS &&
                file.length() == size && file.lastModified() == modified

    /**
     * 关键帧缓存（uri → 全量扫描结果；`uri@near:切点键` → 邻域窗口结果），
     * L1 + L2 见类注释。邻域条目键带切点集合，只被同切点查询命中，不会
     * 冒充全量；两类条目共用此 LRU（上限按条目数计）。
     */
    private val keyframeCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, CachedKfs>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedKfs>?): Boolean =
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
        // 失败原因分两类，提示分开：未授权（可去设置解决）与来源不可解析
        // （云盘/第三方文件管理器，换"此设备"入口选本机文件）
        if (!com.xixka.losslesstrim.util.StorageAccess.hasAllFilesAccess(context)) {
            ProbeResult(
                probeOk = false,
                error = "未授予\u201c所有文件\u201d权限（SAF 通道已移除），请到系统设置授权后重试"
            )
        } else {
            val file = com.xixka.losslesstrim.util.StorageAccess.accessibleFile(context, uri)
            if (file == null) {
                ProbeResult(
                    probeOk = false,
                    error = "无法定位文件路径：该文件不在本机存储（云盘/第三方来源），请在选择器\u201c此设备\u201d中选择本机文件"
                )
            } else {
                // L2：Room 持久缓存（uri + 大小 + mtime 未变即视为同一文件）
                ProbeStore.loadProbe(context, uri.toString(), file)?.let { return@withContext it }
                val result = probeMediaPath(file.absolutePath)
                if (result.probeOk) ProbeStore.saveProbe(context, uri.toString(), file, result)
                result
            }
        }
    }

    /**
     * 按绝对路径探测（直文件 I/O，无 SAF 开销）。
     *
     * 带重试与平台 API 兜底（类注释防线 2/3）：解析类失败（日志通道截断等）
     * 换新会话重试至多 [PROBE_ATTEMPTS] 次；全部失败再退 MediaMetadataRetriever
     * 拿时长+旋转（对 moov atom not found / codec_tag 异常等不标准 MP4 更宽容
     * ——实测 moov 在尾部或 codec 私有字段异常时 ffprobe 严格失败，但
     * MediaMetadataRetriever 仍能拿到 duration），最后退 MediaExtractor 拿
     * stream 列表。MediaExtractor 在真正坏文件上也会失败，此时拿最后一次
     * ffprobe 的错误信息返回。超时（慢文件）走直接 MediaMetadataRetriever
     * 兜底——重跑 ffprobe 只是再等一轮。
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
        // 平台 API 兜底：先 MediaMetadataRetriever（时长更宽容，moov 异常的 MP4
        // 仍可拿到），再 MediaExtractor（stream 列表）。两条路径都失败才把
        // 错误回退到 ffprobe 的（用户看到的是 ffprobe 报错信息）
        platformProbe(path) ?: result ?: ProbeResult(probeOk = false, error = "探测失败")
    }

    /**
     * 平台 API 兜底探测：时长 + 旋转 + stream 列表（尽力）。
     *
     * 优先级：MediaMetadataRetriever（容错好）→ MediaExtractor（stream 列表）。
     *
     * 对应"moov atom not found"等不标准 MP4：ffprobe 严格解析失败（容器级错误），
     * 但 MediaMetadataRetriever 在 moov 仍存在时能拿到 METADATA_KEY_DURATION
     * 与 VIDEO_ROTATION——这两项对结果页展示与头尾裁剪已够用；进入批量剪辑时
     * probeKeyframesNear 才需要 stream 级别信息（该路径另走 ffprobe 严格探测，
     * 真坏文件按真坏文件处理，不会假装能切）。
     *
     * streams 为空 + probeOk=true 是合法状态：列表页能识别时长+大小，AnalysisScreen
     * 仍可打开（看不到轨道——但能看到时长/文件名），批量剪辑由 TrimService 1b
     * 段判 FAILED 并给出"容器解析受限（<ffprobe 错误>），可尝试桌面 ffmpeg 重新
     * 封装后再处理"——比直接标"不可处理"更诚实（用户至少知道文件可识别）。
     */
    private fun platformProbe(path: String): ProbeResult? {
        // 第 1 步：MediaMetadataRetriever——对 moov 异常的 MP4 也能拿 duration/rotation
        val mmr = MediaMetadataRetriever()
        val mmrResult = try {
            mmr.setDataSource(path)
            val durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toDoubleOrNull()
            if (durMs != null && durMs > 0) {
                val rotation = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull() ?: 0
                Pair(durMs / 1000.0, rotation)
            } else null
        } catch (_: Exception) {
            null
        } finally {
            try {
                mmr.release()
            } catch (_: Exception) {
            }
        }

        // 第 2 步：MediaExtractor——拿 stream 列表（codec/宽高/声道/字幕标记）
        val streams = mediaExtractorStreams(path)

        return when {
            mmrResult != null && streams != null -> {
                // 时长 + stream 都在：完整结果（streams 非空，TrimService 不会误判）
                val (durSec, rotation) = mmrResult
                ProbeResult(
                    probeOk = true,
                    durationSec = durSec,
                    streams = streams.mapIndexed { i, f ->
                        platformTrack(i, f, rotation)
                    },
                )
            }
            mmrResult != null -> {
                // 时长在但 stream 拿不到：标记"partial"——probeOk=true（让列表
                // 能识别时长+大小），但 streams=空会让 TrimService 在批量
                // 处理时直接判"未保留任何轨道"失败；AnalysisScreen 仍可打开
                // 看时长与文件名（无法选轨，UI 自然隐藏轨道区）
                val (durSec, _) = mmrResult
                ProbeResult(probeOk = true, durationSec = durSec, streams = emptyList())
            }
            streams != null -> {
                // stream 拿到了但时长没有：少见，仍算成功
                val durSec = streams.maxOfOrNull { f ->
                    if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else 0L
                }?.let { it / 1_000_000.0 } ?: 0.0
                if (durSec > 0) {
                    ProbeResult(
                        probeOk = true,
                        durationSec = durSec,
                        streams = streams.mapIndexed { i, f -> platformTrack(i, f, 0) },
                    )
                } else null
            }
            else -> null
        }
    }

    /**
     * MediaExtractor 拿 stream 列表：任何异常返回 null。
     * 与旧实现的差别：旧版用 extractor.trackCount==0 即返回 null，对坏 MP4
     * 容易误判；新版用 KEY_MIME 识别 stream type，empty mime 也接受（让
     * 兜底兜到底）。
     */
    private fun mediaExtractorStreams(path: String): List<android.media.MediaFormat>? {
        val extractor = android.media.MediaExtractor()
        return try {
            extractor.setDataSource(path)
            if (extractor.trackCount <= 0) null
            else (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
        } catch (_: Exception) {
            null
        } finally {
            try {
                extractor.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun platformTrack(index: Int, f: MediaFormat, rotation: Int = 0): StreamInfo {
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
            // MediaFormat 暴露 sampleRate（KEY_SAMPLE_RATE），与 ffprobe 对齐填充
            sampleRate = intOrNull(MediaFormat.KEY_SAMPLE_RATE),
            // 平台 API 不直接暴露比特率（MediaMetadataRetriever 有但需开 MMR 实例，
            // 此处保持 null，列表展示兜底为 "—"，详尽信息走 ffprobe 严格路径）
            bitRate = null,
            width = intOrNull(MediaFormat.KEY_WIDTH),
            height = intOrNull(MediaFormat.KEY_HEIGHT),
            attachedPic = false,
            // 兜底路径拿不到 has_b_frames：ffprobe 严格路径才是唯一可信源；
            // 标 null 让 seekFudgeSec 按"含 B 帧"保守处理（已有注释说明）
            hasBFrames = null,
            // platform 兜底用 MediaMetadataRetriever 的 VIDEO_ROTATION；mp4 的
            // tkhd matrix 与此一致，side_data Display Matrix 在 platform API 上
            // 不暴露
            rotation = rotation.takeIf { (it % 360 + 360) % 360 != 0 },
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

    /** 关键帧全量扫描（L1/L2 缓存优先，均按 24 小时时效 + 文件身份判定；仅直路径，SAF 通道已移除） */
    suspend fun probeKeyframes(context: Context, uri: Uri): List<Double> = withContext(Dispatchers.IO) {
        val key = uri.toString()
        val file = com.xixka.losslesstrim.util.StorageAccess
            .accessibleFile(context, uri) ?: return@withContext emptyList()
        val now = System.currentTimeMillis()
        keyframeCache[key]?.takeIf { it.validFor(now, file) }
            ?.let { return@withContext it.kfs }
        // L2：Room 持久缓存（全量扫描成本高，跨重启复用收益最大）
        ProbeStore.loadKeyframes(context, key, file)?.let {
            keyframeCache[key] = CachedKfs(it, now, file.length(), file.lastModified())
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
                keyframeCache[key] = CachedKfs(kfs, now, file.length(), file.lastModified())
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
     * 清空进程内 L1 关键帧缓存（全量 + 邻域条目）。配合 [com.xixka.losslesstrim.data.ProbeStore.clearAll]
     * （清 L2 Room）+ [com.xixka.losslesstrim.util.ThumbStore.clearAll]（清缩略图）一起被设置页
     * "清除缓存"调用。Scanner 的 probeCache（L1 Probe 内存）也跟着清——同进程下复用
     * 的内存态探测结果会因文件身份变化/版本升级失效，下次扫描重新探测。
     */
    fun clearKeyframeCache() {
        keyframeCache.clear()
    }

    /**
     * 系统内存压力回调（MainApplication.onTrimMemory 路由过来）。
     *
     * MODERATE 及以上：清空 keyframeCache。代价是回到分析页可能重抽一次关键帧，
     * 但 L2 Room 还在（持久库），只是 L1 重新填一遍——比持续占住 RAM 把后台
     * 进程顶出 LMK 便宜得多。
     */
    fun onTrimMemory(level: Int) {
        when {
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE -> {
                keyframeCache.clear()
            }
        }
    }

    /**
     * 切点邻域关键帧探测：用 -read_intervals 只读各切点前后 [KEYFRAME_WINDOW_SEC]
     * 的 packet，代替整文件全量扫描（[probeKeyframes]）。GB 级 4K 长片全量扫描
     * 要顺序读完整文件（每片数十秒），批量队列"每处理完一个文件干等半天"的
     * 主因之一；定点读通常只触碰文件几 MB。
     *
     * 缓存优先（三层，命中即零探测）：
     * 1. 全量关键帧已缓存（L1/L2，如进过分析页）→ 直接返回全量列表——
     *    信息是全量的超集，对齐精度只增不减，何必再定点读；
     * 2. 同文件同切点的邻域结果已缓存（L1/L2，如刚跑过这批任务后重试）→
     *    直接复用；键含切点集合，换切点自然 miss；
     * 3. 未命中才真正探测，结果按 (uri + 切点集合) 入 L1/L2。
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
        val key = uri.toString()
        val file = com.xixka.losslesstrim.util.StorageAccess
            .accessibleFile(context, uri) ?: return@withContext emptyList()
        if (durSec <= 0 || points.isEmpty()) return@withContext emptyList()
        try {
            val clamped = points.map { it.coerceIn(0.0, durSec) }.distinct().sorted()
            val now = System.currentTimeMillis()

            // 缓存层 1：全量关键帧在手 → 超集直接用（时效 + 文件身份双重校验：
            // 覆盖剪辑后 uri 不变但文件已换，旧布局不能冒用）
            keyframeCache[key]?.takeIf { it.validFor(now, file) }?.let { return@withContext it.kfs }
            ProbeStore.loadKeyframes(context, key, file)?.let {
                keyframeCache[key] = CachedKfs(it, now, file.length(), file.lastModified())
                return@withContext it
            }

            // 缓存层 2：同文件同切点的邻域结果
            val pointsKey = clamped.joinToString(",") {
                String.format(java.util.Locale.US, "%.3f", it)
            }
            val nearKey = "$key@near:$pointsKey"
            keyframeCache[nearKey]?.takeIf { it.validFor(now, file) }?.let { return@withContext it.kfs }
            ProbeStore.loadNearKeyframes(context, key, pointsKey, file)?.let {
                keyframeCache[nearKey] = CachedKfs(it, now, file.length(), file.lastModified())
                return@withContext it
            }

            val path = file.absolutePath
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
            // 仅"窗口凑齐对齐所需关键帧"的结果可缓存：验证过的窗口对同切点可安全复用
            keyframeCache[nearKey] = CachedKfs(sorted, now, file.length(), file.lastModified())
            ProbeStore.saveNearKeyframes(context, key, pointsKey, file, sorted)
            sorted
        } catch (e: Exception) {
            probeKeyframes(context, uri)
        }
    }

    /**
     * 采样指定流的首包 pts_time（秒）——同步校验的采样原语。
     *
     * fromSec 非空时用 -read_intervals 做**区间定点读**（"fromSec%+#N"）：
     * 只 demux seek 落点之后的 N 个包。落点语义（实测）：≤fromSec 的最近
     * 索引点（视频流=关键帧）——与 ffmpeg -ss(noaccurate) 的落点同构，
     * 这正是音画 drift 对比需要的锚点。matroska/mp4 有索引毫秒级返回，
     * GB 级 4K 源也只触碰几 MB（对照 [probeKeyframesNear] 的定点读思路）。
     * fromSec=null 从文件头读。
     *
     * 字幕流的 seek 不生效（实测 matroska：带 fromSec 与从头读结果一致，
     * read_intervals 对字幕流仍从文件头读），字幕只应从头采样。
     *
     * 解析容错：输出可能混入错误日志行（SessionBridge 混采），取第一个
     * 可解析为 double 的行；失败/空输出返回 null——采样失败 ≠ 输出坏，
     * 判定侧（TrimService.assessSync）按"该项未采到"跳过对应校验，
     * 不因采样抖动误杀好成片。
     */
    suspend fun probeFirstPacketPts(path: String, streamSpec: String, fromSec: Double? = null): Double? =
        withContext(Dispatchers.IO) {
            val interval = if (fromSec != null) {
                String.format(java.util.Locale.US, "%.3f%%+#8", fromSec)
            } else "%+#8"
            val outcome = runProbe(
                "-v error -read_intervals \"$interval\" -select_streams $streamSpec " +
                        "-show_entries packet=pts_time -of csv=p=0 -i \"$path\""
            )
            if (!outcome.ok || outcome.output.isBlank()) null
            else outcome.output.lineSequence()
                .map { it.trim() }
                .firstNotNullOfOrNull { it.toDoubleOrNull() }
        }

    /**
     * 同步校验采样（两步采样协议，数值矩阵见 scripts/verify-timeline.sh T12）：
     *
     * - 源侧：视频流在 seekSec 锚点（=命令真实 -ss 值）采样首包 sv——落点即
     *   切点关键帧；**再以 sv 为锚**采样音频首包 sa——两者构成"切点时刻"
     *   的音画 pts 对。片头剪（seekSec≈0，命令不传 -ss）两侧都从头采样：
     *   剪辑从文件头顺序保留，首包对就是切点对（音频以 sv 为锚反而采到
     *   错误时刻，drift 会假报 ~音画偏移量）。
     * - 输出侧：视频/音频/字幕各从头采样首包（ov/oa/os）。
     * - 判定（drift 模型 + 字幕绝对界）在 TrimService.assessSync，纯函数可单测。
     *
     * outHasSubtitle=false 时不探测输出字幕：字幕稀疏，`-select_streams s`
     * 从头读要 demux 到凑满 N 包才返回，无字幕输出会**读完整个文件**才
     * 得到空结果（GB 级全保留文件被白读一遍）。
     */
    suspend fun probeSyncSamples(
        srcPath: String,
        outPath: String,
        seekSec: Double,
        outHasSubtitle: Boolean,
    ): SyncSamples {
        val seek = seekSec.takeIf { it > 0.001 }
        val srcVideo = probeFirstPacketPts(srcPath, "v:0", seek)
        return SyncSamples(
            srcVideoPts = srcVideo,
            // 中段剪：音频锚定视频关键帧时刻；片头剪：从头（见函数注释）
            srcAudioPts = if (seek != null) {
                probeFirstPacketPts(srcPath, "a:0", srcVideo)
            } else {
                probeFirstPacketPts(srcPath, "a:0")
            },
            outVideoPts = probeFirstPacketPts(outPath, "v:0"),
            outAudioPts = probeFirstPacketPts(outPath, "a:0"),
            outSubtitlePts = if (outHasSubtitle) probeFirstPacketPts(outPath, "s:0") else null,
        )
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
                        sampleRate = if (s.has("sample_rate")) s.optInt("sample_rate") else null,
                        // bit_rate 字符串形态：部分容器/流型缺失或返回 "N/A"，统一兜底为 null
                        bitRate = s.optString("bit_rate").takeIf { it.isNotEmpty() }?.toLongOrNull(),
                        width = if (s.has("width")) s.optInt("width") else null,
                        height = if (s.has("height")) s.optInt("height") else null,
                        attachedPic = (disp?.optInt("attached_pic", 0) ?: 0) == 1,
                        // disposition.default（源文件标记的默认轨）；disp 对象缺失 → null（未知），
                        // 音轨兜底逻辑按"源无默认"处理（首保留轨标 default）
                        dispositionDefault = disp?.optInt("default")?.let { it == 1 },
                        hasBFrames = if (s.has("has_b_frames")) s.optInt("has_b_frames") else null,
                        rotation = parseRotation(s.optJSONArray("side_data_list")),
                        codecTag = parseCodecTag(s.optString("codec_tag_string")),
                        pixFmt = s.optString("pix_fmt").takeIf { it.isNotEmpty() },
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
                    startTimeSec = format?.optString("start_time")?.toDoubleOrNull(),
                    streams = streams.sortedBy { it.index },
                )
            }
        } catch (e: Exception) {
            ProbeResult(probeOk = false, error = "JSON 解析失败: ${e.message}")
        }
    }

    /**
     * 解析 side_data_list 里的显示旋转角度（side_data_type == "Display Matrix"
     * 的 rotation 字段，度）。mp4 存 tkhd display matrix；mkv 由 ffmpeg≥6.1 写
     * Projection 元素，两侧 ffprobe 都以此 side_data 形式上报。
     */
    private fun parseRotation(sideDataList: org.json.JSONArray?): Int? {
        if (sideDataList == null) return null
        for (i in 0 until sideDataList.length()) {
            val sd = sideDataList.optJSONObject(i) ?: continue
            if (sd.optString("side_data_type") == "Display Matrix" && sd.has("rotation")) {
                return sd.optInt("rotation")
            }
        }
        return null
    }

    /** codec_tag_string 清掉 \0 填充与 ffprobe 的占位形态（"[0][0][0][0]"） */
    private fun parseCodecTag(raw: String): String? =
        raw.trim { it == '\u0000' || it == ' ' }
            .takeIf { it.isNotEmpty() && !it.startsWith("[") }

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
