package com.xixka.losslesstrim.ffmpeg

import android.content.Context
import android.net.Uri
import com.antonkarpenko.ffmpegkit.FFmpegKitConfig
import com.antonkarpenko.ffmpegkit.FFprobeKit
import com.xixka.losslesstrim.data.ProbeResult
import com.xixka.losslesstrim.data.StreamInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
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

    suspend fun probeMedia(context: Context, uri: Uri): ProbeResult = withContext(Dispatchers.IO) {
        try {
            val input = FFmpegKitConfig.getSafParameterForRead(context, uri)
            val session = FFprobeKit.execute(
                "-v error -show_streams -show_format -of json -i \"$input\""
            )
            val output = session.allLogsAsString
            val rc = session.returnCode
            if (rc == null || !rc.isValueSuccess || output.isNullOrBlank()) {
                return@withContext ProbeResult(
                    probeOk = false,
                    error = tailOf(session.allLogsAsString ?: "ffprobe 无输出", 200)
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
            val session = FFprobeKit.execute(
                "-v error -select_streams v:0 -show_entries packet=pts_time,flags -of csv=p=0 -i \"$input\""
            )
            val output = session.allLogsAsString
            val rc = session.returnCode
            if (rc != null && rc.isValueSuccess && !output.isNullOrBlank()) {
                // 仅成功结果写入缓存；失败不缓存，避免同一文件整个进程周期内都无法重试对齐
                val parsed = parseKeyframeCsv(output)
                keyframeCache[uri.toString()] = parsed
                parsed
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
     * 解析 `-show_entries packet=pts_time,flags -of csv=p=0` 的逐行输出：
     * p=0 时形如 `0.000000,__K`（部分版本会带 `packet,` 前缀），
     * 末列 flags 含 K 即关键帧；在前面各列里取第一个可解析的时间。
     * 容错：跳过乱入的错误日志行与 pts_time 缺失的行。
     */
    private fun parseKeyframeCsv(csv: String): List<Double> {
        val kfs = ArrayList<Double>()
        for (line in csv.lineSequence()) {
            if (line.isEmpty()) continue
            val parts = line.split(',')
            if (parts.size < 2) continue
            val flags = parts.last().trim()
            if (!flags.contains('K')) continue
            val t = parts.asSequence()
                .dropLast(1)
                .firstNotNullOfOrNull { it.trim().toDoubleOrNull() }
            if (t != null && t >= 0) kfs.add(t)
        }
        kfs.sort()
        return kfs
    }

    private fun tailOf(text: String, max: Int): String {
        val clean = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return if (clean.size <= 3) clean.joinToString("; ")
        else clean.takeLast(3).joinToString("; ").take(max)
    }
}
