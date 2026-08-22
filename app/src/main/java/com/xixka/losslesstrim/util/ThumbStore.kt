package com.xixka.losslesstrim.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import com.antonkarpenko.ffmpegkit.FFmpegKit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
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

    /** 失败哨兵：抽帧失败的文件不再重试，避免坏文件每次进页面都空转解码器 */
    private val failed = object : LruCache<String, Boolean>(64) {
        override fun sizeOf(key: String, value: Boolean): Int = 1
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
        diskDirCache = null     // 强制下次 diskDir() 重新解析（旧的已删）
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

    /**
     * 异步加载：内存 → 磁盘 → 抽帧。
     *
     * 关键差异（按 [preview] 走两条路径）：
     *
     * - **切点预览（preview=true）**：必走 ffmpeg 软解，**不查 diskCache**。
     *   原因：上一版 thumbnail 路径同时存 platform + ffmpeg 抽的图，platform 在
     *   HEVC + B 帧 + 不标准 MP4 上系统性返回花屏（粉红条带 / 绿红紫混合等），
     *   上一版 5 点单色检测识别不出这类花屏，坏图进 diskCache 后即使清缓存按钮
     *   之后重抽仍可能命中旧坏图。preview 路径绕开 diskCache：每次进分析页
     *   必走 ffmpeg 软解，绕开 platform codec 兼容性。100~200ms/张 + 拖动 200ms
     *   防抖 = 用户感知 300~400ms 一次，可接受（花屏不能忍，慢一点可接受）。
     *
     * - **列表缩略图（preview=false）**：先 memCache → diskCache（健康检查）→
     *   platform 主路径（快）→ 不健康则 ffmpeg 兜底。缩略图小（128x72dp），
     *   偶发花屏对判断无影响；优先保持列表渲染流畅。
     */
    suspend fun thumb(
        context: Context,
        key: String,
        uri: Uri,
        timeMs: Long = 0L,
        maxPx: Int,
        preview: Boolean = false,
    ): Bitmap? {
        memCache.get(key)?.let { return it }
        if (failed.get(key) != null) return null
        val app = context.applicationContext
        if (preview) {
            // 切点预览：绕开 diskCache（可能命中旧 platform 花屏 JPEG），直接 ffmpeg 软解
            return previewSemaphore.withPermit {
                withContext(Dispatchers.IO) {
                    memCache.get(key) ?: run {
                        val ffmpegBmp = extractViaFfmpeg(app, uri, timeMs, maxPx)
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
                                failed.put(key, true)
                                null
                            }
                        }
                    }
                }
            }
        }
        // 列表缩略图：常规 memCache → diskCache → 抽帧（platform 主路径）
        withContext(Dispatchers.IO) { loadFromDisk(app, key) }?.let { return it }
        return listSemaphore.withPermit {
            withContext(Dispatchers.IO) {
                memCache.get(key)
                    ?: loadFromDisk(app, key)   // 排队期间可能已被其它协程写入缓存
                    ?: extractViaPlatform(app, uri, timeMs, maxPx)?.also { bmp ->
                        memCache.put(key, bmp)
                        saveToDisk(app, key, bmp)
                    } ?: extractViaFfmpeg(app, uri, timeMs, maxPx)?.also { bmp ->
                        // platform 失败/不健康 → ffmpeg 兜底（健康）正常缓存
                        memCache.put(key, bmp)
                        saveToDisk(app, key, bmp)
                    } ?: run {
                        failed.put(key, true)
                        null
                    }
            }
        }
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
                // 不健康：典型表现是绿屏/粉红条带/绿红紫混合花屏，caller 会回退 ffmpeg
                bmp?.recycle()
                null
            }
        } catch (e: Exception) {
            null
        } finally {
            try {
                mmr.release()
            } catch (_: Exception) {
            }
        }
    }

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
    private fun extractViaFfmpeg(context: Context, uri: Uri, timeMs: Long, maxPx: Int): Bitmap? {
        val path = StorageAccess.accessibleFile(context, uri)?.absolutePath ?: return null
        val outDir = File(context.cacheDir, FFMPEG_THUMB_DIR)
        if (!outDir.exists()) outDir.mkdirs()
        val outFile = File(outDir, "ff_${System.currentTimeMillis()}_${Thread.currentThread().id}.jpg")
        val ss = (timeMs / 1000.0).coerceAtLeast(0.0)
        val scale = "scale='min($maxPx,iw)':-1"
        // input-seek 模式：-ss 在 -i 前用 container index 快速定位（前提容器有
        // 索引——MP4/MKV 都有；纯 MPEG-1/2/TS 等不索引容器下会回退为顺序读，
        // 仍 OK，仅稍慢）
        //
        // **关键参数 -skip_frame nokey + -threads 1 + -err_detect ignore_err**：
        // HEVC + B 帧 + 不标准 MP4 上 ffmpeg 即使软解也可能在 input-seek 落点
        // 错位时遇到 P/B 帧需要参考（前一个 IDR 已被 seek 跳过）→ 解参考失败
        // 输出错帧（粉红条带 / 绿红紫混合花屏）。
        //
        // -skip_frame nokey：codec-level 选项，让 ffmpeg 跳过非关键帧包只解
        //   I 帧——I 帧独立可解不需参考，必然稳定
        // -threads 1：单线程解 HEVC，避免 ffmpeg 多线程在短任务里调度错位
        // -err_detect ignore_err：跳过坏包不抛错
        val cmd = "-hide_banner -loglevel error -err_detect ignore_err -skip_frame nokey -threads 1 " +
                "-ss ${String.format(Locale.US, "%.3f", ss)} " +
                "-i \"$path\" -an -sn -frames:v 1 -vf \"$scale\" -q:v 3 -y \"${outFile.absolutePath}\""
        return try {
            val session = FFmpegKit.execute(cmd)
            val rc = session.returnCode
            try {
                if (rc != null && rc.isValueSuccess && outFile.exists() && outFile.length() > 0) {
                    val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                    val bmp = BitmapFactory.decodeFile(outFile.absolutePath, opts)
                    if (bmp != null && isBitmapHealthy(bmp)) bmp else {
                        bmp?.recycle()
                        null
                    }
                } else null
            } finally {
                try { outFile.delete() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            null
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
