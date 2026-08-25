package com.xixka.losslesstrim

import com.xixka.losslesstrim.util.ColorStd
import com.xixka.losslesstrim.util.ColorTransfer
import com.xixka.losslesstrim.util.RgbConverter
import com.xixka.losslesstrim.util.YuvToRgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MediaCodec 直解缩略图实验（MediaCodecThumb/YuvToRgb）的颜色数学单测：
 * P010 采样截取、有限/完整范围、BT.601/709/2020 矩阵、PQ/HLG 逆 OETF、
 * HDR→SDR 启发式。锚点取自 ITU 规范的标准色（黑/白/灰/基色），
 * 与 FFmpeg 软解链路（out_range=pc + 对应矩阵）等价性靠这些锚点保证。
 */
class YuvToRgbTest {

    private fun r(rgb: Int) = (rgb shr 16) and 0xFF
    private fun g(rgb: Int) = (rgb shr 8) and 0xFF
    private fun b(rgb: Int) = rgb and 0xFF

    // ---------- P010 ----------

    @Test
    fun `p010 sample takes high 10 bits`() {
        assertEquals(0xFF, YuvToRgb.p010To8(0xFFC0))
        assertEquals(0x00, YuvToRgb.p010To8(0x0000))
        assertEquals(0x04, YuvToRgb.p010To8(0x0400))
        assertEquals(0x80, YuvToRgb.p010To8(0x8000))
        // getShort 负值（>0x7FFF 的 16-bit）也须按无符号处理
        assertEquals(0xFF, YuvToRgb.p010To8(-0x40))     // 0xFFC0
        assertEquals(0x7F, YuvToRgb.p010To8(0x7FC0))
    }

    // ---------- 有限/完整范围 ----------

    @Test
    fun `limited range black and white bt709`() {
        val c = RgbConverter(ColorStd.BT709, fullRange = false, ColorTransfer.SDR)
        assertEquals(0x000000, c.rgb(16, 128, 128))
        // 219*(255f/219f) 浮点下可能是 254.99..255.00，容忍截断误差
        val white = c.rgb(235, 128, 128)
        assertTrue(
            "white=${Integer.toHexString(white)}",
            r(white) in 254..255 && g(white) in 254..255 && b(white) in 254..255,
        )
    }

    @Test
    fun `limited range gray bt601`() {
        val c = RgbConverter(ColorStd.BT601, fullRange = false, ColorTransfer.SDR)
        val rgb = c.rgb(126, 128, 128)
        // (126-16)*255/219 ≈ 128，三通道一致
        assertEquals(r(rgb), g(rgb))
        assertEquals(g(rgb), b(rgb))
        assertTrue("gray=${r(rgb)}", r(rgb) in 127..129)
    }

    @Test
    fun `full range extremes`() {
        val c = RgbConverter(ColorStd.BT601, fullRange = true, ColorTransfer.SDR)
        assertEquals(0x000000, c.rgb(0, 128, 128))
        assertEquals(0xFFFFFF, c.rgb(255, 128, 128))
    }

    // ---------- 矩阵锚点（ITU-R 标准基色）----------

    @Test
    fun `bt601 red primary`() {
        val c = RgbConverter(ColorStd.BT601, fullRange = false, ColorTransfer.SDR)
        val rgb = c.rgb(81, 90, 240)
        assertTrue("r=${r(rgb)} g=${g(rgb)} b=${b(rgb)}", r(rgb) >= 250 && g(rgb) <= 5 && b(rgb) <= 5)
    }

    @Test
    fun `bt601 green primary`() {
        val c = RgbConverter(ColorStd.BT601, fullRange = false, ColorTransfer.SDR)
        val rgb = c.rgb(150, 44, 21)
        assertTrue("r=${r(rgb)} g=${g(rgb)} b=${b(rgb)}", g(rgb) >= 250 && r(rgb) <= 5 && b(rgb) <= 5)
    }

    @Test
    fun `bt709 green primary`() {
        val c = RgbConverter(ColorStd.BT709, fullRange = false, ColorTransfer.SDR)
        // BT.709 纯绿：Y≈173 Cb≈42 Cr≈26
        val rgb = c.rgb(173, 42, 26)
        assertTrue("r=${r(rgb)} g=${g(rgb)} b=${b(rgb)}", g(rgb) >= 248 && r(rgb) <= 8 && b(rgb) <= 8)
    }

    @Test
    fun `standards differ for same input`() {
        val c601 = RgbConverter(ColorStd.BT601, fullRange = false, ColorTransfer.SDR)
        val c709 = RgbConverter(ColorStd.BT709, fullRange = false, ColorTransfer.SDR)
        // 色度偏离中性时两矩阵输出必然不同——这正是 hwaccel copyback 元数据
        // 丢失后写死 bt601 会偏色的原因
        assertTrue(c601.rgb(126, 160, 68) != c709.rgb(126, 160, 68))
    }

    // ---------- PQ / HLG / sRGB ----------

    @Test
    fun `inverse pq anchors`() {
        assertEquals(0.0, YuvToRgb.invPQ(0.0), 1e-9)
        assertEquals(1.0, YuvToRgb.invPQ(1.0), 1e-9)
        // 0.5 PQ 信号 ≈ 92 nit ≈ 0.0092 线性
        assertEquals(0.0092, YuvToRgb.invPQ(0.5), 0.001)
        assertTrue(YuvToRgb.invPQ(0.7) > YuvToRgb.invPQ(0.5))
    }

    @Test
    fun `inverse hlg anchors`() {
        assertEquals(0.0, YuvToRgb.invHLG(0.0), 1e-9)
        // 信号 0.5 恰在分段点：Y = 0.25/3 = 1/12
        assertEquals(1.0 / 12.0, YuvToRgb.invHLG(0.5), 1e-6)
        assertEquals(1.0, YuvToRgb.invHLG(1.0), 0.01)
        assertTrue(YuvToRgb.invHLG(0.75) > YuvToRgb.invHLG(0.25))
    }

    @Test
    fun `srgb oetf anchors`() {
        assertEquals(0.0, YuvToRgb.srgbOETF(0.0), 1e-9)
        assertEquals(1.0, YuvToRgb.srgbOETF(1.0), 1e-9)
        assertEquals(0.7354, YuvToRgb.srgbOETF(0.5), 0.01)
    }

    // ---------- HDR 启发式兜底 ----------

    @Test
    fun `pq converter saturates white and stays monotonic`() {
        val pq = RgbConverter(ColorStd.BT2020, fullRange = false, ColorTransfer.PQ)
        // 有限范围白（Y=235）→ PQ E=1 → 10000nit → 增益后饱和
        assertEquals(255, (pq.rgb(235, 128, 128) shr 16) and 0xFF)
        val dark = (pq.rgb(80, 128, 128) shr 16) and 0xFF
        val bright = (pq.rgb(160, 128, 128) shr 16) and 0xFF
        assertTrue("dark=$dark bright=$bright", dark < bright)
    }

    @Test
    fun `hlg converter saturates white and stays monotonic`() {
        val hlg = RgbConverter(ColorStd.BT2020, fullRange = false, ColorTransfer.HLG)
        assertEquals(255, (hlg.rgb(235, 128, 128) shr 16) and 0xFF)
        val dark = (hlg.rgb(80, 128, 128) shr 16) and 0xFF
        val bright = (hlg.rgb(160, 128, 128) shr 16) and 0xFF
        assertTrue("dark=$dark bright=$bright", dark < bright)
    }

    @Test
    fun `hdr converters keep chroma away from extremes at mid luma`() {
        // 中等亮度的彩色（Y=140, 蓝偏色度）不应被 LUT 推到 0/255 深饱和
        val pq = RgbConverter(ColorStd.BT2020, fullRange = false, ColorTransfer.PQ)
        val rgb = pq.rgb(140, 160, 110)
        val rr = r(rgb)
        assertTrue("r=$rr", rr in 1..254)
    }
}
