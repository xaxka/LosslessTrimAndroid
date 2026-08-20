package com.xixka.losslesstrim

import com.xixka.losslesstrim.data.ProbeResult
import com.xixka.losslesstrim.data.StreamInfo
import com.xixka.losslesstrim.trim.TrimService
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * MKV+B帧 seek 前移补偿门控逻辑单测（docs/mkv-bframe-seek-offset.md §7）。
 * 只测纯函数 seekFudgeSec，不触碰 Android 运行时。
 *
 * 两个偏移源：3/23s DTS 启发修正 + ic->start_time（AAC priming 常致负值）。
 */
class SeekFudgeTest {

    private fun probe(
        formatName: String,
        hasBFrames: Int? = 2,
        startTime: Double? = 0.0,
        video: Boolean = true,
    ) = ProbeResult(
        probeOk = true,
        durationSec = 60.0,
        formatName = formatName,
        startTimeSec = startTime,
        streams = if (video) listOf(
            StreamInfo(
                index = 0, codecType = "video", codecName = "h264",
                language = null, title = null, channels = null, channelLayout = null,
                width = 1920, height = 1080, attachedPic = false, hasBFrames = hasBFrames,
            )
        ) else emptyList(),
    )

    @Test
    fun `matroska with B-frames mid-file start gets fudge`() {
        assertEquals(
            TrimService.SEEK_FUDGE_SEC,
            TrimService.seekFudgeSec(30.023, probe("matroska")), 1e-9,
        )
    }

    @Test
    fun `webm with B-frames gets fudge`() {
        assertEquals(
            TrimService.SEEK_FUDGE_SEC,
            TrimService.seekFudgeSec(5.0, probe("webm")), 1e-9,
        )
    }

    @Test
    fun `comma-separated format name matches matroska`() {
        assertEquals(
            TrimService.SEEK_FUDGE_SEC,
            TrimService.seekFudgeSec(30.0, probe("matroska,webm")), 1e-9,
        )
    }

    @Test
    fun `negative start_time increases fudge`() {
        // AAC priming：start_time=-0.023 → fudge = 0.131 + 0.023
        assertEquals(
            TrimService.SEEK_FUDGE_SEC + 0.023,
            TrimService.seekFudgeSec(30.0, probe("matroska", startTime = -0.023)), 1e-9,
        )
    }

    @Test
    fun `positive start_time does not shrink fudge`() {
        assertEquals(
            TrimService.SEEK_FUDGE_SEC,
            TrimService.seekFudgeSec(30.0, probe("matroska", startTime = 0.05)), 1e-9,
        )
    }

    @Test
    fun `unknown start_time treated as zero`() {
        assertEquals(
            TrimService.SEEK_FUDGE_SEC,
            TrimService.seekFudgeSec(30.0, probe("matroska", startTime = null)), 1e-9,
        )
    }

    @Test
    fun `unknown hasBFrames treated as B-frames (old cache rows)`() {
        assertEquals(
            TrimService.SEEK_FUDGE_SEC,
            TrimService.seekFudgeSec(30.0, probe("matroska", hasBFrames = null)), 1e-9,
        )
    }

    @Test
    fun `no B-frames mkv gets no fudge`() {
        assertEquals(0.0, TrimService.seekFudgeSec(30.0, probe("matroska", hasBFrames = 0)), 1e-9)
    }

    @Test
    fun `mp4 input gets no fudge`() {
        assertEquals(0.0, TrimService.seekFudgeSec(30.0, probe("mov,mp4,m4a,3gp,3g2,mj2")), 1e-9)
    }

    @Test
    fun `start at file head gets no fudge`() {
        assertEquals(0.0, TrimService.seekFudgeSec(0.0, probe("matroska")), 1e-9)
        assertEquals(0.0, TrimService.seekFudgeSec(0.001, probe("matroska")), 1e-9)
    }

    @Test
    fun `no video stream gets no fudge`() {
        assertEquals(0.0, TrimService.seekFudgeSec(30.0, probe("matroska", video = false)), 1e-9)
    }

    @Test
    fun `cover-only video track not treated as main video`() {
        val probe = ProbeResult(
            probeOk = true, durationSec = 60.0, formatName = "matroska", startTimeSec = 0.0,
            streams = listOf(
                StreamInfo( // 仅封面轨（attachedPic），无真正视频流
                    index = 1, codecType = "video", codecName = "mjpeg",
                    language = null, title = null, channels = null, channelLayout = null,
                    width = 600, height = 800, attachedPic = true, hasBFrames = 2,
                )
            ),
        )
        assertEquals(0.0, TrimService.seekFudgeSec(30.0, probe), 1e-9)
    }

    @Test
    fun `fudge value is 3 over 23 rounded up to millis`() {
        // 3/23 ≈ 0.130435s；取 0.131 留舍入余量（不能用 0.130）
        assertEquals(0.131, TrimService.SEEK_FUDGE_SEC, 1e-12)
    }
}
