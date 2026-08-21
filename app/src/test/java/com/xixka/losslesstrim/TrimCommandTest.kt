package com.xixka.losslesstrim

import com.xixka.losslesstrim.data.ProbeResult
import com.xixka.losslesstrim.data.StreamInfo
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

    // ---------- avoidNegativeTsArgs ----------

    @Test
    fun `head cut omits make_zero letting ffmpeg default auto apply`() {
        assertEquals("", TrimService.avoidNegativeTsArgs(0.0))
        assertEquals("", TrimService.avoidNegativeTsArgs(0.001))
    }

    @Test
    fun `mid cut keeps make_zero to reset absolute timestamps`() {
        assertEquals(" -avoid_negative_ts make_zero", TrimService.avoidNegativeTsArgs(30.154))
        assertEquals(" -avoid_negative_ts make_zero", TrimService.avoidNegativeTsArgs(0.174))
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
}
