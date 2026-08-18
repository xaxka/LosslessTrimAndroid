package com.xixka.losslesstrim.trim

import android.content.Context
import android.content.Intent
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

    private var pendingJobs: List<TrimJob> = emptyList()

    fun start(context: Context, jobs: List<TrimJob>) {
        if (running) return
        pendingJobs = jobs
        cancelRequested = false
        val intent = Intent(context, TrimService::class.java).setAction(TrimService.ACTION_START)
        ContextCompat.startForegroundService(context, intent)
    }

    fun cancel() {
        cancelRequested = true
    }

    fun takeJobs(): List<TrimJob> = pendingJobs
}
