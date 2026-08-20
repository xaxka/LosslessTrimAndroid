package com.xixka.losslesstrim.ffmpeg

import com.antonkarpenko.ffmpegkit.FFmpegKitConfig
import com.antonkarpenko.ffmpegkit.Statistics
import java.util.concurrent.ConcurrentHashMap

/**
 * ffmpeg-kit 会话输出桥。
 *
 * 背景：ffmpeg-kit 的 Java 侧日志/统计回调先按 sessionId 从“会话历史”里查会话，
 * 查不到就把整条消息**静默丢弃**。本应用为防大输出（关键帧 CSV 可达几十 MB）驻留
 * 内存导致 OOM，把会话历史压到了 2 条；而扫描阶段是 4 路 ffprobe 并发，正在运行的
 * 会话随时可能被新会话挤出历史——表现为 ffprobe 输出被拦腰截断（如 JSON 在第
 * 822 字符处突然结束）、剪辑进度回调冻结、失败原因日志残缺。
 *
 * 方案：注册**全局** Log/Statistics 回调（它们的调用不经过会话历史查找，一定会触发），
 * 按 sessionId 路由到进程内缓冲。只有显式 begin() 过的会话才会被记录，其余会话
 * 零开销、零驻留；采集完立即取走释放，不持有已结束会话的任何数据。
 */
object SessionBridge {

    /** 正在采集日志的会话：sessionId → 输出缓冲（仅执行线程写入） */
    private val logBuffers = ConcurrentHashMap<Long, StringBuilder>()

    /** 已结束、日志已定格待消费的会话（ffmpeg 完成回调先于 extractError 消费） */
    private val doneLogs = ConcurrentHashMap<Long, String>()

    /** 进度统计路由：sessionId → 处理器 */
    private val statHandlers = ConcurrentHashMap<Long, (Double, Double) -> Unit>()

    @Volatile
    private var initialized = false

    /** 进程内注册一次全局回调；线程安全，可放心重复调用 */
    fun init() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            FFmpegKitConfig.enableLogCallback { log ->
                logBuffers[log.sessionId]?.append(log.message)
            }
            FFmpegKitConfig.enableStatisticsCallback { stat: Statistics ->
                statHandlers[stat.sessionId]?.invoke(stat.time, stat.speed)
            }
            initialized = true
        }
    }

    /** 开始采集该会话日志（重复 begin 视为重置） */
    fun beginLogs(sessionId: Long) {
        logBuffers[sessionId] = StringBuilder()
    }

    /**
     * 结束采集并返回完整日志。会话已结束后再飘来的零星日志直接丢弃。
     * 结果同时暂存于 doneLogs，供完成回调之后才消费日志的场景（如 extractError）。
     */
    fun endLogs(sessionId: Long): String {
        val sb = logBuffers.remove(sessionId) ?: return ""
        val out = sb.toString()
        doneLogs[sessionId] = out
        return out
    }

    /** 取走已定格的会话日志（无则 null），取后即删 */
    fun takeDoneLogs(sessionId: Long): String? = doneLogs.remove(sessionId)

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
        doneLogs.remove(sessionId)
        statHandlers.remove(sessionId)
    }
}
