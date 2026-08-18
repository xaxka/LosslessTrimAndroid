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
import java.util.concurrent.ConcurrentHashMap

/**
 * ffprobe 封装：媒体信息（流列表）+ 关键帧位置探测。
 * 全部通过 ffmpeg-kit 的 saf: 协议直接读 SAF 文档。
 */
object Probe {

    /** 关键帧缓存（uri string → 升序关键帧时间列表），进程内复用 */
    val keyframeCache = ConcurrentHashMap<String, List<Double>>()

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
            val session = FFprobeKit.execute(
                "-v error -select_streams v:0 -show_entries packet=pts_time,flags -of json -i \"$input\""
            )
            val output = session.allLogsAsString
            val rc = session.returnCode
            val result = if (rc != null && rc.isValueSuccess && !output.isNullOrBlank()) {
                parseKeyframeJson(output)
            } else emptyList()
            keyframeCache[uri.toString()] = result
            result
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

    private fun parseKeyframeJson(json: String): List<Double> {
        return try {
            val root = JSONObject(json)
            val packets = root.optJSONArray("packets") ?: return emptyList()
            val kfs = ArrayList<Double>()
            for (i in 0 until packets.length()) {
                val p = packets.optJSONObject(i) ?: continue
                val flags = p.optString("flags", "")
                val t = p.optString("pts_time", "").toDoubleOrNull() ?: continue
                if (t >= 0 && flags.contains("K")) kfs.add(t)
            }
            kfs.sort()
            kfs
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun tailOf(text: String, max: Int): String {
        val clean = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return if (clean.size <= 3) clean.joinToString("; ")
        else clean.takeLast(3).joinToString("; ").take(max)
    }
}
