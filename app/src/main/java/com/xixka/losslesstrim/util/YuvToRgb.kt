package com.xixka.losslesstrim.util

import kotlin.math.exp
import kotlin.math.pow

/**
 * 色彩标准（YUV→RGB 矩阵系数 Kr/Kb）。
 * 对应 MediaFormat.KEY_COLOR_STANDARD 的语义（映射见 MediaCodecThumb.mapStd）。
 */
enum class ColorStd(val kr: Double, val kb: Double) {
    BT601(0.299, 0.114),
    BT709(0.2126, 0.0722),
    BT2020(0.2627, 0.0593),
}

/** 输出传递函数：SDR 直通；PQ/HLG 需逆 OETF + 增益 + sRGB 伽马（手动 HDR→SDR 兜底） */
enum class ColorTransfer { SDR, PQ, HLG }

/**
 * 纯 Kotlin YUV→RGB 颜色转换（无 Android 依赖，可 JVM 单测）。
 *
 * 服务于实验性"MediaCodec 直解缩略图"（MediaCodecThumb）：
 * Android CDD 要求声明 COLOR_FormatYUVP010 的解码器必须支持 CPU 读取 P010
 * （ImageReader / Image / ByteBuffer），本模块负责 P010 10-bit → 8-bit、
 * YUV 有限/完整范围 → RGB、BT.601/709/2020 矩阵选择，以及 HDR（PQ/HLG）
 * 的逆 OETF + 启发式 SDR 映射——即"硬解10bit缩略图.txt"里第一种方案
 * （MediaCodec 硬解 → P010 → 自己转 8-bit Bitmap）的像素级实现。
 *
 * 关键正确性来源（与 FFmpeg 软解链路等价）：
 * - 有限范围（tv）Y∈[16,235]/C∈[16,240] → full range 转换（255/219、255/224），
 *   对应 ffmpeg scale 滤镜的 out_range=pc；
 * - 矩阵按 KEY_COLOR_STANDARD 选择而非写死 bt601（vendor 常见错标）。
 */
object YuvToRgb {

    // ---- ST 2084 (PQ) 常数 ----
    private const val PQ_M1 = 0.1593017578125
    private const val PQ_M2 = 78.84375
    private const val PQ_C1 = 0.8359375
    private const val PQ_C2 = 18.8515625
    private const val PQ_C3 = 18.6875

    // ---- ARIB STD-B67 (HLG) 常数 ----
    private const val HLG_A = 0.17883277
    private const val HLG_B = 0.28466892
    private const val HLG_C = 0.55991073

    /**
     * 手动 HDR→SDR 的线性增益（启发式，仅当解码器拒绝 tone-map 请求时兜底）：
     * PQ 线性域 1.0 = 10000 nit，典型 HDR 内容峰值 ~1000-4000 nit，
     * 增益 10 把 ~1000 nit 映射到 SDR 白；HLG 线性域 1.0 ≈ 1000 nit，
     * 增益 3 对应 ~330 nit 白。缩略图场景够用（系统 tone-map 为主路径）。
     */
    const val PQ_SDR_GAIN = 10.0
    const val HLG_SDR_GAIN = 3.0

    /** P010 16-bit 样本（little-endian，高 10 位有效，低 6 位为 0）→ 8-bit */
    fun p010To8(v16: Int): Int = (v16 and 0xFFFF) ushr 8

    /** ST 2084 逆 OETF：PQ 编码 [0,1] → 线性 [0,1]（1.0 = 10000 nit） */
    fun invPQ(e: Double): Double {
        if (e <= 0.0) return 0.0
        if (e >= 1.0) return 1.0
        val ep = e.pow(1.0 / PQ_M2)
        val num = (ep - PQ_C1).coerceAtLeast(0.0)
        val den = PQ_C2 - PQ_C3 * ep
        if (den <= 0.0) return 1.0
        return (num / den).pow(1.0 / PQ_M1)
    }

    /** ARIB STD-B67（HLG）逆 OETF：信号 [0,1] → 线性 [0,1]（1.0 ≈ 1000 nit） */
    fun invHLG(e: Double): Double {
        if (e <= 0.0) return 0.0
        return if (e <= 0.5) e * e / 3.0 else (exp((e - HLG_C) / HLG_A) + HLG_B) / 12.0
    }

    /** sRGB OETF：线性 [0,1] → 显示编码 [0,1] */
    fun srgbOETF(l: Double): Double = when {
        l <= 0.0 -> 0.0
        l >= 1.0 -> 1.0
        l <= 0.0031308 -> 12.92 * l
        else -> 1.055 * l.pow(1.0 / 2.4) - 0.055
    }

    /** HDR→SDR 启发式：逆 OETF → 增益 → sRGB 伽马（夹紧到 [0,1]） */
    fun hdrToSdr(e: Double, transfer: ColorTransfer): Double {
        val lin = when (transfer) {
            ColorTransfer.PQ -> invPQ(e)
            ColorTransfer.HLG -> invHLG(e)
            ColorTransfer.SDR -> return e
        }
        val gained = lin * (if (transfer == ColorTransfer.PQ) PQ_SDR_GAIN else HLG_SDR_GAIN)
        return srgbOETF(gained.coerceIn(0.0, 1.0))
    }
}

/**
 * YUV→RGB 像素转换器：矩阵系数预计算 + 可选 HDR 后处理 LUT（256 项，索引即 8-bit 值）。
 * 热路径每像素仅 ~6 次浮点乘加 + 最多 3 次 LUT 查表。
 */
class RgbConverter(std: ColorStd, fullRange: Boolean, transfer: ColorTransfer) {

    private val yOff: Int
    private val yScale: Float
    private val cScale: Float
    private val rCr: Float
    private val gCb: Float
    private val gCr: Float
    private val bCb: Float

    /** HDR 后处理 LUT；null = SDR 直通 */
    private val toneLut: IntArray?

    init {
        if (fullRange) {
            yOff = 0; yScale = 1f; cScale = 1f
        } else {
            // 有限范围（tv）：Y 16-235 / C 16-240 → full（pc），等价 ffmpeg out_range=pc
            yOff = 16; yScale = 255f / 219f; cScale = 255f / 224f
        }
        val kr = std.kr
        val kb = std.kb
        val kg = 1.0 - kr - kb
        rCr = (2.0 * (1.0 - kr)).toFloat()
        bCb = (2.0 * (1.0 - kb)).toFloat()
        gCb = (-2.0 * (1.0 - kb) * kb / kg).toFloat()
        gCr = (-2.0 * (1.0 - kr) * kr / kg).toFloat()
        toneLut = if (transfer == ColorTransfer.SDR) null else IntArray(256) { i ->
            (YuvToRgb.hdrToSdr(i / 255.0, transfer) * 255.0 + 0.5).toInt()
        }
    }

    /**
     * 单像素转换。y/cb/cr 均为 8-bit 值（P010 已先行 ushr 8）。
     * 返回 0xRRGGBB（不含 alpha）。
     */
    fun rgb(y: Int, cb: Int, cr: Int): Int {
        val yn = (y - yOff) * yScale
        val cbn = (cb - 128) * cScale
        val crn = (cr - 128) * cScale
        var r = (yn + crn * rCr).toInt().coerceIn(0, 255)
        var g = (yn + cbn * gCb + crn * gCr).toInt().coerceIn(0, 255)
        var b = (yn + cbn * bCb).toInt().coerceIn(0, 255)
        val lut = toneLut
        if (lut != null) {
            r = lut[r]; g = lut[g]; b = lut[b]
        }
        return (r shl 16) or (g shl 8) or b
    }
}
