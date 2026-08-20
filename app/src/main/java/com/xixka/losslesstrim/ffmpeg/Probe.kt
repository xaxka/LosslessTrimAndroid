package com.xixka.losslesstrim.ffmpeg

import android.content.Context
import android.net.Uri
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

/**
 * ffprobe 封装：媒体信息（流列表）+ 关键帧位置探测。
 * 全部通过 ffmpeg-kit 的 saf: 协议直接读 SAF 文档。
 */
object Probe {

    /** 关键帧缓存上限（视频条数）：防止长时间使用 / 换多个目录后无限增长 */
    private const val KEYFRAME_CACHE_MAX = 64

    /** 关键帧缓存（uri string → 升序关键帧时间列表），进程内复用，LRU 淘汰 */
    private val keyframeCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, List<Double>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Double>>?): Boolean =
                size > KEYFRAME_CACHE_MAX
        }
    )

    /**
     * 同步执行 ffprobe 并取回**完整**输出（内存模式，适合小输出）。
     * 输出经 SessionBridge 的全局回调采集，不依赖 ffmpeg-kit 会话历史：
     * 修复并发扫描时正在运行的会话被挤出历史（history=2）导致输出被截断、
     * JSON 在半途突然结束（"End of input at character NNN"）的问题。
     * retain=false：探测输出立即消费，不驻留 doneLogs（防多份大输出积压）。
     * 执行全程持有 SessionBridge 全局执行锁：并发会话的日志会互相串扰。
     */
    private suspend fun runProbe(cmd: String): Pair<Boolean, String> {
        SessionBridge.init()
        val session = FFprobeSession.create(FFmpegKitConfig.parseArguments(cmd))
        SessionBridge.withExecuteLock {
            runProbeLocked(session)
        }
    }

    /**
     * 同步执行 ffprobe，输出经 SessionBridge 磁盘溢写（cacheDir 临时文件），
     * 再流式逐行交给 [consume]——全程不把整份输出驻留内存。
     * 磁盘防护：packet 级 CSV 对长视频（数小时、多轨）可达几十 MB，旧实现
     * StringBuilder + toString 双份拷贝瞬时峰值可达几十 MB，是 OOM 诱因；
     * 溢写后内存峰值只剩解析结果本身。磁盘不可用时退回内存模式（降级不失效）。
     * 返回 false = 探测失败或溢写损坏，调用方按失败处理。
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
                val (ok, output) = runProbeLocked(session)
                if (ok) {
                    try {
                        consume(StringReader(output))
                        execOk = true
                    } catch (_: Exception) {
                        execOk = false
                    }
                }
                return@withExecuteLock
            }
            spill = sp
            try {
                FFmpegKitConfig.ffprobeExecute(session)
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
    private fun runProbeLocked(session: FFprobeSession): Pair<Boolean, String> {
        SessionBridge.beginLogs(session.sessionId)
        var output = ""
        try {
            FFmpegKitConfig.ffprobeExecute(session)
        } finally {
            output = SessionBridge.endLogs(session.sessionId, retain = false)
        }
        val rc = session.returnCode
        return (rc != null && rc.isValueSuccess) to output
    }

    suspend fun probeMedia(context: Context, uri: Uri): ProbeResult = withContext(Dispatchers.IO) {
        try {
            val input = FFmpegKitConfig.getSafParameterForRead(context, uri)
            val (ok, output) = runProbe(
                "-v error -show_streams -show_format -of json -i \"$input\""
            )
            if (!ok || output.isBlank()) {
                return@withContext ProbeResult(
                    probeOk = false,
                    error = tailOf(output.ifBlank { "ffprobe 无输出" }, 200)
                )
            }
            parseMediaJson(output)
        } catch (e: Exception) {
            ProbeResult(probeOk = false, error = "探测异常: ${e.message}")
        }
    }

    suspend fun probeKeyframes(context: Context, uri: Uri): List<Double> = withContext(Dispatchers.IO) {
        keyframeCache[uri.toString()]?.let { return@withContext it }
        try {
            val input = FFmpegKitConfig.getSafParameterForRead(context, uri)
            // CSV 而非 JSON：长视频全量 packet 输出可达几十 MB，CSV 体积约减半，
            // 且免去 JSONObject 整树解析的内存峰值（这是批处理时 OOM 的主要诱因之一）
            // 输出经磁盘溢写 + 流式解析：整份 CSV 不再驻留内存
            val spillDir = File(context.cacheDir, "probe-spill")
            val kfs = ArrayList<Double>()
            val ok = runProbeToDisk(
                "-v error -select_streams v:0 -show_entries packet=pts_time,flags -of csv=p=0 -i \"$input\"",
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
