package com.xixka.losslesstrim.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 实验性：MediaCodec 直解缩略图（"硬解10bit缩略图.txt"方案验证）。
 *
 * 背景：项目原先通过 FFmpeg `-hwaccel mediacodec` 间接使用硬解，10-bit HEVC
 * 输出颜色不可靠（copyback 链路丢 range/matrix 元数据），只能整体回退软解。
 * Android 官方路线其实是：
 *
 *     HEVC Main10 → MediaCodec（ByteBuffer/Image 输出）→ P010
 *       → 正确处理 Y/UV plane + rowStride + crop → 10bit→8bit → YUV→RGB → Bitmap
 *
 * 且 CDD 要求：声明支持 COLOR_FormatYUVP010 的解码器必须支持 CPU 读取 P010；
 * 支持高位深（9+ bit）的解码器按应用请求须支持 8-bit 等效输出；
 * API 31 起可用 KEY_COLOR_TRANSFER_REQUEST 请求 HDR→SDR tone-map。
 *
 * 本类即按此实现：查询解码器能力（P010 / YUV420Flexible）→ HDR 输入请求
 * tone-map → 同步解码到目标帧 → 读取 Image 平面（含 crop/stride/旋转）→
 * YuvToRgb 转出小图 Bitmap。任何一步失败返回 null，由 ThumbStore 回退
 * 到 FFmpeg 既有链路——只有 MediaCodec 不支持/解码失败/P010 输出异常/
 * 厂商 codec bug 才 fallback，正是 txt 建议的合理架构。
 *
 * 附带优势：MediaExtractor 支持 content:// SAF uri，FFmpeg 软解路径拿不到
 * 直路径的云盘/受限文件也能抽帧。
 *
 * 诊断：每次尝试（成功/失败都）记录到环形缓冲（snapshotAttempts），
 * 设备是否支持 P010、tone-map 是否被采纳、实际输出色彩格式一目了然，
 * 供设置页"导出诊断日志"评估该实验能不能行。
 */
object MediaCodecThumb {

    /** 单次抽帧总预算：厂商 codec 卡死时的硬上限 */
    private const val OVERALL_TIMEOUT_NS = 10_000_000_000L

    /** 单次出/入队等待 */
    private const val DEQUEUE_TIMEOUT_US = 10_000L

    /** 输出帧数上限（30fps × 80s，正常 GOP 远小于此） */
    private const val MAX_OUTPUT_FRAMES = 2400

    /** 喂入样本数上限 */
    private const val MAX_INPUT_SAMPLES = 6000

    /** 连续 getOutputImage 为 null 判定输出不可 CPU 读取（典型：Surface-only 私有格式） */
    private const val MAX_NULL_IMAGE = 8

    /** inputDone 后连续无进展的轮数上限（300 轮 ≈ 3s） */
    private const val MAX_STARVED_ROUNDS = 300

    /** 诊断环形缓冲容量 */
    private const val MAX_DIAG = 16

    /** P010 的 ImageFormat 数值（ImageFormat.YCBCR_P010，API 33 起公开；数值兼容旧版本） */
    private const val IMAGE_FORMAT_P010 = 0x2B

    // ---------------- 诊断 ----------------

    /** 单次直解尝试的现场记录（成功也记：实验需要知道能力面，不只是失败） */
    data class AttemptDiag(
        val at: Long,
        val uri: String,
        val timeMs: Long,
        val ok: Boolean,
        val decoder: String?,
        val mime: String,
        val hasP010Cap: Boolean,
        val hasYuvFlexCap: Boolean,
        val outColorFormat: Int,
        val imageFormat: Int,
        val toneMapRequested: Boolean,
        val toneMapApplied: Boolean,
        val manualHdr: Boolean,
        val decodedFrames: Int,
        val durationMs: Long,
        val error: String?,
    )

    private val attempts = ArrayList<AttemptDiag>(MAX_DIAG)

    /** 最近尝试快照（诊断导出用） */
    fun snapshotAttempts(): List<AttemptDiag> = synchronized(attempts) { ArrayList(attempts) }

    /** 最近一次尝试的紧凑描述（ThumbStore 失败记录引用） */
    @Volatile
    var lastDiag: String? = null
        private set

    private fun record(a: AttemptDiag) {
        synchronized(attempts) {
            attempts.add(a)
            while (attempts.size > MAX_DIAG) attempts.removeAt(0)
        }
    }

    /** 一次解码过程的可变状态（结束后汇成 AttemptDiag） */
    private class Run(val uri: String, val timeMs: Long) {
        var mime: String = ""
        var decoder: String? = null
        var hasP010Cap = false
        var hasYuvFlexCap = false
        var outColorFormat = -1
        var imageFormat = -1
        var toneMapRequested = false
        var toneMapApplied = false
        var manualHdr = false
        var decodedFrames = 0
        var error: String? = null
        var bitmap: Bitmap? = null
        var inputStd: ColorStd? = null
        var inputTransfer: ColorTransfer? = null
        var inputFullRange: Boolean? = null
    }

    private fun describe(run: Run, durMs: Long): String {
        val parts = mutableListOf<String>()
        parts.add(if (run.bitmap != null) "ok" else "fail")
        parts.add("decoder=${run.decoder ?: "(default)"}")
        if (run.mime.isNotEmpty()) parts.add("mime=${run.mime}")
        parts.add("outColorFormat=0x${run.outColorFormat.toString(16)}")
        parts.add("imageFormat=0x${run.imageFormat.toString(16)}")
        parts.add("toneMap=" + when {
            run.toneMapRequested && run.toneMapApplied -> "applied"
            run.toneMapRequested -> "rejected"
            else -> "n/a"
        })
        parts.add("manualHdr=${run.manualHdr}")
        parts.add("frames=${run.decodedFrames}")
        parts.add("${durMs}ms")
        run.error?.let { parts.add("err=$it") }
        return parts.joinToString(" ")
    }

    // ---------------- 对外入口 ----------------

    /**
     * 直解抽帧：解码 timeMs 处最近的一帧，缩到 maxPx 内返回 Bitmap；失败返回 null。
     * 阻塞调用（同步 MediaCodec），须在 IO 线程执行。
     */
    fun extract(context: Context, uri: Uri, timeMs: Long, maxPx: Int): Bitmap? {
        val t0 = System.nanoTime()
        val run = Run(uri.toString(), timeMs)
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        var heldIndex = -1
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(context, uri, null)

            // ---- 1. 选视频轨 ----
            var trackIdx = -1
            var trackFmt: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (m.startsWith("video/")) {
                    trackIdx = i; trackFmt = f; break
                }
            }
            if (trackIdx < 0 || trackFmt == null) {
                run.error = "no video track"
                return null
            }
            val mime = trackFmt.getString(MediaFormat.KEY_MIME)
            if (mime == null) {
                run.error = "null mime"
                return null
            }
            run.mime = mime
            extractor.selectTrack(trackIdx)

            // ---- 2. 轨道元数据：时长/帧率/旋转/色彩 ----
            val durationUs = if (trackFmt.containsKey(MediaFormat.KEY_DURATION))
                runCatching { trackFmt.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(-1L) else -1L
            val fps = if (trackFmt.containsKey(MediaFormat.KEY_FRAME_RATE))
                runCatching { trackFmt.getInteger(MediaFormat.KEY_FRAME_RATE) }.getOrNull()?.coerceAtLeast(1) ?: 30
            else 30
            val rotation = if (trackFmt.containsKey(MediaFormat.KEY_ROTATION))
                runCatching { trackFmt.getInteger(MediaFormat.KEY_ROTATION) }.getOrDefault(0) else 0

            val inTransferRaw = if (trackFmt.containsKey(MediaFormat.KEY_COLOR_TRANSFER))
                runCatching { trackFmt.getInteger(MediaFormat.KEY_COLOR_TRANSFER) }.getOrDefault(0) else 0
            val isHdrInput = inTransferRaw == MediaFormat.COLOR_TRANSFER_ST2084 ||
                    inTransferRaw == MediaFormat.COLOR_TRANSFER_HLG
            run.inputTransfer = when (inTransferRaw) {
                MediaFormat.COLOR_TRANSFER_ST2084 -> ColorTransfer.PQ
                MediaFormat.COLOR_TRANSFER_HLG -> ColorTransfer.HLG
                else -> null
            }
            if (trackFmt.containsKey(MediaFormat.KEY_COLOR_STANDARD)) {
                run.inputStd = runCatching { mapStd(trackFmt.getInteger(MediaFormat.KEY_COLOR_STANDARD)) }.getOrNull()
            }
            if (trackFmt.containsKey(MediaFormat.KEY_COLOR_RANGE)) {
                run.inputFullRange = runCatching { trackFmt.getInteger(MediaFormat.KEY_COLOR_RANGE) }.getOrNull() ==
                        MediaFormat.COLOR_RANGE_FULL
            }

            // ---- 3. 定位目标帧（PREVIOUS_SYNC 起步，向前解码到目标）----
            var targetUs = (timeMs * 1000L).coerceAtLeast(0L)
            if (durationUs > 0) targetUs = targetUs.coerceAtMost((durationUs - 20_000L).coerceAtLeast(0L))
            val tolUs = 500_000L / fps
            extractor.seekTo(targetUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            // ---- 4. 按能力挑解码器（txt：查询 → 支持 Main10？→ 支持 P010？→ 8-bit？）----
            val pick = pickDecoder(mime)
            run.decoder = pick?.name
            run.hasP010Cap = pick?.hasP010 == true
            run.hasYuvFlexCap = pick?.hasYuvFlex == true
            codec = runCatching {
                if (pick != null) MediaCodec.createByCodecName(pick.name)
                else MediaCodec.createDecoderByType(mime)
            }.getOrNull()
            if (codec == null) {
                run.error = "cannot create decoder"
                return null
            }

            // ---- 5. 配置：HDR 输入请求 tone-map 到 SDR（API 31+，codec 可忽略）----
            if (Build.VERSION.SDK_INT >= 31 && isHdrInput) {
                trackFmt.setInteger(
                    MediaFormat.KEY_COLOR_TRANSFER_REQUEST,
                    MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                )
                run.toneMapRequested = true
            }
            codec.configure(trackFmt, null, null, 0)
            codec.start()

            // ---- 6. 解码循环 ----
            // 滞后一帧留存策略：目标之前的帧只记最后一个（Image 在
            // releaseOutputBuffer 前持续有效，零拷贝）；命中目标帧即停。
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var inputSamples = 0
            var nullImages = 0
            var starvedRounds = 0
            val deadline = System.nanoTime() + OVERALL_TIMEOUT_NS

            while (System.nanoTime() < deadline) {
                var progressed = false
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inIdx >= 0) {
                        progressed = true
                        val buf = codec.getInputBuffer(inIdx)
                        val size = if (buf != null) extractor.readSampleData(buf, 0) else -1
                        inputSamples++
                        if (buf == null || size < 0 || inputSamples > MAX_INPUT_SAMPLES) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> progressed = true
                    outIdx >= 0 -> {
                        progressed = true
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        val img: Image? =
                            if (info.size > 0) runCatching { codec.getOutputImage(outIdx) }.getOrNull() else null
                        if (info.size > 0 && img == null) {
                            nullImages++
                            if (nullImages >= MAX_NULL_IMAGE) {
                                run.error = run.error ?: "output frames not CPU-readable via Image (x$nullImages)"
                                codec.releaseOutputBuffer(outIdx, false)
                                break
                            }
                        } else if (img != null) {
                            nullImages = 0
                            run.decodedFrames++
                        }
                        val reachTarget = img != null && info.presentationTimeUs >= targetUs - tolUs
                        when {
                            reachTarget -> {
                                if (heldIndex >= 0) codec.releaseOutputBuffer(heldIndex, false)
                                heldIndex = outIdx
                                break
                            }
                            img != null -> {
                                // 目标之前的帧：留存（含 EOS 帧 = 流末最后一帧）
                                if (heldIndex >= 0) codec.releaseOutputBuffer(heldIndex, false)
                                heldIndex = outIdx
                            }
                            else -> codec.releaseOutputBuffer(outIdx, false)
                        }
                        if (eos || run.decodedFrames >= MAX_OUTPUT_FRAMES) break
                    }
                }
                if (inputDone && !progressed) {
                    starvedRounds++
                    if (starvedRounds > MAX_STARVED_ROUNDS) {
                        run.error = run.error ?: "decode stalled"
                        break
                    }
                } else {
                    starvedRounds = 0
                }
            }

            // ---- 7. 统一转换点：held buffer 未 release，Image 仍有效 ----
            if (run.bitmap == null && heldIndex >= 0) {
                runCatching {
                    val heldImg = codec?.getOutputImage(heldIndex)
                    if (heldImg != null) {
                        run.bitmap = convert(codec!!, heldImg, rotation, maxPx, run)
                    } else {
                        run.error = run.error ?: "held frame not readable"
                    }
                }.onFailure {
                    run.error = run.error ?: "convert failed: ${it.javaClass.simpleName}: ${it.message}"
                }
            }
            if (run.bitmap == null && run.error == null) {
                run.error = if (run.decodedFrames == 0) "no output frame (decoder produced nothing)"
                else "no frame at/after target"
            }
        } catch (e: Throwable) {
            run.error = "${e.javaClass.simpleName}: ${e.message}"
        } finally {
            if (heldIndex >= 0) {
                runCatching { codec?.releaseOutputBuffer(heldIndex, false) }
            }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor?.release() }
        }

        val durMs = (System.nanoTime() - t0) / 1_000_000
        record(AttemptDiag(
            at = System.currentTimeMillis(),
            uri = run.uri.takeLast(96),
            timeMs = run.timeMs,
            ok = run.bitmap != null,
            decoder = run.decoder,
            mime = run.mime,
            hasP010Cap = run.hasP010Cap,
            hasYuvFlexCap = run.hasYuvFlexCap,
            outColorFormat = run.outColorFormat,
            imageFormat = run.imageFormat,
            toneMapRequested = run.toneMapRequested,
            toneMapApplied = run.toneMapApplied,
            manualHdr = run.manualHdr,
            decodedFrames = run.decodedFrames,
            durationMs = durMs,
            error = run.error,
        ))
        lastDiag = describe(run, durMs)
        return run.bitmap
    }

    // ---------------- 解码器选择 ----------------

    private data class DecoderPick(val name: String, val hasP010: Boolean, val hasYuvFlex: Boolean)

    /**
     * 在 REGULAR_CODECS 里找 CPU 可读输出（P010 或 YUV420Flexible）的解码器，
     * 硬解优先、P010 能力加分。找不到返回 null（由调用方退 createDecoderByType，
     * 让 framework 自选，输出形态交给运行时检查——不预设"10bit=不支持"）。
     */
    private fun pickDecoder(mime: String): DecoderPick? = runCatching {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        var best: DecoderPick? = null
        var bestScore = -1
        for (info in list.codecInfos) {
            if (info.isEncoder) continue
            val types = runCatching { info.supportedTypes }.getOrNull() ?: continue
            if (types.none { it.equals(mime, ignoreCase = true) }) continue
            val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull() ?: continue
            val fmts = caps.colorFormats
            val hasP010 = fmts.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010)
            val hasYuvFlex = fmts.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            if (!hasP010 && !hasYuvFlex) continue
            val hw = if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated else isLikelyHwName(info.name)
            val score = (if (hw) 4 else 0) + (if (hasP010) 2 else 0) + (if (hasYuvFlex) 1 else 0)
            if (score > bestScore) {
                bestScore = score
                best = DecoderPick(info.name, hasP010, hasYuvFlex)
            }
        }
        best
    }.getOrNull()

    /** API < 29 无 isHardwareAccelerated：按名字排除已知软解（c2.android.* / OMX.google.*） */
    private fun isLikelyHwName(name: String): Boolean {
        val n = name.lowercase()
        return !(n.startsWith("c2.android") || n.startsWith("c2.soft") ||
                n.startsWith("omx.google") || n.contains("google."))
    }

    // ---------------- 像素转换 ----------------

    private fun mapStd(v: Int): ColorStd? = when (v) {
        MediaFormat.COLOR_STANDARD_BT2020 -> ColorStd.BT2020
        MediaFormat.COLOR_STANDARD_BT601_NTSC, MediaFormat.COLOR_STANDARD_BT601_PAL -> ColorStd.BT601
        MediaFormat.COLOR_STANDARD_BT709 -> ColorStd.BT709
        else -> null
    }

    /**
     * Image → Bitmap：处理 crop/rowStride/pixelStride、P010 16-bit 采样、
     * 半平面（NV12/P010，2 plane）与平面（I420，3 plane）两种布局、旋转、
     * 最近邻降采样到 maxPx。所有颜色元数据从 codec.outputFormat 现读
     * （configure 后检查实际输出——不假设各厂商行为一致）。
     */
    private fun convert(
        codec: MediaCodec,
        img: Image,
        rotationDeg: Int,
        maxPx: Int,
        run: Run,
    ): Bitmap? {
        val of = codec.outputFormat
        val crop = runCatching { img.cropRect }.getOrNull() ?: Rect(0, 0, img.width, img.height)
        val srcW = crop.width()
        val srcH = crop.height()
        if (srcW <= 0 || srcH <= 0) {
            run.error = run.error ?: "empty crop rect"
            return null
        }

        run.imageFormat = img.format
        run.outColorFormat = if (of.containsKey(MediaFormat.KEY_COLOR_FORMAT))
            runCatching { of.getInteger(MediaFormat.KEY_COLOR_FORMAT) }.getOrDefault(-1) else -1

        // P010 判定：输出 color format / Image format 双保险（常量数值均兼容旧 API）
        val isP010 = run.outColorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010 ||
                img.format == IMAGE_FORMAT_P010 ||
                (Build.VERSION.SDK_INT >= 33 && img.format == ImageFormat.YCBCR_P010)

        // 传递函数：输出未声明时回退输入标记（防 codec 吞元数据把 HDR 当 SDR 转）
        val transfer = if (of.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
            when (runCatching { of.getInteger(MediaFormat.KEY_COLOR_TRANSFER) }.getOrDefault(0)) {
                MediaFormat.COLOR_TRANSFER_ST2084 -> ColorTransfer.PQ
                MediaFormat.COLOR_TRANSFER_HLG -> ColorTransfer.HLG
                else -> ColorTransfer.SDR
            }
        } else {
            run.inputTransfer ?: ColorTransfer.SDR
        }
        run.toneMapApplied = run.toneMapRequested && transfer == ColorTransfer.SDR
        run.manualHdr = transfer != ColorTransfer.SDR

        // 矩阵：输出 → 输入 → 启发式（HDR 默认 BT.2020，SDR 默认 BT.709）
        val std = (if (of.containsKey(MediaFormat.KEY_COLOR_STANDARD))
            runCatching { mapStd(of.getInteger(MediaFormat.KEY_COLOR_STANDARD)) }.getOrNull() else null)
            ?: run.inputStd
            ?: if (transfer != ColorTransfer.SDR) ColorStd.BT2020 else ColorStd.BT709

        // 范围：输出 → 输入 → 有限范围（视频几乎全是 tv）
        val rangeRaw = if (of.containsKey(MediaFormat.KEY_COLOR_RANGE))
            runCatching { of.getInteger(MediaFormat.KEY_COLOR_RANGE) }.getOrDefault(MediaFormat.COLOR_RANGE_LIMITED)
        else MediaFormat.COLOR_RANGE_LIMITED
        val fullRange = if (rangeRaw == MediaFormat.COLOR_RANGE_FULL) true
        else if (rangeRaw == MediaFormat.COLOR_RANGE_LIMITED) false
        else run.inputFullRange ?: false

        // 旋转与输出尺寸（与 ffmpeg scale='min(maxPx,iw)':-1 + autorotate 语义对齐）
        val rot = (((rotationDeg % 360) + 360) % 360) / 90 * 90
        val rotW = if (rot == 90 || rot == 270) srcH else srcW
        val rotH = if (rot == 90 || rot == 270) srcW else srcH
        val outW = maxPx.coerceAtLeast(1).coerceAtMost(rotW)
        val outH = (((outW.toLong() * rotH) + rotW / 2) / rotW).toInt().coerceIn(1, rotH)

        val planes = img.planes
        if (planes.size < 2) {
            run.error = run.error ?: "unexpected plane count ${planes.size}"
            return null
        }
        val planar = planes.size >= 3
        val sampleW = if (isP010) 2 else 1   // 单样本字节数（P010 = 16-bit）

        val yP = planes[0]
        val yBuf = yP.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val yRs = yP.rowStride
        val yPs = yP.pixelStride.coerceAtLeast(sampleW)

        val c1P = planes[1]
        val c1Buf = c1P.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val c1Rs = c1P.rowStride
        // 半平面：pixelStride 是相邻 Cb 样本的字节距（P010=4、NV12=2），兼作 CbCr 对步进
        val c1Ps = c1P.pixelStride.coerceAtLeast(2 * sampleW)
        val c2P = if (planar) planes[2] else null
        val c2Buf = c2P?.buffer?.duplicate()?.order(ByteOrder.LITTLE_ENDIAN)
        val c2Rs = c2P?.rowStride ?: 0
        val c2Ps = c2P?.pixelStride?.coerceAtLeast(sampleW) ?: 0

        // P010 是 little-endian 16-bit；Image 平面 buffer 的 byte order 不保证，显式设置
        fun read8(b: ByteBuffer, off: Int): Int =
            if (isP010) YuvToRgb.p010To8(b.getShort(off).toInt()) else b.get(off).toInt() and 0xFF

        val conv = RgbConverter(std, fullRange, transfer)
        val px = IntArray(outW * outH)
        var w = 0
        for (dy in 0 until outH) {
            val v = (((dy.toLong() * rotH) + outH / 2) / outH).toInt().coerceIn(0, rotH - 1)
            for (dx in 0 until outW) {
                val u = (((dx.toLong() * rotW) + outW / 2) / outW).toInt().coerceIn(0, rotW - 1)
                // 目标像素（已旋转坐标系）→ 源坐标
                var sx = 0
                var sy = 0
                when (rot) {
                    90 -> { sx = v; sy = srcH - 1 - u }
                    180 -> { sx = srcW - 1 - u; sy = srcH - 1 - v }
                    270 -> { sx = srcW - 1 - v; sy = u }
                    else -> { sx = u; sy = v }
                }
                val yOff = ((crop.top + sy).toLong() * yRs + (crop.left + sx).toLong() * yPs).toInt()
                val y8 = read8(yBuf, yOff)
                val cc = (crop.left + sx) / 2
                val rr = (crop.top + sy) / 2
                val cb8: Int
                val cr8: Int
                if (planar) {
                    cb8 = read8(c1Buf, (rr.toLong() * c1Rs + cc.toLong() * c1Ps).toInt())
                    cr8 = read8(c2Buf!!, (rr.toLong() * c2Rs + cc.toLong() * c2Ps).toInt())
                } else {
                    val o = (rr.toLong() * c1Rs + cc.toLong() * c1Ps).toInt()
                    cb8 = read8(c1Buf, o)
                    cr8 = read8(c1Buf, o + sampleW)
                }
                px[w++] = (0xFF shl 24) or conv.rgb(y8, cb8, cr8)
            }
        }
        val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, outW, 0, 0, outW, outH)
        return bmp
    }
}
