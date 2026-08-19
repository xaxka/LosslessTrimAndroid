package com.xixka.losslesstrim.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

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

    /** 缓存键：同一文件不同时间点分开缓存（timeMs<=0 即首帧缩略图） */
    fun keyOf(uri: Uri, timeMs: Long = 0L): String =
        if (timeMs <= 0L) uri.toString() else "$uri@$timeMs"

    /** 同步探测：命中内存缓存立即返回（页面返回时秒显示，不闪占位符） */
    fun peek(key: String): Bitmap? = memCache.get(key)

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

    private fun extract(context: Context, uri: Uri, timeMs: Long, maxPx: Int): Bitmap? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(context, uri)
            val timeUs = timeMs * 1000L
            if (Build.VERSION.SDK_INT >= 27) {
                // API 27+：直接取缩放帧，避免全尺寸 Bitmap 落堆
                mmr.getScaledFrameAtTime(
                    timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, maxPx, maxPx
                )
            } else {
                // API 26 兜底：取出全尺寸帧后立即缩放并回收原图（4K 帧约 33MB）
                val orig = mmr.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                scaleAndRecycle(orig, maxPx)
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
            if (bmp != null && !bmp.isRecycled) {
                memCache.put(key, bmp)
                bmp
            } else {
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
