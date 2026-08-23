package com.xixka.losslesstrim.data

import android.net.Uri

/** 单个媒体轨道（对应 ffprobe -show_streams 的一条流） */

/** Dolby Vision 的 codec_name 直接形态（部分封装直接作为 codec 名上报） */
private val DV_CODEC_NAMES = setOf("dvh1", "dvhe", "dva1", "dvav")

/** Dolby Vision 的 mp4 codec_tag 形态（codec_name 报 hevc/avc 时靠 tag 区分） */
private val DV_CODEC_TAGS = setOf("dvh1", "dvhe", "dva1", "dvav")

data class StreamInfo(
    val index: Int,            // 全局流索引（-map 0:index 用）
    val codecType: String,     // video / audio / subtitle / data / attachment
    val codecName: String,     // h264 / aac / subrip / mjpeg ...
    val language: String?,     // tags.language
    val title: String?,        // tags.title
    val channels: Int?,        // 音频声道数
    val channelLayout: String?,
    val width: Int?,
    val height: Int?,
    val attachedPic: Boolean,  // 封面图轨
    // 视频重排缓冲大小（ffprobe has_b_frames，>0 即含 B 帧）；null=未知（旧缓存行/
    // 平台 MediaExtractor 兜底），见 docs/mkv-bframe-seek-offset.md §7
    val hasBFrames: Int? = null,
    // 显示旋转角度（度，来自 side_data "Display Matrix"；0/360 的倍数=无旋转）。
    // null=未知（旧缓存行）。MKV 侧 ffmpeg≥6.1 写 Projection 元素可保留数据，
    // 但部分播放器不识别 → 转 MKV 时结果页提示兼容风险
    val rotation: Int? = null,
    // ffprobe codec_tag_string（清掉 \0 填充后），用于识别 Dolby Vision
    // （dvh1/dvhe/dva1/dvav；codec_name 对 DV 仍报 hevc/avc，靠 tag 区分）
    val codecTag: String? = null,
    // ffprobe pix_fmt（视频流像素格式，如 yuv420p / yuv420p10le）。
    // null=未知（platform 兜底探测 / 旧缓存行）。用于区分 8-bit / 10-bit，
    // 辅助调试 mediacodec 硬解 10-bit HEVC 的颜色问题。
    val pixFmt: String? = null,
) {
    val isVideo: Boolean get() = codecType == "video" && !attachedPic
    val isAudio: Boolean get() = codecType == "audio"
    val isSubtitle: Boolean get() = codecType == "subtitle"
    val isCover: Boolean get() = codecType == "video" && attachedPic

    /** 10-bit 像素格式（yuv420p10le / p010le 等含 "10"）；null（未知）不算 */
    val is10Bit: Boolean get() = pixFmt?.contains("10") == true

    /**
     * Dolby Vision 流（mp4/mov 里 tag 为 dvh1/dvhe/dva1/dvav；mkv 的 DV 无
     * 专属 tag，识别不了——可探测面即 mp4 系，恰是手机拍摄 DV 的主形态）。
     * 转封装无损保留，但非 DV 设备播放可能偏色/黑屏 → 结果页提示。
     */
    val isDolbyVision: Boolean
        get() = codecName in DV_CODEC_NAMES || codecTag in DV_CODEC_TAGS

    /** 轨道列表展示文本 */
    fun label(): String {
        val type = when {
            isCover -> "封面"
            isVideo -> "视频"
            isAudio -> "音频"
            isSubtitle -> "字幕"
            else -> "数据"
        }
        val detail = when {
            isCover -> codecName
            isVideo -> buildString {
                append(codecName)
                if (width != null && height != null) append(" ${width}×$height")
            }
            isAudio -> buildString {
                append(codecName)
                if (channels != null) append(
                    when (channels) {
                        1 -> " 单声道"
                        2 -> " 立体声"
                        else -> " ${channels}声道"
                    }
                )
            }
            else -> codecName
        }
        val lang = language?.takeIf { it.isNotEmpty() && it != "und" } ?: ""
        return "#$index $type · $detail${if (lang.isNotEmpty()) " · $lang" else ""}"
    }
}

/** ffprobe 解析结果 */
data class ProbeResult(
    val probeOk: Boolean,
    val durationSec: Double = 0.0,
    val formatName: String = "",
    // ffprobe format.start_time（秒，常为负：AAC priming 等导致音/字幕轨起始
    // pts<0）。ffmpeg seek 时会把它加进目标（ffmpeg_demux.c timestamp += ic->start_time），
    // 直接影响 -ss 落点补偿量（见 TrimService.seekFudgeSec）；null=未知按 0 处理
    val startTimeSec: Double? = null,
    val streams: List<StreamInfo> = emptyList(),
    val error: String? = null,
) {
    val videoCodec: String?
        get() = streams.firstOrNull { it.isVideo }?.codecName
}

/** 列表里的一条视频 */
data class VideoEntry(
    val treeUri: Uri,      // 选中的根目录 tree uri（单文件模式 = 文件自身 uri）
    val folderUri: Uri?,   // 所在文件夹（tree 作用域内）；单文件模式为 null → 只能另存为
    val docUri: Uri,       // 文件本身的 document uri
    val name: String,      // 显示名（含扩展名）
    val sizeBytes: Long,
    val probe: ProbeResult,
    val filePath: String? = null, // 可直接访问的绝对路径（已授权全部文件权限时非空，否则走 saf:）
) {
    val isSingleFile: Boolean get() = folderUri == null
    val baseName: String get() = name.substringBeforeLast('.', name)
    val ext: String get() = name.substringAfterLast('.', "").lowercase()
}

/** 单文件覆盖参数（仅作用于该文件，null 表示跟随全局） */
data class PerFileOverride(
    val headSec: Double? = null,
    val tailSec: Double? = null,
    val intervalStartSec: Double? = null,
    val intervalEndSec: Double? = null,
    val droppedStreams: Set<Int> = emptySet(),
) {
    val isEmpty: Boolean
        get() = headSec == null && tailSec == null && intervalStartSec == null &&
                intervalEndSec == null && droppedStreams.isEmpty()
}

/** 一次剪辑计划（逻辑层，不含关键帧对齐） */
data class TrimPlan(
    val ok: Boolean,
    val skipReason: String? = null,
    val requestedStart: Double = 0.0,   // 期望切点（未对齐）
    val requestedEnd: Double = 0.0,
    val actualStart: Double = 0.0,      // 关键帧对齐后实际切点
    val actualEnd: Double = 0.0,
    val truncated: Boolean = false,     // 区间模式：终点按片长截断
) {
    val duration: Double get() = (actualEnd - actualStart).coerceAtLeast(0.0)
}

/** 队列中单个文件的处理结果 */
enum class Outcome { PENDING, SUCCESS, FAILED, SKIPPED, CANCELLED }

data class FileResult(
    val entry: VideoEntry,
    val plan: TrimPlan,
    val outcome: Outcome,
    val origSize: Long,
    val newSize: Long = 0L,
    val reason: String? = null,
    /** 成功但有兼容性风险的非阻断提示（旋转元数据/Dolby Vision 等），结果页展示 */
    val warnings: List<String> = emptyList(),
)
