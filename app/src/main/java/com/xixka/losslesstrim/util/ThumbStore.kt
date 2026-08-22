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
     * 磁盘命中不需要排队（只是解一张小 JPEG），直接放行；
     * 真正 expensive 的视频抽帧才进并发池，列表/预览各用各的池。
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
        // 磁盘缓存命中：不占用抽帧许可，即刻返回
        withContext(Dispatchers.IO) { loadFromDisk(app, key) }?.let { return it }
        val sem = if (preview) previewSemaphore else listSemaphore
        return sem.withPermit {
            withContext(Dispatchers.IO) {
                memCache.get(key)
                    ?: loadFromDisk(app, key)   // 排队期间可能已被其它协程写入缓存
                    ?: extract(app, uri, timeMs, maxPx)?.also { bmp ->
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
     * 抽帧：先尝试 platform MediaMetadataRetriever（快、不出进程），失败
     * 或返回的 Bitmap 不健康（典型花屏：HEVC + B 帧 + 不标准 MP4 下 platform
     * codec 偶发返回内部 native data 损坏的 Bitmap）时回退 ffmpeg 软解
     * 抽帧（启动一个 ffmpeg 进程抽 1 帧到 JPEG 再读入——慢但稳定，不依赖
     * 平台 codec 兼容性）。
     *
     * 选用 OPTION_CLOSEST 而非 OPTION_CLOSEST_SYNC：CLOSEST 返回离 timeMs
     * 最近的可解码帧（不要求是 IDR），更贴近"切点附近的画面"；CLOSEST_SYNC
     * 返回的是之前的最近关键帧，HEVC 5~10s GOP 距离下经常落在距离切点几秒
     * 之外——预览与切点脱节，用户会感觉"切点抽帧不对"。
     */
    private fun extract(context: Context, uri: Uri, timeMs: Long, maxPx: Int): Bitmap? {
        val plat = extractViaPlatform(context, uri, timeMs, maxPx)
        if (plat != null) return plat
        return extractViaFfmpeg(context, uri, timeMs, maxPx)
    }

    /** platform API 抽帧（默认路径）；不健康时返回 null 以便 caller 回退 */
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
                // 不健康：典型表现是绿屏/全黑/像素错位，caller 会回退 ffmpeg
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
        // 仍 OK，仅稍慢）；-an/-sn 跳过音频/字幕流的 demux 解码（不分配解复用
        // 缓冲），避免少数容器在 input-seek 时把无关流也读一遍
        val cmd = "-hide_banner -loglevel error -ss ${String.format(Locale.US, "%.3f", ss)} " +
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
     * Bitmap 健康检测：platform MediaMetadataRetriever 在 HEVC + B 帧 + 不
     * 标准 MP4（moov 在尾部、codec_tag 异常）上偶发返回 Bitmap 对象但内部
     * native data 损坏——getPixel 拿到全 0x0000FF00（绿）或 0xFF000000（黑）
     * 或部分撕裂。采样 5 个像素点，3 个及以上接近同一种"非自然色"判为花屏。
     *
     * 检测通过 → 接受；不通过 → recycle 并返回 false 让 caller 走 ffmpeg
     * fallback。注意：短视频封面/纯黑片头在统计上不可能同时命中 3 点同色
     * 阈值（任一点都是合法画面色），不会误杀。
     */
    private fun isBitmapHealthy(bmp: Bitmap): Boolean {
        if (bmp.isRecycled || bmp.width <= 0 || bmp.height <= 0) return false
        val w = bmp.width
        val h = bmp.height
        val pts = intArrayOf(
            bmp.getPixel(w / 2, h / 2),
            bmp.getPixel(w / 4, h / 2),
            bmp.getPixel(3 * w / 4, h / 2),
            bmp.getPixel(w / 2, h / 4),
            bmp.getPixel(w / 2, 3 * h / 4),
        )
        fun isNear(c: Int, r: Int, g: Int, b: Int): Boolean {
            val cr = (c shr 16) and 0xFF
            val cg = (c shr 8) and 0xFF
            val cb = c and 0xFF
            return kotlin.math.abs(cr - r) < 8 && kotlin.math.abs(cg - g) < 8 && kotlin.math.abs(cb - b) < 8
        }
        val allGreen = pts.count { isNear(it, 0, 220, 0) } >= 3
        val allBlack = pts.count { isNear(it, 0, 0, 0) } >= 3
        val allBlue  = pts.count { isNear(it, 0, 0, 220) } >= 3
        val allPurple = pts.count { isNear(it, 160, 0, 160) } >= 3
        return !(allGreen || allBlack || allBlue || allPurple)
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
