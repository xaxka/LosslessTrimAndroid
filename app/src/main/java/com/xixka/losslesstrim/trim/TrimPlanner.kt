package com.xixka.losslesstrim.trim

import com.xixka.losslesstrim.data.AlignStrategy
import com.xixka.losslesstrim.data.AppSettings
import com.xixka.losslesstrim.data.OutputContainer
import com.xixka.losslesstrim.data.PerFileOverride
import com.xixka.losslesstrim.data.TrimMode
import com.xixka.losslesstrim.data.TrimPlan
import com.xixka.losslesstrim.data.VideoEntry

/**
 * 剪辑计划：把"头尾裁剪 / 区间保留"两种模式统一归一化为 [start, end]，
 * 并按关键帧对齐策略计算无损剪辑的实际切点。
 */
object TrimPlanner {

    /** 逻辑计划（不含关键帧对齐），用于列表状态与批量前校验 */
    fun logicalPlan(entry: VideoEntry, s: AppSettings, o: PerFileOverride?): TrimPlan {
        if (!entry.probe.probeOk) {
            return TrimPlan(ok = false, skipReason = "不可处理（${entry.probe.error ?: "元数据解析失败"}）")
        }
        val dur = entry.probe.durationSec
        return when (s.mode) {
            TrimMode.HEAD_TAIL -> {
                // 片头/片尾每视频单独设置（PerFileOverride），无设置时默认 0 = 不切
                val head = (o?.headSec ?: 0.0).coerceAtLeast(0.0)
                val tail = (o?.tailSec ?: 0.0).coerceAtLeast(0.0)
                if (head + tail >= dur) {
                    TrimPlan(ok = false, skipReason = "剪完将为空（片头${head}s + 片尾${tail}s ≥ 总时长）")
                } else {
                    TrimPlan(ok = true, requestedStart = head, requestedEnd = dur - tail,
                        actualStart = head, actualEnd = dur - tail)
                }
            }
            TrimMode.INTERVAL -> {
                // 负值（-1）= 不切：起点归一化为 0，终点归一化为片长
                val rawStart = o?.intervalStartSec ?: s.intervalStartSec
                val rawEnd = o?.intervalEndSec ?: s.intervalEndSec
                val start = if (rawStart < 0) 0.0 else rawStart
                val end = if (rawEnd < 0) dur else rawEnd
                if (start >= end) {
                    TrimPlan(ok = false, skipReason = "参数非法：开始 ≥ 结束")
                } else if (dur <= start) {
                    TrimPlan(ok = false, skipReason = "区间起点超出片长")
                } else if (end > dur) {
                    if (s.truncateOverlong) {
                        TrimPlan(ok = true, requestedStart = start, requestedEnd = dur,
                            actualStart = start, actualEnd = dur, truncated = true)
                    } else {
                        TrimPlan(ok = false, skipReason = "区间终点超出片长（设置为跳过）")
                    }
                } else {
                    TrimPlan(ok = true, requestedStart = start, requestedEnd = end,
                        actualStart = start, actualEnd = end)
                }
            }
        }
    }

    /** 对齐后计划（喂给 ffmpeg 的权威值） */
    fun alignedPlan(entry: VideoEntry, s: AppSettings, o: PerFileOverride?, keyframes: List<Double>): TrimPlan {
        val base = logicalPlan(entry, s, o)
        if (!base.ok || keyframes.isEmpty()) return base
        val dur = entry.probe.durationSec
        val aStart = align(base.requestedStart, keyframes, isEnd = false, strategy = s.alignment)
            .coerceIn(0.0, dur)
        val aEnd = align(base.requestedEnd, keyframes, isEnd = true, strategy = s.alignment)
            .coerceIn(0.0, dur)
        return if (aEnd - aStart <= 0.05) {
            base.copy(ok = false, skipReason = "关键帧对齐后区间为空（GOP 过长或切点过近）")
        } else {
            base.copy(actualStart = aStart, actualEnd = aEnd)
        }
    }

    /**
     * 关键帧对齐。
     * 多切：起点对齐到后一个关键帧（多砍），终点对齐到前一个关键帧（多砍）
     * 少切：反之；自动：各取最近。
     */
    fun align(t: Double, kfs: List<Double>, isEnd: Boolean, strategy: AlignStrategy): Double {
        if (kfs.isEmpty()) return t
        return when (strategy) {
            AlignStrategy.CUT_MORE -> if (isEnd) prevOrFirst(kfs, t) else nextOrLast(kfs, t)
            AlignStrategy.CUT_LESS -> if (isEnd) nextOrLast(kfs, t) else prevOrFirst(kfs, t)
            AlignStrategy.AUTO -> nearest(kfs, t)
        }
    }

    private fun prevOrFirst(kfs: List<Double>, t: Double): Double {
        // 最后一个 ≤ t 的关键帧；没有则第一个
        var res = kfs[0]
        for (k in kfs) {
            if (k <= t) res = k else break
        }
        return res
    }

    private fun nextOrLast(kfs: List<Double>, t: Double): Double {
        // 第一个 ≥ t 的关键帧；没有则最后一个
        for (k in kfs) {
            if (k >= t) return k
        }
        return kfs.last()
    }

    private fun nearest(kfs: List<Double>, t: Double): Double {
        var best = kfs[0]
        var bestDiff = Double.MAX_VALUE
        for (k in kfs) {
            val d = Math.abs(k - t)
            if (d < bestDiff) {
                bestDiff = d
                best = k
            }
        }
        return best
    }
}

/** 输出封装目标 */
data class OutputTarget(val muxer: String, val ext: String, val mime: String)

object Containers {
    private val KEEP_MAP = mapOf(
        "mp4" to OutputTarget("mp4", "mp4", "video/mp4"),
        "m4v" to OutputTarget("mp4", "m4v", "video/mp4"),
        "m4a" to OutputTarget("mp4", "m4a", "video/mp4"),
        "mov" to OutputTarget("mov", "mov", "video/quicktime"),
        "mkv" to OutputTarget("matroska", "mkv", "video/x-matroska"),
        "webm" to OutputTarget("matroska", "webm", "video/webm"),
        "ts" to OutputTarget("mpegts", "ts", "video/mp2ts"),
        "m2ts" to OutputTarget("mpegts", "m2ts", "video/mp2ts"),
        "mts" to OutputTarget("mpegts", "mts", "video/mp2ts"),
        "avi" to OutputTarget("avi", "avi", "video/x-msvideo"),
        "flv" to OutputTarget("flv", "flv", "video/x-flv"),
        "3gp" to OutputTarget("3gp", "3gp", "video/3gpp"),
        "3g2" to OutputTarget("3gp", "3g2", "video/3gpp"),
        "mpg" to OutputTarget("mpeg", "mpg", "video/mpeg"),
        "mpeg" to OutputTarget("mpeg", "mpeg", "video/mpeg"),
        "wmv" to OutputTarget("asf", "wmv", "video/x-ms-asf"),
        "ogv" to OutputTarget("ogg", "ogv", "video/ogg"),
    )

    fun resolve(container: OutputContainer, originalExt: String): OutputTarget? = when (container) {
        OutputContainer.MP4 -> OutputTarget("mp4", "mp4", "video/mp4")
        OutputContainer.MKV -> OutputTarget("matroska", "mkv", "video/x-matroska")
        OutputContainer.KEEP -> KEEP_MAP[originalExt]
    }
}
