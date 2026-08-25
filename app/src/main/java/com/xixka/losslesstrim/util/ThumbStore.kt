package com.xixka.losslesstrim.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.LruCache
import com.antonkarpenko.ffmpegkit.FFmpegKit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局缩略图 / 抽帧缓存：内存 LruCache + 磁盘 JPEG 双层缓存。
 *
 * 背景：旧实现里每个列表项直接 MediaMetadataRetriever 按原始分辨率抽帧，
 * Bitmap 由各自组合函数持有、无任何缓存：
 *  1) 4K 视频单帧原始解码约 33MB，浏览列表 / 拖动切点时内存只增不减 → OOM 闪退；
 *  2) 无缓存，页面切换、返回列表全部重新抽帧（"每次进入页面都重新加载缩略图"）。
 *
 * 现策略：
 *  - 抽帧一律出小图（getScaledFrameAtTime / 解码后立即缩放并回收原图）；
 *  - Bitmap 所有权归 LruCache：条目被挤出时不手动 recycle（防止"被挤出但仍在显示"
 *    导致绘制崩溃），minSdk 26 起像素内存由 GC/NativeAllocationRegistry 及时回收；
 *  - 磁盘缓存保证冷启动再次进入也秒出，不用重新解码视频。
 */
object ThumbStore {

    /**
     * 硬解总开关（设置页"缩略图硬解"，AppViewModel 观察 settings 同步写入）。
     * 默认 false = 软解优先：颜色可靠（10-bit HEVC → 8-bit 正确转换），
     * 缩略图场景慢一点可接受。硬解快 5-10x 但 10-bit 输出颜色可能不可靠，
     * 由用户自行权衡开启，**不做 isBitmapHealthy 自动检测回退**——
     * stddev 检测会误杀极端合法画面（全单色背景/重彩动画），宁可交还选择权。
     */
    @Volatile
    var hwDecodeEnabled: Boolean = false

    /**
     * 实验性“MediaCodec 直解缩略图”开关（设置页，AppViewModel 观察 settings 同步写入）。
     * 开启后抽帧链路最前面插入 MediaCodecThumb 直解：10-bit HEVC 走 P010→8bit
     * CPU 转换（stride/crop/旋转/颜色矩阵自管）、HDR 尝试请求 tone-map；
     * 失败或不健康自动回退 FFmpeg 既有链路。用于验证“硬解10bit缩略图.txt”方案。
     */
    @Volatile
    var mcThumbEnabled: Boolean = false

    /**
     * 抽帧并发池：列表缩略图与交互预览分池，
     * 避免进分析页时预览排在列表抽帧后面干等（预览是用户盯着等的）。
     */
    private val listSemaphore = Semaphore(2)
    private val previewSemaphore = Semaphore(2)

    /** 内存缓存：maxMemory/8，限幅 [4MB, 32MB]（按 byteCount 计） */
    private val memCache = object : LruCache<String, Bitmap>(
        ((Runtime.getRuntime().maxMemory() / 8).toInt())
            .coerceIn(4 * 1024 * 1024, 32 * 1024 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /**
     * 失败哨兵：抽帧失败的 key 暂记 60s 内不重试，避免拖动切点时高频空转解码器；
     * **60s 后自动失效**——切回页面 / 重启应用时仍会尝试，让用户看到最新状态
     * （而不是永久卡"加载中…"）。永久失败会让 ffmpeg-kit 解码器问题导致的
     * 一次失败成为永久黑名单，掩盖诊断信息。
     */
    private const val FAILED_TTL_MS = 60_000L

    private val failed = object : LruCache<String, Long>(128) {
        override fun sizeOf(key: String, value: Long): Int = 1
    }

    /** 失败哨兵查询：过期视为未失败（移除并返回 false） */
    private fun isRecentlyFailed(key: String): Boolean {
        val ts = failed.get(key) ?: return false
        if (System.currentTimeMillis() - ts > FAILED_TTL_MS) {
            failed.remove(key)
            return false
        }
        return true
    }

    private fun markFailed(key: String) {
        failed.put(key, System.currentTimeMillis())
    }

    /**
     * MediaCodec 直解失败哨兵：同 key 60s 内不再重试（镜像 FAILED_TTL_MS 语义）。
     * 防坏设备上每张图都先卡一轮直解超时（最长 10s）才回退 ffmpeg。
     */
    private val mcFailed = object : LruCache<String, Long>(128) {
        override fun sizeOf(key: String, value: Long): Int = 1
    }

    private fun isRecentlyMcFailed(key: String): Boolean {
        val ts = mcFailed.get(key) ?: return false
        if (System.currentTimeMillis() - ts > FAILED_TTL_MS) {
            mcFailed.remove(key)
            return false
        }
        return true
    }

    private fun markMcFailed(key: String) {
        mcFailed.put(key, System.currentTimeMillis())
    }

    /** 磁盘缓存文件数上限（单张 JPEG 仅几十 KB，512 张约几十 MB） */
    private const val DISK_MAX_FILES = 512

    /** 磁盘写入计数（用于清理节流） */
    private val writeCounter = java.util.concurrent.atomic.AtomicInteger(0)

    private const val DISK_DIR_NAME = "thumbs"

    @Volatile
    private var diskDirCache: File? = null

    /**
     * 缓存键：同一文件不同时间点分开缓存（timeMs<=0 即首帧缩略图）。
     * identity（文件大小等身份串）非空时拼进键：剪辑覆盖后 uri 不变而内容已换，
     * 不带身份的键会永远命中旧缓存（切了片头封面还是老画面、切点预览抽旧帧）。
     */
    fun keyOf(uri: Uri, timeMs: Long = 0L, identity: String? = null): String {
        val base = if (timeMs <= 0L) uri.toString() else "$uri@$timeMs"
        return if (identity.isNullOrEmpty()) base else "$base|$identity"
    }

    /** 同步探测：命中内存缓存立即返回（页面返回时秒显示，不闪占位符） */
    fun peek(key: String): Bitmap? = memCache.get(key)

    /**
     * 清空全部缓存：内存 LruCache + 磁盘 thumbs/ + cacheDir/ffmpeg-thumb/ +
     * failed 哨兵。修复前的花屏 JPEG 会被删掉，下次进入页面重新抽帧。
     *
     * 返回删除的磁盘字节数 + 文件数（不含内存条目数——内存条目会被 LruCache
     * evict 自动 GC，数量无意义）。Room 探测缓存不走这里，由 ProbeStore.clearAll
     * 单独清；Scanner 的 probeCache（内存 L1）会在进程下次重建时自然空。
     */
    fun clearAll(context: Context): ClearResult {
        memCache.evictAll()
        failed.evictAll()
        mcFailed.evictAll()
        diskDirCache = null     // 强制下次 diskDir() 重新解析（旧的已删）
        synchronized(recentFailures) { recentFailures.clear() }
        var bytes = 0L
        var files = 0
        // thumbs/ 与 ffmpeg-thumb/ 同在 cacheDir 下；逐目录遍历删除
        listOf(DISK_DIR_NAME, FFMPEG_THUMB_DIR).forEach { name ->
            val dir = File(context.cacheDir, name)
            if (dir.isDirectory) {
                dir.listFiles()?.forEach { f ->
                    if (f.isFile) {
                        bytes += f.length()
                        files++
                        try { f.delete() } catch (_: Exception) {}
                    }
                }
                try { dir.delete() } catch (_: Exception) {}
            }
        }
        return ClearResult(bytes, files)
    }

    /** 清空结果：释放的磁盘字节数 + 删除的文件数 */
    data class ClearResult(val bytes: Long, val files: Int)

    // ---------------- 诊断日志（ffmpeg 抽帧失败现场） ----------------

    /**
     * 抽帧失败现场记录：包括 ffmpeg 命令、returnCode、stderr tail、源文件路径
     * 与大小、seek 位置——用户在设置页"导出诊断日志"会输出到 Movies/LosslessTrim/
     * 下的 .txt，分享后可用于诊断花屏根因。
     */
    data class FailureRecord(
        val at: Long,
        val path: String?,
        val fileSize: Long,
        val timeMs: Long,
        val cmd: String,
        val returnCode: Int?,
        val stderr: String,
        val via: String,        // "ffmpeg" 或 "platform"
    )

    private const val MAX_FAILURE_RECORDS = 32
    private val recentFailures = java.util.Collections.synchronizedList(ArrayList<FailureRecord>())

    /** 记录失败现场（超出上限删最旧） */
    private fun recordFailure(r: FailureRecord) {
        recentFailures.add(r)
        while (recentFailures.size > MAX_FAILURE_RECORDS) {
            try { recentFailures.removeAt(0) } catch (_: Exception) {}
        }
    }

    /** 已收集的失败现场快照（导出时调用，避免持有引用太久） */
    fun snapshotFailures(): List<FailureRecord> = synchronized(recentFailures) {
        ArrayList(recentFailures)
    }

    /**
     * 导出诊断日志到 Movies/LosslessTrim/thumbstore_diag_<ts>.txt（用户可从文件
     * 管理器找到分享）。包含：设备信息、ffmpeg-kit 版本、最近 N 次抽帧失败的
     * 完整命令 + returnCode + stderr + 源文件路径大小。
     *
     * 已授予 MANIFEST_EXTERNAL_STORAGE 权限才能写到外部公共目录；未授权写到
     * app 私有 cacheDir（用户分享时需要进 Android/data/<pkg>/cache/）。
     */
    fun exportDiagnostics(context: Context): File? {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val body = buildDiagnosticsBody(context, ts)
        // 优先外部公共 Movies（用户能直接看到）；不可用回退 app 私有 files
        val dir = try {
            if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
                File(Environment.getExternalStorageDirectory(), "Movies/LosslessTrim").also {
                    if (!it.exists()) it.mkdirs()
                }
            } else null
        } catch (_: Exception) { null } ?: File(context.filesDir, "diagnostics").also {
            if (!it.exists()) it.mkdirs()
        }
        return try {
            val file = File(dir, "thumbstore_diag_${ts}.txt")
            file.bufferedWriter().use { it.write(body) }
            file
        } catch (_: Exception) {
            null
        }
    }

    /** 诊断文本组装（独立方法便于测试） */
    private fun buildDiagnosticsBody(context: Context, tsStr: String): String {
        val sb = StringBuilder()
        sb.appendLine("LosslessTrimAndroid ThumbStore diagnostics")
        sb.appendLine("Exported: $tsStr")
        sb.appendLine("App: ${context.packageName}")
        sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT}, ${Build.DISPLAY})")
        sb.appendLine("ffmpeg-kit: com.antonkarpenko:ffmpeg-kit-full:2.2.1 (FFmpeg v8.1.1)")
        sb.appendLine()
        sb.appendLine("==== Recent failures (${recentFailures.size}) ====")
        val snapshot = synchronized(recentFailures) { ArrayList(recentFailures) }
        snapshot.forEachIndexed { i, r ->
            sb.appendLine("---- #${i + 1} ----")
            sb.appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(r.at))}")
            sb.appendLine("Source: ${r.path} (${r.fileSize} bytes)")
            sb.appendLine("SeekMs: ${r.timeMs} (sec ${r.timeMs / 1000.0})")
            sb.appendLine("Via: ${r.via}")
            sb.appendLine("ReturnCode: ${r.returnCode}")
            sb.appendLine("Command: ${r.cmd}")
            sb.appendLine("Logs (tail 2000 chars):")
            sb.appendLine(r.stderr.takeLast(2000))
            sb.appendLine()
        }
        // 实验性 MediaCodec 直解现场：成功也记（能力面/tone-map 是否采纳是实验关心的）
        val mcAttempts = MediaCodecThumb.snapshotAttempts()
        sb.appendLine("==== MediaCodec direct decode (experimental, recent ${mcAttempts.size}) ====")
        if (mcAttempts.isEmpty()) {
            sb.appendLine("(no attempts yet)")
        }
        mcAttempts.forEachIndexed { i, a ->
            sb.appendLine(
                "#${i + 1} ${if (a.ok) "OK" else "FAIL"} ${a.durationMs}ms decoder=${a.decoder ?: "(default)"} " +
                        "mime=${a.mime} p010cap=${a.hasP010Cap} yuvflexcap=${a.hasYuvFlexCap} " +
                        "outColorFormat=0x${a.outColorFormat.toString(16)} imageFormat=0x${a.imageFormat.toString(16)} " +
                        "toneMap=${if (a.toneMapRequested) (if (a.toneMapApplied) "applied" else "rejected") else "n/a"} " +
                        "manualHdr=${a.manualHdr} frames=${a.decodedFrames} seekMs=${a.timeMs} err=${a.error ?: "-"}"
            )
            sb.appendLine("    uri=…${a.uri.takeLast(80)}")
        }
        return sb.toString()
    }

    /**
     * 异步加载：内存 → 磁盘 → 抽帧。
     *
     * 关键差异（按 [preview] 走两条路径）：
     *
     * - **切点预览（preview=true）**：memCache → diskCache（读时健康校验）→
     *   ffmpeg 软解 → platform 兜底（仅入内存）。历史版本曾让 preview 完全
     *   绕开 diskCache（怕命中上一版 platform 抽的坏 JPEG），副作用是冷启动/
     *   内存缓存被挤出后重进分析页，盘上明明有现成 JPEG（上次进页抽的切点图，
     *   或列表首帧图——切点对齐到 0 时两者同 key）仍要重跑 ffmpeg 重抽一遍，
     *   即"外面列表已经生成了新起点缩略图、时间线也没变，进来还要重新生成
     *   一次"。现在敢读的前提见下方 preview 分支注释。
     *
     * - **列表缩略图（preview=false）**：memCache → diskCache（健康检查）→
     *   ffmpeg 主路径（软解颜色正确）→ platform 兜底。缩略图小（128x72dp），
     *   优先保持列表渲染流畅。
     */
    suspend fun thumb(
        context: Context,
        key: String,
        uri: Uri,
        timeMs: Long = 0L,
        maxPx: Int,
        preview: Boolean = false,
        nearestKfSec: Double? = null,
        allowHw: Boolean = true,
    ): Bitmap? {
        memCache.get(key)?.let { return it }
        if (isRecentlyFailed(key)) return null
        val app = context.applicationContext
        if (preview) {
            // 切点预览：memCache → diskCache（读时健康校验）→ ffmpeg 软解 → platform 兜底。
            //
            // 2026-08-24 恢复读 diskCache：此前 preview 一律绕开磁盘（怕命中上一版
            // platform 抽的坏 JPEG 落盘），代价是冷启动/内存缓存被挤出后重进分析页，
            // 盘上明明有现成 JPEG（上次进页抽的切点图；或列表首帧图——actualStart
            // 对齐到 0 时 keyOf 归一为同 key）仍要重跑 ffmpeg 重抽一遍，即"外面
            // 列表已经生成了新起点缩略图、时间线也没变，进来还要重新生成一次"。
            // 现在敢读的前提：
            //  1) 抽帧主路径早已改为 ffmpeg（platform 仅兜底），列表路径一直在读
            //     同一目录，未再出现坏图回灌问题；
            //  2) loadFromDisk 每次读取都过 isBitmapHealthy（64 点 stddev），
            //     旧 platform 花屏形态（全单色 stddev<3 / 条带 stddev>95）当场
            //     拒绝并删文件，继续走 ffmpeg 重抽，最终行为与绕开磁盘时一致，
            //     坏图不会污染预览。时间线/文件变了 key 自然 miss，照常重抽。
            withContext(Dispatchers.IO) { loadFromDisk(app, key) }?.let { return it }
            return previewSemaphore.withPermit {
                withContext(Dispatchers.IO) {
                    memCache.get(key)
                        ?: loadFromDisk(app, key)   // 排队期间可能已被其它协程写入
                        ?: run {
                            // 实验性 MediaCodec 直解优先；失败/不健康自动回退 ffmpeg 软解
                            tryExtractViaMediaCodec(app, key, uri, timeMs, maxPx)
                                ?.let { return@withContext it }
                            val ffmpegBmp = extractViaFfmpeg(app, uri, timeMs, maxPx, nearestKfSec, allowHw)
                            if (ffmpegBmp != null) {
                                memCache.put(key, ffmpegBmp)
                                saveToDisk(app, key, ffmpegBmp)
                                ffmpegBmp
                            } else {
                                // ffmpeg 失败（路径不可用）：platform 兜底，**只入 memCache**
                                // 防止坏图污染 diskCache 供下次预览路径命中
                                val platBmp = extractViaPlatform(app, uri, timeMs, maxPx)
                                if (platBmp != null) memCache.put(key, platBmp)
                                platBmp ?: run {
                                    markFailed(key)
                                    null
                                }
                            }
                        }
                }
            }
        }
        // 列表缩略图：memCache → diskCache → ffmpeg 主路径 → platform 兜底
        // 原先 platform 主路径在 HEVC 上系统性花屏（MMR 解码 10-bit HEVC 出错），
        // 且 isBitmapHealthy 检测不出色调偏移/规则竖线类花屏。
        // 改为 ffmpeg 主路径（软解颜色正确），platform 仅在 ffmpeg 失败时兜底。
        // diskCache 仍有效：ffmpeg 生成的健康图会被缓存，二次进入秒出。
        withContext(Dispatchers.IO) { loadFromDisk(app, key) }?.let { return it }
        return listSemaphore.withPermit {
            withContext(Dispatchers.IO) {
                memCache.get(key)
                    ?: loadFromDisk(app, key)   // 排队期间可能已被其它协程写入缓存
                    ?: run { tryExtractViaMediaCodec(app, key, uri, timeMs, maxPx) }
                    ?: extractViaFfmpeg(app, uri, timeMs, maxPx, allowHw = allowHw)?.also { bmp ->
                        memCache.put(key, bmp)
                        saveToDisk(app, key, bmp)
                    } ?: extractViaPlatform(app, uri, timeMs, maxPx)?.also { bmp ->
                        // ffmpeg 失败（路径不可用/损坏文件）：platform 兜底
                        memCache.put(key, bmp)
                        saveToDisk(app, key, bmp)
                    } ?: run {
                        markFailed(key)
                        null
                    }
            }
        }
    }

    // ---------------- 实验性：MediaCodec 直解 ----------------

    /**
     * 实验性 MediaCodec 直解抽帧（设置页“MediaCodec 直解缩略图”，详见 MediaCodecThumb）。
     *
     * 成功：过 isBitmapHealthy 后 memCache + diskCache 双入（与 ffmpeg 主路径同等待遇）；
     * 失败/不健康：记录失败现场（via=mediacodec，附 MediaCodecThumb.lastDiag 能力面）
     * + 写入 mcFailed 哨兵（60s 内同 key 不重试），返回 null 让调用方沿
     * ffmpeg → platform 既有链路兑底——即 txt 建议的“只有 MediaCodec 不支持/
     * 解码失败/P010 输出异常/厂商 codec bug 才 fallback 到 FFmpeg”。
     */
    private fun tryExtractViaMediaCodec(context: Context, key: String, uri: Uri, timeMs: Long, maxPx: Int): Bitmap? {
        if (!mcThumbEnabled) return null
        if (isRecentlyMcFailed(key)) return null
        val bmp = runCatching { MediaCodecThumb.extract(context, uri, timeMs, maxPx) }.getOrNull()
        if (bmp == null) {
            markMcFailed(key)
            recordFailure(FailureRecord(
                at = System.currentTimeMillis(),
                path = runCatchingPath(context, uri),
                fileSize = runCatchingFileSize(context, uri),
                timeMs = timeMs,
                cmd = "(MediaCodec direct decode, experimental)",
                returnCode = null,
                stderr = "MediaCodec direct decode failed. ${MediaCodecThumb.lastDiag ?: "no diag"}",
                via = "mediacodec",
            ))
            return null
        }
        if (!isBitmapHealthy(bmp)) {
            markMcFailed(key)
            bmp.recycle()
            recordFailure(FailureRecord(
                at = System.currentTimeMillis(),
                path = runCatchingPath(context, uri),
                fileSize = runCatchingFileSize(context, uri),
                timeMs = timeMs,
                cmd = "(MediaCodec direct decode, experimental)",
                returnCode = null,
                stderr = "MediaCodec bitmap rejected by isBitmapHealthy (stddev). " +
                        "${MediaCodecThumb.lastDiag ?: ""}",
                via = "mediacodec",
            ))
            return null
        }
        memCache.put(key, bmp)
        saveToDisk(context, key, bmp)
        return bmp
    }

    // ---------------- 抽帧 ----------------

    /**
     * platform API 抽帧（列表缩略图快路径，preview 不走）。
     *
     * 用 OPTION_CLOSEST（最近可解码帧）而非 CLOSEST_SYNC（前一个关键帧）：
     * 后者在 HEVC 5~10s GOP 下经常跳到几秒外的关键帧。
     *
     * 返回 null 触发 ffmpeg 兜底：抽帧异常 / Bitmap 不健康（绿屏/单色/条带/
     * 马赛克花屏——见 [isBitmapHealthy] 的 stddev 检测）。platform 在 HEVC +
     * B 帧 + 不标准 MP4 上系统性偶发花屏，**单色检测已不能涵盖**。
     */
    private fun extractViaPlatform(context: Context, uri: Uri, timeMs: Long, maxPx: Int): Bitmap? {
        val mmr = MediaMetadataRetriever()
        var unhealthy = false
        return try {
            mmr.setDataSource(context, uri)
            val timeUs = timeMs * 1000L
            val bmp = if (Build.VERSION.SDK_INT >= 27) {
                mmr.getScaledFrameAtTime(
                    timeUs, MediaMetadataRetriever.OPTION_CLOSEST, maxPx, maxPx
                )
            } else {
                val orig = mmr.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                scaleAndRecycle(orig, maxPx)
            }
            if (bmp != null && isBitmapHealthy(bmp)) bmp else {
                // 不健康：典型表现是绿屏/粉红条带/绿红紫混合花屏
                unhealthy = bmp != null
                bmp?.recycle()
                null
            }
        } catch (e: Exception) {
            recordFailure(FailureRecord(
                at = System.currentTimeMillis(),
                path = runCatchingPath(context, uri),
                fileSize = runCatchingFileSize(context, uri),
                timeMs = timeMs,
                cmd = "(platform MediaMetadataRetriever OPTION_CLOSEST maxPx=$maxPx)",
                returnCode = null,
                stderr = "Exception: ${e.javaClass.simpleName}: ${e.message}",
                via = "platform",
            ))
            null
        } finally {
            try {
                mmr.release()
            } catch (_: Exception) {
            }
            if (unhealthy) {
                recordFailure(FailureRecord(
                    at = System.currentTimeMillis(),
                    path = runCatchingPath(context, uri),
                    fileSize = runCatchingFileSize(context, uri),
                    timeMs = timeMs,
                    cmd = "(platform MediaMetadataRetriever OPTION_CLOSEST maxPx=$maxPx)",
                    returnCode = null,
                    stderr = "Bitmap returned but isBitmapHealthy rejected (stddev<3 single-color or stddev>95 striped)",
                    via = "platform",
                ))
            }
        }
    }

    /** 同步取路径（不影响 platform 抽帧主流程） */
    private fun runCatchingPath(context: Context, uri: Uri): String? = try {
        StorageAccess.accessibleFile(context, uri)?.absolutePath
    } catch (_: Exception) { null }

    /** 同步取文件大小（失败返回 0） */
    private fun runCatchingFileSize(context: Context, uri: Uri): Long = try {
        StorageAccess.accessibleFile(context, uri)?.length() ?: 0L
    } catch (_: Exception) { 0L }

    /**
     * ffmpeg 软解抽帧（platform 失败/不健康时回退）。启动 ffmpeg 抽 1 帧 JPEG
     * 到 cacheDir（每张几十 KB），再 BitmapFactory 解出，完事即删。绕开平台
     * codec：HEVC/H.264/HDR10/部分自定义参数都走 ffmpeg 自带软解，bitmap
     * 一定是解码完成的、不会出现"内部 native data 损坏"的绿屏。
     *
     * 输入必须是直路径（ffmpeg 不支持 SAF fd）：拿不到路径（云盘/未授权）
     * 直接返回 null；AnalysisScreen 由 VideoPlayerPanel 的"该格式无法预览"
     * 兜底，抽帧不显示仅少两张缩略图、不阻塞分析流程。
     *
     * 单次约 80~200ms：每分析页最多两张切点抽帧 + 拖动 200ms 防抖，可接受。
     */
    private fun extractViaFfmpeg(context: Context, uri: Uri, timeMs: Long, maxPx: Int, nearestKfSec: Double? = null, allowHw: Boolean = true): Bitmap? {
        val path = StorageAccess.accessibleFile(context, uri)?.absolutePath ?: return null
        val outDir = File(context.cacheDir, FFMPEG_THUMB_DIR)
        if (!outDir.exists()) outDir.mkdirs()
        val outFile = File(outDir, "ff_${System.currentTimeMillis()}_${Thread.currentThread().id}.jpg")
        val ss = (timeMs / 1000.0).coerceAtLeast(0.0)
        val ssStr = String.format(Locale.US, "%.3f", ss)
        // === 缩略图抽帧策略（2026-08-23 重构）===
        // 根因：视频是 limited range (tv, Y:16-235)，JPEG 需要 full range (pc, 0-255)
        // FFmpeg 默认不做 range 转换，直接把 limited range 数据塞进 JPEG → 颜色错乱/花屏
        // 修复：scale 滤镜加 out_range=pc 做 limited→full range 转换
        // 参考StackOverflow: https://stackoverflow.com/questions/74350828
        //
        // 软解（默认）：native hevc/x265 软解，10-bit → 8-bit 颜色正确；
        //   **不再用 -skip_frame nokey**——它只解 I 帧，与 -ss input seek 组合时
        //   （目标点之前的 I 帧被 seek 丢弃、目标点又是 P/B 帧被跳过）会导致
        //   Filtergraph 无帧输出，抽帧必然失败。
        // 硬解（设置开关）：GPU 解 HEVC 快 5-10x，但 10-bit 输出颜色可能不可靠。
        //   用户可在设置中开启，自行权衡速度与颜色准确性。
        // 回退链（有最近关键帧 + 硬解）：hw-out-seek → hw-input-seek → sw-out-seek → sw-input-seek
        // 回退链（有最近关键帧 + 软解）：sw-out-seek → sw-input-seek
        // 回退链（无关键帧 + 硬解）：hw-input-seek → hw-out-seek → sw-input-seek → sw-out-seek
        // 回退链（无关键帧 + 软解）：sw-input-seek → sw-out-seek

        // --- 软件解码命令 ---
        val swCommon = "-hide_banner -loglevel info -err_detect ignore_err -threads 0"
        val swVf = "scale='min($maxPx,iw)':-1:in_color_matrix=auto:out_color_matrix=bt709:out_range=pc"
        val swInputCmd = "$swCommon -ss $ssStr -i \"$path\" -an -sn -frames:v 1 -vf \"$swVf\" -q:v 3 -y \"${outFile.absolutePath}\""
        // 有关键帧信息时用最近关键帧做 pre-seek（output-seek 距离最小，AVDiscard 加速），
        // 无关键帧信息时回退固定 30s pre-buffer
        val preSec = nearestKfSec?.coerceIn(0.0, ss) ?: (ss - 30.0).coerceAtLeast(0.0)
        val outDelta = ss - preSec
        val swOutCmd = "$swCommon -ss ${String.format(Locale.US, "%.3f", preSec)} -i \"$path\" " +
                "-ss ${String.format(Locale.US, "%.3f", outDelta)} -an -sn -frames:v 1 -vf \"$swVf\" -q:v 3 -y \"${outFile.absolutePath}\""

        // --- 硬件解码命令 ---
        val hwCommon = "-hide_banner -loglevel info -err_detect ignore_err -hwaccel mediacodec -threads 0"
        // 硬解帧的颜色元数据（range/matrix）经常丢失或标错——mediacodec copyback 的已知坑：
        // auto 拿不到标记就退化成默认值（full range / bt601），out_range=pc 拿错源转换 → 偏色。
        // 显式写死 in_range=tv + bt709（视频几乎全是 limited range + bt709）兜住最常见偏色。
        // 软解命令的 auto 不动：软解元数据完整，auto 本来就对。
        // 注：HDR（bt2020pq）内容仍会偏色，缩略图场景可接受。
        val hwVf = "scale='min($maxPx,iw)':-1:in_range=tv:in_color_matrix=bt709:out_color_matrix=bt709:out_range=pc"
        val hwInputCmd = "$hwCommon -ss $ssStr -i \"$path\" -an -sn -frames:v 1 -vf \"$hwVf\" -q:v 3 -y \"${outFile.absolutePath}\""
        val hwOutCmd = "$hwCommon -ss ${String.format(Locale.US, "%.3f", preSec)} -i \"$path\" " +
                "-ss ${String.format(Locale.US, "%.3f", outDelta)} -an -sn -frames:v 1 -vf \"$hwVf\" -q:v 3 -y \"${outFile.absolutePath}\""

        val firstCmd = if (nearestKfSec != null) swOutCmd else swInputCmd
        // 设置页开关 × 调用方资格（10-bit 文件由 ProbeResult.hwThumbEligible 判定，硬解颜色不可靠）
        val hwDecode = hwDecodeEnabled && allowHw
        return try {
            if (nearestKfSec != null) {
                // 有最近关键帧：两阶段 seek 优先（output-seek 阶段 AVDiscard 跳非参考帧加速）
                if (hwDecode) {
                    runFfmpegOnce(hwOutCmd, path, timeMs, outFile)
                        ?: runFfmpegOnce(hwInputCmd, path, timeMs, outFile)
                        ?: runFfmpegOnce(swOutCmd, path, timeMs, outFile)
                        ?: runFfmpegOnce(swInputCmd, path, timeMs, outFile)
                } else {
                    runFfmpegOnce(swOutCmd, path, timeMs, outFile)
                        ?: runFfmpegOnce(swInputCmd, path, timeMs, outFile)
                }
            } else {
                // 无关键帧信息：input-seek 优先
                if (hwDecode) {
                    runFfmpegOnce(hwInputCmd, path, timeMs, outFile)
                        ?: runFfmpegOnce(hwOutCmd, path, timeMs, outFile)
                        ?: runFfmpegOnce(swInputCmd, path, timeMs, outFile)
                        ?: runFfmpegOnce(swOutCmd, path, timeMs, outFile)
                } else {
                    runFfmpegOnce(swInputCmd, path, timeMs, outFile)
                        ?: runFfmpegOnce(swOutCmd, path, timeMs, outFile)
                }
            }
        } catch (e: Exception) {
            recordFailure(FailureRecord(
                at = System.currentTimeMillis(),
                path = path,
                fileSize = File(path).length(),
                timeMs = timeMs,
                cmd = firstCmd,
                returnCode = null,
                stderr = "Exception (outer): ${e.javaClass.simpleName}: ${e.message}",
                via = "ffmpeg",
            ))
            null
        }
    }

    /** 单次 ffmpeg 抽帧执行；失败（rc 非成功/输出为空）返回 null 让 caller 沿回退链继续。不做健康检测——是否用硬解交由设置开关决定 */
    private fun runFfmpegOnce(cmd: String, path: String, timeMs: Long, outFile: File): Bitmap? {
        val session = try {
            FFmpegKit.execute(cmd)
        } catch (e: Exception) {
            recordFailure(FailureRecord(
                at = System.currentTimeMillis(),
                path = path,
                fileSize = File(path).length(),
                timeMs = timeMs,
                cmd = cmd,
                returnCode = null,
                stderr = "Exception: ${e.javaClass.simpleName}: ${e.message}",
                via = "ffmpeg",
            ))
            return null
        }
        val rc = session.returnCode
        // 收集日志用于诊断（allLogsAsString 在 session history=2 下可能丢，
        // 但 execute 同步返回时 session 仍在 history 内，logs 完整）
        val logs = try { session.allLogsAsString } catch (_: Exception) { null } ?: ""
        try {
            return if (rc != null && rc.isValueSuccess && outFile.exists() && outFile.length() > 0) {
                val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                BitmapFactory.decodeFile(outFile.absolutePath, opts)
            } else {
                // rc 非 success 或输出文件为空：典型 input-seek 在 EOF 附近跳过范围 / 解码失败
                recordFailure(FailureRecord(
                    at = System.currentTimeMillis(),
                    path = path,
                    fileSize = File(path).length(),
                    timeMs = timeMs,
                    cmd = cmd,
                    returnCode = rc?.value,
                    stderr = "rc=${rc?.value} outfile_exists=${outFile.exists()} " +
                            "outfile_size=${outFile.length()}. Logs:\n$logs",
                    via = "ffmpeg",
                ))
                null
            }
        } finally {
            try { outFile.delete() } catch (_: Exception) {}
        }
    }

    /**
     * Bitmap 健康检测：8x8 网格采样 64 个像素点，统计 R/G/B 三通道标准差。
     *
     * 上一版用 5 点单色检测（绿/黑/蓝/紫），对条带/马赛克花屏（HEVC B 帧解
     * 码错位的典型表现：粉红+白条带 / 绿红紫混合色块）识别不出——坏图通过检测
     * 进入 diskCache 后即使清缓存按钮重抽仍可能命中。
     *
     * 新版用 64 点 stddev 检测：
     * - **stddev < 3** = 全单色花屏（绿/黑/紫/红整片同色）
     * - **stddev > 95** = 条带/马赛克花屏（相邻像素颜色剧变，正常画面中
     *   同一通道的 stddev 通常 5~70；条带/棋盘格 stddev 普遍 90+）
     * - 合法画面 stddev 通常 5~70（不同场景差异大）：浅色背景 stddev 5~30，
     *   复杂画面 30~70，均落在正常范围
     *
     * 注：极端合法画面（全单色纯背景/重彩动画）可能误判"不健康"——列表缩略图
     * 失败会显示占位符"加载中…"，UI 仍可继续；切点预览失败会显示占位符，分析
     * 页其他要素（时间轴/视频预览）仍可用，宁可少图不要花屏。
     */
    private fun isBitmapHealthy(bmp: Bitmap): Boolean {
        if (bmp.isRecycled || bmp.width <= 0 || bmp.height <= 0) return false
        val w = bmp.width
        val h = bmp.height
        val n = 8
        val stepX = (w / n).coerceAtLeast(1)
        val stepY = (h / n).coerceAtLeast(1)
        var rSum = 0L
        var gSum = 0L
        var bSum = 0L
        val count = n * n
        // 同时收集各通道原始值用于 stddev 计算（避免对每个像素两次访问）
        val rArr = IntArray(count)
        val gArr = IntArray(count)
        val bArr = IntArray(count)
        var idx = 0
        for (sy in 0 until n) {
            val y = (sy * stepY).coerceAtMost(h - 1)
            for (sx in 0 until n) {
                val x = (sx * stepX).coerceAtMost(w - 1)
                val p = bmp.getPixel(x, y)
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                rArr[idx] = r; gArr[idx] = g; bArr[idx] = b
                rSum += r; gSum += g; bSum += b
                idx++
            }
        }
        val rMean = rSum.toDouble() / count
        val gMean = gSum.toDouble() / count
        val bMean = bSum.toDouble() / count
        var rVar = 0.0
        var gVar = 0.0
        var bVar = 0.0
        for (i in 0 until count) {
            val dr = rArr[i] - rMean
            val dg = gArr[i] - gMean
            val db = bArr[i] - bMean
            rVar += dr * dr
            gVar += dg * dg
            bVar += db * db
        }
        rVar /= count; gVar /= count; bVar /= count
        val rStd = kotlin.math.sqrt(rVar)
        val gStd = kotlin.math.sqrt(gVar)
        val bStd = kotlin.math.sqrt(bVar)
        // 全单色：任一通道过低（三通道同时低才计"全单色"，避免误杀低对比度合法画面）
        if (rStd < 3 && gStd < 3 && bStd < 3) return false
        // 条带/马赛克：任一通道过高
        if (rStd > 95 || gStd > 95 || bStd > 95) return false
        return true
    }

    private const val FFMPEG_THUMB_DIR = "ffmpeg-thumb"

    private fun scaleAndRecycle(orig: Bitmap?, maxPx: Int): Bitmap? {
        if (orig == null) return null
        val w = orig.width
        val h = orig.height
        if (w <= maxPx && h <= maxPx) return orig
        return try {
            val scale = maxPx.toFloat() / maxOf(w, h)
            val sw = (w * scale).toInt().coerceAtLeast(1)
            val sh = (h * scale).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(orig, sw, sh, true)
            scaled
        } finally {
            // 无论缩放成功与否，原始全尺寸帧都不再需要，立即回收
            orig.recycle()
        }
    }

    // ---------------- 磁盘缓存 ----------------

    private fun diskDir(context: Context): File {
        diskDirCache?.let { return it }
        val dir = File(context.cacheDir, DISK_DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        diskDirCache = dir
        return dir
    }

    private fun diskFile(context: Context, key: String): File =
        File(diskDir(context), md5(key) + ".jpg")

    private fun loadFromDisk(context: Context, key: String): Bitmap? {
        val f = diskFile(context, key)
        if (!f.exists()) return null
        return try {
            val bmp = BitmapFactory.decodeFile(f.absolutePath)
            if (bmp != null && !bmp.isRecycled && isBitmapHealthy(bmp)) {
                memCache.put(key, bmp)
                bmp
            } else {
                // 旧版（修复前）写入的损坏 JPEG：解码成功但内部花屏；
                // 不入缓存、删文件让 caller 重新抽（platform→ffmpeg fallback）
                bmp?.recycle()
                try { f.delete() } catch (_: Exception) {}
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun saveToDisk(context: Context, key: String, bmp: Bitmap) {
        try {
            val f = diskFile(context, key)
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            // 每 32 次写入才扫一次目录做清理，避免每张图都 listFiles；
            // 最坏超限 31 个文件，对缓存体积无实质影响
            if (writeCounter.incrementAndGet() % 32 == 0) {
                pruneDisk(diskDir(context))
            }
        } catch (_: Exception) {
            // 磁盘缓存是加速项，写失败不影响功能
        }
    }

    /** 超出上限时按修改时间删除最旧文件 */
    private fun pruneDisk(dir: File) {
        val files = dir.listFiles() ?: return
        if (files.size <= DISK_MAX_FILES) return
        files.sortBy { it.lastModified() }
        var toDelete = files.size - DISK_MAX_FILES
        for (f in files) {
            if (toDelete <= 0) break
            f.delete()
            toDelete--
        }
    }

    private fun md5(text: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
