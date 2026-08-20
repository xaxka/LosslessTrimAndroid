package com.xixka.losslesstrim

import com.xixka.losslesstrim.data.ProbeResult
import com.xixka.losslesstrim.data.StreamInfo
import com.xixka.losslesstrim.trim.TrimService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 剪辑命令时间轴修复单测（docs/output-timeline.md）：
 * 1. 片头剪切不传 -ss（-ss 0 会丢开头音轨包）；
 * 2. 字幕时长钳制 bsf（修结尾超播）与门控。
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
}
