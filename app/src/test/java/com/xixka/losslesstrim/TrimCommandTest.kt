package com.xixka.losslesstrim

import com.xixka.losslesstrim.data.ProbeResult
import com.xixka.losslesstrim.data.StreamInfo
import com.xixka.losslesstrim.data.TrimPlan
import com.xixka.losslesstrim.ffmpeg.SyncSamples
import com.xixka.losslesstrim.trim.OutputTarget
import com.xixka.losslesstrim.trim.TrimService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 剪辑命令时间轴修复单测（docs/output-timeline.md）：
 * 1. 片头剪切不传 -ss（-ss 0 会丢开头音轨包）；
 * 2. 字幕时长钳制 bsf（修结尾超播）与门控；
 * 3. disposition 重设 / 附件保留 / muxdelay / 时间轴校验与警告。
 */
class TrimCommandTest {

    private fun stream(index: Int, type: String) = StreamInfo(
        index = index, codecType = type, codecName = "x",
        language = null, title = null, channels = null, channelLayout = null,
        width = null, height = null, attachedPic = false,
    )

    // ---------- seekArgs ----------

    @Test
    fun `head cut omits -ss entirely`() {
        assertEquals("", TrimService.seekArgs(0.0))
        assertEquals("", TrimService.seekArgs(0.001))
    }

    @Test
    fun `mid cut emits -ss and -noaccurate_seek`() {
        assertEquals(" -ss 30.154 -noaccurate_seek", TrimService.seekArgs(30.154))
    }

    @Test
    fun `tiny positive ss still seeks`() {
        // 对齐后的起点要么是 0（走省略分支）要么是真关键帧；极小正值防御性走 seek
        assertEquals(" -ss 0.174 -noaccurate_seek", TrimService.seekArgs(0.174))
    }

    // ---------- 时间戳归零（不传 -avoid_negative_ts，防回退锚点） ----------

    @Test
    fun `assembled commands never pass avoid_negative_ts`() {
        // 中段剪曾传 make_zero：B 帧重排下首帧 PTS 残留重排延迟（实测
        // bf3@12.5fps → start=0.160s，线上即"输出校验失败(起点未归零
        // start=0.200s)"）；改用 ffmpeg 默认 auto 后 mp4 走 edit list+负
        // CTS、mkv 走首包基线，start_time=0。此断言防任何人把 make_zero
        // 加回装配（TrimService 时间戳归零注释有完整实测矩阵）。
        val plan = TrimPlan(
            ok = true, requestedStart = 30.0, requestedEnd = 60.0,
            actualStart = 30.0, actualEnd = 60.0,
        )
        val mid = TrimService.assembleCommand(
            "/in.mkv", "/out.mkv", plan, listOf(0, 1, 2), mkvTarget(), bframeProbe(),
        )
        assertFalse(mid.contains("avoid_negative_ts"))
        val head = TrimService.assembleCommand(
            "/in.mkv", "/out.mkv",
            plan.copy(requestedStart = 0.0, actualStart = 0.0),
            listOf(0, 1, 2), mkvTarget(), bframeProbe(),
        )
        assertFalse(head.contains("avoid_negative_ts"))
    }

    // ---------- subtitleClampBsf ----------

    @Test
    fun `clamp expression keeps commas escaped and uses TB for unit conversion`() {
        assertEquals(
            "setts=duration=if(gte(DURATION\\,0)\\,max(min(DURATION\\,(29.946/TB)-TS)\\,0)\\,0)",
            TrimService.subtitleClampBsf(29.946),
        )
    }

    @Test
    fun `clamp duration value formatted with 3 decimals`() {
        val s = TrimService.subtitleClampBsf(5.0)
        assertTrue(s.contains("(5.000/TB)"))
        assertFalse(s.contains(" "))
    }

    // ---------- hasKeptSubtitle ----------

    @Test
    fun `kept subtitle stream detected`() {
        val probe = ProbeResult(
            probeOk = true, durationSec = 60.0, formatName = "matroska",
            streams = listOf(stream(0, "video"), stream(1, "audio"), stream(2, "subtitle")),
        )
        assertTrue(TrimService.hasKeptSubtitle(probe, listOf(0, 1, 2)))
    }

    @Test
    fun `dropped subtitle stream not detected`() {
        val probe = ProbeResult(
            probeOk = true, durationSec = 60.0, formatName = "matroska",
            streams = listOf(stream(0, "video"), stream(1, "audio"), stream(2, "subtitle")),
        )
        assertFalse(TrimService.hasKeptSubtitle(probe, listOf(0, 1)))
    }

    @Test
    fun `no subtitle streams not detected`() {
        val probe = ProbeResult(
            probeOk = true, durationSec = 60.0, formatName = "matroska",
            streams = listOf(stream(0, "video"), stream(1, "audio")),
        )
        assertFalse(TrimService.hasKeptSubtitle(probe, listOf(0, 1)))
    }

    // ---------- dispositionArgs ----------

    @Test
    fun `single kept audio marked default`() {
        // 源两音轨，用户丢默认轨 1 只留轨 2 → 输出唯一音轨必须标 default
        val probe = ProbeResult(
            probeOk = true, durationSec = 60.0, formatName = "matroska",
            streams = listOf(stream(0, "video"), stream(1, "audio"), stream(2, "audio")),
        )
        assertEquals(" -disposition:a:0 default", TrimService.dispositionArgs(probe, listOf(0, 2)))
    }

    @Test
    fun `multiple kept audio first default rest zeroed`() {
        val probe = ProbeResult(
            probeOk = true, durationSec = 60.0, formatName = "matroska",
            streams = listOf(
                stream(0, "video"), stream(1, "audio"), stream(2, "audio"), stream(3, "audio"),
            ),
        )
        assertEquals(
            " -disposition:a:0 default -disposition:a:1 0 -disposition:a:2 0",
            TrimService.dispositionArgs(probe, listOf(0, 1, 2, 3)),
        )
    }

    @Test
    fun `no kept audio emits nothing`() {
        val probe = ProbeResult(
            probeOk = true, durationSec = 60.0, formatName = "matroska",
            streams = listOf(stream(0, "video"), stream(1, "audio")),
        )
        assertEquals("", TrimService.dispositionArgs(probe, listOf(0)))
    }

    // ---------- attachmentArgs / muxDelayArgs ----------

    @Test
    fun `mkv output maps attachments`() {
        assertEquals(" -map 0:t?", TrimService.attachmentArgs(OutputTarget("matroska", "mkv", "video/x-matroska")))
    }

    @Test
    fun `webm mp4 and ts outputs omit attachment mapping`() {
        assertEquals("", TrimService.attachmentArgs(OutputTarget("matroska", "webm", "video/webm")))
        assertEquals("", TrimService.attachmentArgs(OutputTarget("mp4", "mp4", "video/mp4")))
        assertEquals("", TrimService.attachmentArgs(OutputTarget("mpegts", "ts", "video/mp2ts")))
    }

    @Test
    fun `ts family zeroes muxdelay`() {
        assertEquals(
            " -muxdelay 0 -muxpreload 0",
            TrimService.muxDelayArgs(OutputTarget("mpegts", "ts", "video/mp2ts")),
        )
        assertEquals(
            " -muxdelay 0 -muxpreload 0",
            TrimService.muxDelayArgs(OutputTarget("mpeg", "mpg", "video/mpeg")),
        )
        assertEquals("", TrimService.muxDelayArgs(OutputTarget("mp4", "mp4", "video/mp4")))
        assertEquals("", TrimService.muxDelayArgs(OutputTarget("matroska", "mkv", "video/x-matroska")))
    }

    @Test
    fun `mkv output disables forced subtitle default`() {
        assertEquals(
            " -default_mode infer_no_subs",
            TrimService.matroskaFlagsArgs(OutputTarget("matroska", "mkv", "video/x-matroska")),
        )
        assertEquals("", TrimService.matroskaFlagsArgs(OutputTarget("matroska", "webm", "video/webm")))
        assertEquals("", TrimService.matroskaFlagsArgs(OutputTarget("mp4", "mp4", "video/mp4")))
        assertEquals("", TrimService.matroskaFlagsArgs(OutputTarget("mpegts", "ts", "video/mp2ts")))
    }

    // ---------- timelineWarnings ----------

    private fun videoStream(
        index: Int,
        rotation: Int? = null,
        codecTag: String? = null,
    ) = StreamInfo(
        index = index, codecType = "video", codecName = "hevc",
        language = null, title = null, channels = null, channelLayout = null,
        width = null, height = null, attachedPic = false,
        rotation = rotation, codecTag = codecTag,
    )

    @Test
    fun `rotated source to mkv warns but mp4 does not`() {
        val probe = ProbeResult(
            probeOk = true, durationSec = 60.0, formatName = "mov,mp4",
            streams = listOf(videoStream(0, rotation = 90)),
        )
        assertEquals(1, TrimService.timelineWarnings(probe, listOf(0), OutputTarget("matroska", "mkv", "video/x-matroska")).size)
        assertTrue(TrimService.timelineWarnings(probe, listOf(0), OutputTarget("mp4", "mp4", "video/mp4")).isEmpty())
    }

    @Test
    fun `no rotation and full turn emit no warning`() {
        val none = ProbeResult(
            probeOk = true, durationSec = 60.0, formatName = "mov,mp4",
            streams = listOf(videoStream(0, rotation = null)),
        )
        val full = ProbeResult(
            probeOk = true, durationSec = 60.0, formatName = "mov,mp4",
            streams = listOf(videoStream(0, rotation = 360)),
        )
        assertTrue(TrimService.timelineWarnings(none, listOf(0), OutputTarget("matroska", "mkv", "video/x-matroska")).isEmpty())
        assertTrue(TrimService.timelineWarnings(full, listOf(0), OutputTarget("matroska", "mkv", "video/x-matroska")).isEmpty())
    }

    @Test
    fun `dolby vision warns regardless of container`() {
        val probe = ProbeResult(
            probeOk = true, durationSec = 60.0, formatName = "mov,mp4",
            streams = listOf(videoStream(0, codecTag = "dvh1")),
        )
        assertEquals(1, TrimService.timelineWarnings(probe, listOf(0), OutputTarget("mp4", "mp4", "video/mp4")).size)
        assertEquals(1, TrimService.timelineWarnings(probe, listOf(0), OutputTarget("matroska", "mkv", "video/x-matroska")).size)
    }

    @Test
    fun `warnings only consider kept streams`() {
        val probe = ProbeResult(
            probeOk = true, durationSec = 60.0, formatName = "mov,mp4",
            streams = listOf(videoStream(0), videoStream(1, rotation = 90)),
        )
        // 旋转轨被丢弃 → 无警告
        assertTrue(TrimService.timelineWarnings(probe, listOf(0), OutputTarget("matroska", "mkv", "video/x-matroska")).isEmpty())
    }

    // ---------- assessTimeline ----------

    @Test
    fun `healthy output passes`() {
        assertTrue(
            TrimService.assessTimeline(
                start = 0.0, dur = 30.2, expectedDurSec = 30.1,
                hasVideo = true, hasAudio = true, videoKept = true, audioKept = true,
            ).isEmpty(),
        )
    }

    @Test
    fun `start time off zero fails`() {
        val issues = TrimService.assessTimeline(
            start = 30.0, dur = 30.2, expectedDurSec = 30.1,
            hasVideo = true, hasAudio = true, videoKept = true, audioKept = true,
        )
        assertEquals(1, issues.size)
        assertTrue(issues[0].contains("起点未归零"))
    }

    @Test
    fun `subtitle trailing duration fails`() {
        // 字幕拖尾把容器撑到 40s（期望 30s）→ 必须命中时长断言
        val issues = TrimService.assessTimeline(
            start = 0.0, dur = 40.08, expectedDurSec = 30.08,
            hasVideo = true, hasAudio = true, videoKept = true, audioKept = true,
        )
        assertEquals(1, issues.size)
        assertTrue(issues[0].contains("时长异常"))
    }

    @Test
    fun `missing kept streams fail`() {
        val issues = TrimService.assessTimeline(
            start = 0.0, dur = 30.0, expectedDurSec = 30.0,
            hasVideo = false, hasAudio = false, videoKept = true, audioKept = true,
        )
        assertEquals(2, issues.size)
        assertTrue(issues.any { it.contains("视频流") })
        assertTrue(issues.any { it.contains("音频流") })
    }

    @Test
    fun `dropped audio absence is fine`() {
        assertTrue(
            TrimService.assessTimeline(
                start = 0.0, dur = 30.0, expectedDurSec = 30.0,
                hasVideo = true, hasAudio = false, videoKept = true, audioKept = false,
            ).isEmpty(),
        )
    }

    @Test
    fun `unknown start fails and ts family tolerance relaxes`() {
        assertTrue(
            TrimService.assessTimeline(
                start = null, dur = 30.0, expectedDurSec = 30.0,
                hasVideo = true, hasAudio = true, videoKept = true, audioKept = true,
            ).any { it.contains("起始时间未知") },
        )
        assertTrue(
            TrimService.assessTimeline(
                start = 1.4, dur = 30.0, expectedDurSec = 30.0,
                hasVideo = true, hasAudio = true, videoKept = true, audioKept = true,
                startToleranceSec = 1.6,
            ).isEmpty(),
        )
    }

    // ---------- assessSync（音视频 drift + 字幕绝对界，数值取自 verify-timeline.sh T12 实测） ----------

    private fun sync(
        sv: Double? = null, sa: Double? = null,
        ov: Double? = null, oa: Double? = null, os: Double? = null,
    ) = SyncSamples(sv, sa, ov, oa, os)

    @Test
    fun `healthy mid cut drift and subtitle pass`() {
        // T12 健康臂实测：src@切点 v=30.023 a=30.023 | out v=0.000 a=-0.069 s=2.000
        //（-0.069 = 输出 mkv 首包基线/edit list 类封装噪声，远小于 0.5 容差）
        assertTrue(
            TrimService.assessSync(
                sync(sv = 30.023, sa = 30.023, ov = 0.0, oa = -0.069, os = 2.0),
                seekSec = 30.154, expectedDurSec = 29.977,
            ).isEmpty(),
        )
    }

    @Test
    fun `audio leading source head cut passes`() {
        // T12 健康臂实测（音频超前 0.8s 源片头剪）：src v=0.823 a=0.000 |
        // out v=0.823 a=0.000 s=5.823 —— 源音画间隔被原样保留（drift=0），
        // 字幕窗口随 ov=0.823 平移（上界 31.323）
        assertTrue(
            TrimService.assessSync(
                sync(sv = 0.823, sa = 0.0, ov = 0.823, oa = 0.0, os = 5.823),
                seekSec = 0.0, expectedDurSec = 30.0,
            ).isEmpty(),
        )
    }

    @Test
    fun `dropped head audio drift fails`() {
        // T12 坏剪臂实测（-ss 0 + make_zero 丢头音频）：src v=0.823 a=0.000 |
        // out v=0.080 a=0.000 → drift=+0.743 超容差
        val issues = TrimService.assessSync(
            sync(sv = 0.823, sa = 0.0, ov = 0.08, oa = 0.0),
            seekSec = 0.0, expectedDurSec = 30.0,
        )
        assertEquals(1, issues.size)
        assertTrue(issues[0].contains("音视频不同步"))
        assertTrue(issues[0].contains("0.743"))
    }

    @Test
    fun `audio keeping absolute timestamps fails`() {
        // 音频未随切点平移（保源绝对时间戳）：oa−ov=30.023 而 sa−sv=0 →
        // drift=+30.023
        val issues = TrimService.assessSync(
            sync(sv = 30.023, sa = 30.023, ov = 0.0, oa = 30.023),
            seekSec = 30.154, expectedDurSec = 29.977,
        )
        assertEquals(1, issues.size)
        assertTrue(issues[0].contains("音视频不同步"))
    }

    @Test
    fun `null samples skip drift check`() {
        // 纯视频源/采样失败：不判失败（采样失败 ≠ 输出坏）
        assertTrue(
            TrimService.assessSync(
                sync(sv = 30.023, sa = null, ov = 0.0, oa = null),
                seekSec = 30.154, expectedDurSec = 29.977,
            ).isEmpty(),
        )
    }

    @Test
    fun `seek failure head landing is skipped not failed`() {
        // 区间 seek 失败落回文件头：sv=0.823 偏离锚点 30.154 超过 30s 防呆
        // 界 → 按未采到处理（不防呆会算出 drift≈+29.4 的假信号误杀好成片）
        assertTrue(
            TrimService.assessSync(
                sync(sv = 0.823, sa = 0.0, ov = 0.0, oa = -0.069),
                seekSec = 30.154, expectedDurSec = 29.977,
            ).isEmpty(),
        )
    }

    @Test
    fun `long gop landing is still verified`() {
        // 超长 GOP（25s）合法落点：sv 偏离锚点 25.154 < 30s 防呆界 → 照常
        // 校验，drift=(0−0)−(4.977−5.0)=+0.023 通过
        assertTrue(
            TrimService.assessSync(
                sync(sv = 5.0, sa = 4.977, ov = 0.0, oa = 0.0),
                seekSec = 30.154, expectedDurSec = 29.977,
            ).isEmpty(),
        )
    }

    @Test
    fun `shifted subtitle within anchored window passes`() {
        // 音频超前源整体平移：ov=0.823 锚定窗口 [−0.5, 31.323]，窗口末端
        // cue 30.7 合法（若错误地用 0 锚定或 planDur 不含平移会误报）
        assertTrue(
            TrimService.assessSync(
                sync(ov = 0.823, os = 30.7),
                seekSec = 0.0, expectedDurSec = 30.0,
            ).isEmpty(),
        )
    }

    @Test
    fun `unshifted subtitle beyond window fails`() {
        // 字幕未随切点平移（保源绝对时间戳 32s）：窗口上界 0+29.977+0.5=30.477
        val issues = TrimService.assessSync(
            sync(ov = 0.0, os = 32.0),
            seekSec = 30.154, expectedDurSec = 29.977,
        )
        assertEquals(1, issues.size)
        assertTrue(issues[0].contains("字幕时间轴异常"))
    }

    @Test
    fun `subtitle before container start fails`() {
        // bsf 损坏类异常：首 cue 落在容器起点之前
        val issues = TrimService.assessSync(
            sync(ov = 0.0, os = -0.9),
            seekSec = 0.0, expectedDurSec = 30.0,
        )
        assertEquals(1, issues.size)
        assertTrue(issues[0].contains("字幕时间轴异常"))
    }

    @Test
    fun `no subtitle sample skips window check`() {
        // 窗口内无 cue（稀疏字幕）/未保留字幕：跳过，不判失败
        assertTrue(
            TrimService.assessSync(
                sync(ov = 0.0, os = null),
                seekSec = 30.154, expectedDurSec = 29.977,
            ).isEmpty(),
        )
    }

    // ---------- seekTargetSec（采样锚点与命令 -ss 严格一致） ----------

    @Test
    fun `seekTargetSec matches command ss and zeroes on head cut`() {
        // bframeProbe：fudge = 0.131 + 0.023(start_time) = 0.154（与
        // assembleCommand 断言的 -ss 30.154 同源；锚点偏了 drift 就是假信号）
        val plan = TrimPlan(
            ok = true, requestedStart = 30.0, requestedEnd = 60.0,
            actualStart = 30.0, actualEnd = 60.0,
        )
        assertEquals(30.154, TrimService.seekTargetSec(plan, bframeProbe()), 1e-9)
        assertEquals(0.0, TrimService.seekTargetSec(plan.copy(actualStart = 0.0), bframeProbe()), 1e-9)
    }

    // ---------- assembleCommand（装配锚点：任何装配步骤被回退即红） ----------

    /** 样例与 docs/mkv-bframe-seek-offset.md 同构：matroska + bf=2 + start_time=-0.023 */
    private fun bframeProbe() = ProbeResult(
        probeOk = true, durationSec = 60.0, formatName = "matroska", startTimeSec = -0.023,
        streams = listOf(
            StreamInfo(
                index = 0, codecType = "video", codecName = "h264",
                language = null, title = null, channels = null, channelLayout = null,
                width = null, height = null, attachedPic = false, hasBFrames = 2,
            ),
            stream(1, "audio"),
            stream(2, "subtitle"),
        ),
    )

    private fun mkvTarget() = OutputTarget("matroska", "mkv", "video/x-matroska")

    @Test
    fun `assemble mid cut applies fudge to ss and anchors t on it`() {
        // fudge = 0.131 + 0.023(start_time) = 0.154 → ss=30.154、dur=60-30.154=29.846。
        // 若有人把 "ss = actualStart + fudge" 改回 actualStart，或忘了 -t 同步减
        // fudge，此断言即红（docs/mkv-bframe-seek-offset.md §7）
        val plan = TrimPlan(
            ok = true, requestedStart = 30.0, requestedEnd = 60.0,
            actualStart = 30.0, actualEnd = 60.0,
        )
        val cmd = TrimService.assembleCommand(
            "/in.mkv", "/out.mkv", plan, listOf(0, 1, 2), mkvTarget(), bframeProbe(),
        )
        assertEquals(
            "-hide_banner -y -ss 30.154 -noaccurate_seek -i \"/in.mkv\" -t 29.846 " +
                "-map 0:0 -map 0:1 -map 0:2 -map 0:t? -c copy -map_metadata 0 -map_chapters 0 " +
                "-bsf:s setts=duration=if(gte(DURATION\\,0)\\,max(min(DURATION\\,(29.846/TB)-TS)\\,0)\\,0) " +
                "-disposition:a:0 default -default_mode infer_no_subs -ignore_unknown -f matroska \"/out.mkv\"",
            cmd,
        )
    }

    @Test
    fun `assemble head cut omits ss and keeps clamp`() {
        // 片头剪：无 -ss（防丢音频），钳制与加固项保留
        val plan = TrimPlan(
            ok = true, requestedStart = 0.0, requestedEnd = 30.0,
            actualStart = 0.0, actualEnd = 30.0,
        )
        val cmd = TrimService.assembleCommand(
            "/in.mkv", "/out.mkv", plan, listOf(0, 1, 2), mkvTarget(), bframeProbe(),
        )
        assertEquals(
            "-hide_banner -y -i \"/in.mkv\" -t 30.000 " +
                "-map 0:0 -map 0:1 -map 0:2 -map 0:t? -c copy -map_metadata 0 -map_chapters 0 " +
                "-bsf:s setts=duration=if(gte(DURATION\\,0)\\,max(min(DURATION\\,(30.000/TB)-TS)\\,0)\\,0) " +
                "-disposition:a:0 default -default_mode infer_no_subs -ignore_unknown -f matroska \"/out.mkv\"",
            cmd,
        )
    }

    @Test
    fun `assemble mp4 input skips fudge and mp4 output uses faststart`() {
        // mp4 输入设 AVFMT_SEEK_TO_PTS，无 B帧前移 → ss=30.000 原值；
        // mp4 输出：无附件 map、追加 faststart；probe 无字幕 → 追加 -map 0:s? 兜底、无 bsf
        val plan = TrimPlan(
            ok = true, requestedStart = 30.0, requestedEnd = 60.0,
            actualStart = 30.0, actualEnd = 60.0,
        )
        val probe = ProbeResult(
            probeOk = true, durationSec = 60.0, formatName = "mov,mp4", startTimeSec = 0.0,
            streams = listOf(
                StreamInfo(
                    index = 0, codecType = "video", codecName = "h264",
                    language = null, title = null, channels = null, channelLayout = null,
                    width = null, height = null, attachedPic = false, hasBFrames = 2,
                ),
                stream(1, "audio"),
            ),
        )
        val cmd = TrimService.assembleCommand(
            "/in.mp4", "/out.mp4", plan, listOf(0, 1),
            OutputTarget("mp4", "mp4", "video/mp4"), probe,
        )
        assertEquals(
            "-hide_banner -y -ss 30.000 -noaccurate_seek -i \"/in.mp4\" -t 30.000 " +
                "-map 0:0 -map 0:1 -map 0:s? -c copy -map_metadata 0 -map_chapters 0 " +
                "-disposition:a:0 default -movflags +faststart+use_metadata_tags -ignore_unknown -f mp4 \"/out.mp4\"",
            cmd,
        )
    }

    @Test
    fun `assemble degraded retry drops subtitle clamp bsf`() {
        val plan = TrimPlan(
            ok = true, requestedStart = 30.0, requestedEnd = 60.0,
            actualStart = 30.0, actualEnd = 60.0,
        )
        val cmd = TrimService.assembleCommand(
            "/in.mkv", "/out.mkv", plan, listOf(0, 1, 2), mkvTarget(), bframeProbe(),
            clampSubtitles = false,
        )
        assertFalse(cmd.contains(" -bsf:s "))
        // 降级只去钳制，其余加固项（fudge/disposition/附件）不动，
        // avoid_negative_ts 任何形态都不出现（见"never pass"测试）
        assertTrue(cmd.contains(" -ss 30.154 "))
        assertFalse(cmd.contains("avoid_negative_ts"))
        assertTrue(cmd.contains(" -disposition:a:0 default"))
    }

    @Test
    fun `assemble platform fallback maps subtitles when probe missed them`() {
        // 平台兜底 MediaExtractor 对 MKV 内嵌 ASS/PGS 等不暴露 track →
        // probe.streams 无字幕 → 追加 -map 0:s? 兜底带出（? = 无字幕不报错）
        val plan = TrimPlan(
            ok = true, requestedStart = 30.0, requestedEnd = 60.0,
            actualStart = 30.0, actualEnd = 60.0,
        )
        val noSubProbe = ProbeResult(
            probeOk = true, durationSec = 60.0, formatName = "matroska",
            streams = listOf(stream(0, "video"), stream(1, "audio")),
        )
        val cmd = TrimService.assembleCommand(
            "/in.mkv", "/out.mkv", plan, listOf(0, 1), mkvTarget(), noSubProbe,
        )
        assertTrue(cmd.contains(" -map 0:s? "))
        // ffprobe 检测到字幕时不加 -map 0:s?（已在 bframeProbe 测试覆盖：
        // 有字幕轨 → 显式 -map 0:2，不加 -map 0:s? 避免重复映射）
        val cmdWithSub = TrimService.assembleCommand(
            "/in.mkv", "/out.mkv", plan, listOf(0, 1, 2), mkvTarget(), bframeProbe(),
        )
        assertFalse(cmdWithSub.contains(" -map 0:s?"))
    }
}
