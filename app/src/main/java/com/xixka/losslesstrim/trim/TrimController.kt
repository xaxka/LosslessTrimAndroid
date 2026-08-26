package com.xixka.losslesstrim.trim

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import com.xixka.losslesstrim.data.AppSettings
import com.xixka.losslesstrim.data.FileResult
import com.xixka.losslesstrim.data.PerFileOverride
import com.xixka.losslesstrim.data.VideoEntry

/** 队列里待处理的一个文件（设置快照 + 该文件覆盖参数） */
data class TrimJob(
    val entry: VideoEntry,
    val settings: AppSettings,
    val override: PerFileOverride?,
    /** 单文件模式：另存为目标（CreateDocument 结果）；为空时走目录内 .part 流程 */
    val outputUri: Uri? = null,
)

/** 处理页 UI 状态 */
sealed interface QueueUi {
    data object Idle : QueueUi
    data class Running(
        val done: Int,
        val total: Int,
        val currentName: String,
        val progress: Float,
        val speed: String,
    ) : QueueUi

    data class Finished(val results: List<FileResult>) : QueueUi
}

/** UI 与前台 Service 之间的桥（进程内单例） */
object TrimController {

    val queueUi = kotlinx.coroutines.flow.MutableStateFlow<QueueUi>(QueueUi.Idle)
    val lastResults = kotlinx.coroutines.flow.MutableStateFlow<List<FileResult>>(emptyList())

    @Volatile var cancelRequested: Boolean = false
    @Volatile var running: Boolean = false

    @Volatile private var pendingJobs: List<TrimJob> = emptyList()

    /** 待启动请求：start() 占位后置位，Service 端首个 onStartCommand 认领（原子取走） */
    @Volatile private var startRequested = false
    private val lock = Any()

    /** 返回 false = 已有队列在运行或服务启动失败（调用方据此提示用户，而不是静默丢弃） */
    fun start(context: Context, jobs: List<TrimJob>): Boolean {
        synchronized(lock) {
            if (running) return false
            pendingJobs = jobs
            cancelRequested = false
            // 提前占位：running 若只在 Service 协程里置位，startForegroundService 的
            // IPC 延迟期间二次 start() 会通过检查并覆盖 pendingJobs，导致两条队列
            // 并发执行（两个 ffmpeg 同时写同一个 .part）或首批任务被静默丢弃
            running = true
            // 与占位分离的启动信号：Service 不能再用 !running 判断是否启动队列——
            // running 已被上面提前置 true，否则 runQueue 永远不会启动，服务挂着
            // "准备中…"通知空转，处理页一直停在"队列未在运行"
            startRequested = true
        }
        val intent = Intent(context, TrimService::class.java).setAction(TrimService.ACTION_START)
        return try {
            ContextCompat.startForegroundService(context, intent)
            releaseUiCachesForBatch()
            true
        } catch (e: Exception) {
            synchronized(lock) {
                running = false
                startRequested = false
                pendingJobs = emptyList()
            }
            false
        }
    }

    /**
     * 队列启动即释放 UI 层可重建缓存（缩略图 memCache / 扫描·探测 L1 / 会话日志）。
     *
     * 批处理期间主页与分析页不可见，这些缓存纯白占内存；前台服务一跑几十分钟，
     * 进程常驻内存每多 10MB，LMK 杀掉的后台应用（音乐/IM/浏览器）就多一批。
     * 磁盘层（Room probe L2、关键帧 L2、缩略图 JPEG）保留——回主页重扫、列表
     * 出图仍秒级；正在运行的 ffmpeg 会话缓冲不受影响（各 onTrimMemory 均不触
     * 活动会话）。单个模块异常不拖累启动主路径。
     */
    private fun releaseUiCachesForBatch() {
        val low = android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
        try { com.xixka.losslesstrim.util.ThumbStore.onTrimMemory(low) } catch (_: Exception) {}
        try { com.xixka.losslesstrim.data.Scanner.onTrimMemory(low) } catch (_: Exception) {}
        try { com.xixka.losslesstrim.ffmpeg.Probe.onTrimMemory(low) } catch (_: Exception) {}
        try { com.xixka.losslesstrim.ffmpeg.SessionBridge.onTrimMemory(low) } catch (_: Exception) {}
    }

    /**
     * Service 端认领启动请求：仅 start() 成功后的首个 onStartCommand 返回 true
     * （重复投递/队列已在跑时返回 false，不会启动第二条队列）。
     */
    fun takeStartRequest(): Boolean {
        synchronized(lock) {
            val r = startRequested
            startRequested = false
            return r
        }
    }

    fun cancel() {
        cancelRequested = true
    }

    fun takeJobs(): List<TrimJob> = pendingJobs
}
