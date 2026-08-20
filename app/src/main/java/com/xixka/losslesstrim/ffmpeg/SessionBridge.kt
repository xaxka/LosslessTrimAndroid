package com.xixka.losslesstrim.ffmpeg

import com.antonkarpenko.ffmpegkit.FFmpegKitConfig
import com.antonkarpenko.ffmpegkit.Statistics
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * ffmpeg-kit 会话输出桥。
 *
 * 背景：ffmpeg-kit 的 Java 侧日志/统计回调先按 sessionId 从“会话历史”里查会话，
 * 查不到就把整条消息**静默丢弃**。本应用为防大输出（关键帧 CSV 可达几十 MB）驻留
 * 内存导致 OOM，把会话历史压到了 2 条；曾经扫描阶段 4 路 ffprobe 并发、扫描还会
 * 与剪辑队列的 ffmpeg 会话重叠——正在运行的会话随时可能被新会话挤出历史，
 * 表现为 ffprobe 输出被拦腰截断（如 JSON 在第 822 字符处突然结束）、剪辑进度
 * 回调冻结、失败原因日志残缺。因此所有会话输出一律经本桥采集（见下），
 * 且所有 ffmpeg/ffprobe 执行经全局执行锁串行（见 executeMutex）。
 *
 * 方案：注册**全局** Log/Statistics 回调（它们的调用不经过会话历史查找，一定会触发），
 * 按 sessionId 路由到进程内缓冲。只有显式 begin 过的会话才会被记录，其余会话
 * 零开销、零驻留。
 *
 * 两种采集模式（大输出走磁盘，防 OOM）：
 *  - 内存模式 beginLogs：小输出（媒体 JSON，KB 级）直接进 StringBuilder；
 *  - 磁盘模式 beginSpill：大输出（关键帧 packet CSV，长视频几十 MB）边收边
 *    写 cacheDir 临时文件，消费方从磁盘流式读取，内存峰值只剩解析结果本身。
 */
object SessionBridge {

    /** doneLogs 总字节预算（ffmpeg 会话日志仅供 extractError 取尾部几行） */
    private const val DONE_MAX_TOTAL_CHARS = 4 * 1024 * 1024

    /** 单条定格日志上限（字符）：只保留尾部——错误提取只看最后几行 */
    private const val DONE_MAX_ENTRY_CHARS = 256 * 1024

    /** 磁盘溢写临时文件的保留期：进程被杀遗留的残文件按此清理 */
    private const val SPILL_MAX_AGE_MS = 24 * 60 * 60 * 1000L

    /** 正在采集日志的会话：sessionId → 输出缓冲（仅执行线程写入） */
    private val logBuffers = ConcurrentHashMap<Long, StringBuilder>()

    /** 正在磁盘溢写的会话：sessionId → 溢写句柄 */
    private val spills = ConcurrentHashMap<Long, Spill>()

    /** 已结束、日志已定格待消费的会话（ffmpeg 完成回调先于 extractError 消费） */
    private val doneBytes = AtomicLong(0)

    private val doneLogs: MutableMap<Long, String> = Collections.synchronizedMap(
        object : LinkedHashMap<Long, String>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, String>?): Boolean {
                val over = doneBytes.get() > DONE_MAX_TOTAL_CHARS && size > 1
                if (over && eldest != null) {
                    doneBytes.addAndGet(-eldest.value.length.toLong())
                }
                return over
            }
        }
    )

    /** 进度统计路由：sessionId → 处理器 */
    private val statHandlers = ConcurrentHashMap<Long, (Double, Double) -> Unit>()

    /**
     * ffmpeg-kit 全局执行互斥锁。
     *
     * 实测：多个会话**并发执行**时，库对日志的会话归属不可靠——并发扫描或
     * 扫描与剪辑队列重叠时，ffmpeg 会话的 stderr 行（如 "Stream #0:1[0x2]"，
     * ffmpeg 6.x+ 输入转储风格）会被写进并发 ffprobe 会话的输出缓冲，JSON
     * 拦腰混入外来行 → "Expected ':' after Stream ..." 解析失败、文件被判
     * "不可处理"。这是会话历史挤出（已修）之外的另一个并发缺陷：路由键
     * sessionId 本身在并发执行下就不可信。
     *
     * 因此所有 ffmpeg/ffprobe 执行（含 begin/execute/end 全程）必须持有此锁：
     * 进程内任一时刻至多一个会话在执行，日志与进度统计的归属就不会串。
     */
    private val executeMutex = Mutex()

    /** 串行执行一个 ffmpeg/ffprobe 会话；block 返回即会话结束、锁释放 */
    suspend fun <T> withExecuteLock(block: suspend () -> T): T =
        executeMutex.withLock { block() }

    @Volatile
    private var initialized = false

    /** 进程内注册一次全局回调；线程安全，可放心重复调用 */
    fun init() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            FFmpegKitConfig.enableLogCallback { log ->
                val id = log.sessionId
                logBuffers[id]?.append(log.message)
                spills[id]?.let { s ->
                    try {
                        s.writer.write(log.message)
                    } catch (e: Exception) {
                        s.failed = true
                    }
                }
            }
            FFmpegKitConfig.enableStatisticsCallback { stat: Statistics ->
                statHandlers[stat.sessionId]?.invoke(stat.time, stat.speed)
            }
            initialized = true
        }
    }

    /** 开始内存采集该会话日志（重复 begin 视为重置） */
    fun beginLogs(sessionId: Long) {
        logBuffers[sessionId] = StringBuilder()
    }

    /** 当前内存采集缓冲的长度（未在采集返回 -1）：日志排水采样用 */
    fun logLength(sessionId: Long): Long =
        logBuffers[sessionId]?.length?.toLong() ?: -1L

    /**
     * 结束内存采集并返回完整日志。
     * retain=true 时按字节预算定格到 doneLogs（供完成回调之后才消费的场景，如
     * extractError；单条只保尾部、总量封顶，防巨型日志积压）；探测类输出立即
     * 消费，retain=false 即取即弃。
     */
    fun endLogs(sessionId: Long, retain: Boolean = true): String {
        val sb = logBuffers.remove(sessionId) ?: return ""
        val out = sb.toString()
        if (retain) {
            val kept = if (out.length > DONE_MAX_ENTRY_CHARS) {
                out.substring(out.length - DONE_MAX_ENTRY_CHARS)
            } else out
            synchronized(doneLogs) {
                doneLogs.remove(sessionId)?.let { old ->
                    doneBytes.addAndGet(-old.length.toLong())
                }
                doneBytes.addAndGet(kept.length.toLong())
                doneLogs[sessionId] = kept
            }
        }
        return out
    }

    /** 取走已定格的会话日志（无则 null），取后即删 */
    fun takeDoneLogs(sessionId: Long): String? = synchronized(doneLogs) {
        doneLogs.remove(sessionId)?.also {
            doneBytes.addAndGet(-it.length.toLong())
        }
    }

    /**
     * 开始磁盘溢写：日志边收边写入 dir 下的临时文件（内存模式返回 null 表示磁盘
     * 不可用，调用方应退回内存模式）。同一时刻每会话只占用一个临时文件。
     */
    fun beginSpill(sessionId: Long, dir: File): Spill? {
        return try {
            if (!dir.exists()) dir.mkdirs()
            // 顺手清理上次进程被杀遗留的残文件（>24h 的）
            dir.listFiles()?.forEach { f ->
                if (f.isFile && System.currentTimeMillis() - f.lastModified() > SPILL_MAX_AGE_MS) {
                    try { f.delete() } catch (_: Exception) {}
                }
            }
            val file = File(dir, "probe_$sessionId.csv")
            val spill = Spill(file, BufferedWriter(FileWriter(file)))
            spills[sessionId] = spill
            spill
        } catch (e: Exception) {
            null
        }
    }

    /** 结束磁盘溢写（关闭句柄）；返回句柄供消费，未开始过则 null */
    fun endSpill(sessionId: Long): Spill? {
        val s = spills.remove(sessionId) ?: return null
        try {
            s.writer.flush()
            s.writer.close()
        } catch (e: Exception) {
            s.failed = true
        }
        return s
    }

    /** 登记进度统计处理器 */
    fun beginStats(sessionId: Long, handler: (timeMs: Double, speed: Double) -> Unit) {
        statHandlers[sessionId] = handler
    }

    /** 注销进度统计处理器 */
    fun endStats(sessionId: Long) {
        statHandlers.remove(sessionId)
    }

    /** 彻底清理该会话的全部痕迹（取消/异常路径防泄漏） */
    fun cleanup(sessionId: Long) {
        logBuffers.remove(sessionId)
        doneLogs.remove(sessionId)?.let { doneBytes.addAndGet(-it.length.toLong()) }
        statHandlers.remove(sessionId)
        spills.remove(sessionId)?.let { s ->
            try { s.writer.close() } catch (_: Exception) {}
            s.delete()
        }
    }

    /** 磁盘溢写句柄：failed 置位表示写入中途出错，内容不可信 */
    class Spill internal constructor(val file: File, internal val writer: BufferedWriter) {
        @Volatile
        internal var failed = false

        fun delete() {
            try {
                file.delete()
            } catch (_: Exception) {
            }
        }
    }
}
