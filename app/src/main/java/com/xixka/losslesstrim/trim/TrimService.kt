package com.xixka.losslesstrim.trim

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.FFmpegKitConfig
import com.antonkarpenko.ffmpegkit.FFmpegSession
import com.xixka.losslesstrim.data.FileResult
import com.xixka.losslesstrim.data.Outcome
import com.xixka.losslesstrim.data.TrimPlan
import com.xixka.losslesstrim.ffmpeg.Probe
import com.xixka.losslesstrim.ffmpeg.SessionBridge
import com.xixka.losslesstrim.util.Formats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

/**
 * 前台服务：串行执行无损剪辑队列。
 * 流程：ffprobe 关键帧 → 计算对齐切点 → ffmpeg -c copy 写 .part → 成功后删原文件并重命名。
 */
class TrimService : Service() {

    companion object {
        const val ACTION_START = "com.xixka.losslesstrim.ACTION_START"
        const val ACTION_CANCEL = "com.xixka.losslesstrim.ACTION_CANCEL"
        const val CHANNEL_ID = "trim_queue"
        const val NOTIF_ID = 1001
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var lastNotifyAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                TrimController.cancel()
                // 立即中断正在运行的 ffmpeg 会话（否则当前文件会完整跑完才停）
                try {
                    FFmpegKit.cancel()
                } catch (_: Exception) {
                }
                if (!TrimController.running) stopSelf()
            }
            ACTION_START -> {
                startAsForeground()
                if (!TrimController.running) {
                    serviceScope.launch { runQueue() }
                }
            }
            else -> {
                startAsForeground()
                if (!TrimController.running) {
                    serviceScope.launch { runQueue() }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        val ch = NotificationChannel(
            CHANNEL_ID, "批量剪辑进度", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "无损批量剪辑处理进度"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun startAsForeground() {
        val n = buildNotification(0, 0, "准备中…", 0f, "")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun buildNotification(done: Int, total: Int, name: String, progress: Float, speed: String): Notification {
        val cancelIntent = PendingIntent.getService(
            this, 2,
            Intent(this, TrimService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (total > 0) "无损批量剪辑 ${done.coerceAtMost(total)}/$total" else "无损批量剪辑"
        val text = buildString {
            append(name)
            if (speed.isNotEmpty()) append("  ").append(speed)
            if (progress > 0f) append("  ").append((progress * 100).toInt()).append("%")
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .apply {
                if (total > 0) setProgress(total, done.coerceAtMost(total), false)
                else setProgress(0, 0, true)
            }
            .addAction(0, "取消", cancelIntent)
            .build()
    }

    private fun notifyProgress(done: Int, total: Int, name: String, progress: Float, speed: String) {
        val now = System.currentTimeMillis()
        if (now - lastNotifyAt < 400 && progress < 1f) return
        lastNotifyAt = now
        try {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            nm.notify(NOTIF_ID, buildNotification(done, total, name, progress, speed))
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
    }

    private fun publishRunning(done: Int, total: Int, name: String, progress: Float, speed: String) {
        TrimController.queueUi.value = QueueUi.Running(done, total, name, progress, speed)
        notifyProgress(done, total, name, progress, speed)
    }

    // ---------------- 队列主流程 ----------------

    private suspend fun runQueue() {
        TrimController.running = true
        try {
            val jobs = TrimController.takeJobs()
            val results = ArrayList<FileResult>()
            var idx = 0
            var stopped = false
            while (idx < jobs.size) {
                if (TrimController.cancelRequested) {
                    stopped = true
                    break
                }
                val job = jobs[idx]
                val res = processJob(job, idx, jobs.size)
                results += res
                // 先自增再判取消：当前文件的结果已入列，补录从未处理的下一个开始，
                // 否则同一文件会被补录第二条 CANCELLED，导致结果页 key 冲突崩溃
                idx++
                if (res.outcome == Outcome.CANCELLED) {
                    stopped = true
                    break
                }
            }
            if (stopped) {
                for (j in idx until jobs.size) {
                    results += FileResult(
                        entry = jobs[j].entry,
                        plan = TrimPlanner.logicalPlan(jobs[j].entry, jobs[j].settings, jobs[j].override),
                        outcome = Outcome.CANCELLED,
                        origSize = jobs[j].entry.sizeBytes,
                        reason = "已取消（未处理）",
                    )
                }
            }
            TrimController.lastResults.value = results
            TrimController.queueUi.value = QueueUi.Finished(results)
        } finally {
            TrimController.running = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun processJob(job: TrimJob, idx: Int, total: Int): FileResult {
        val entry = job.entry
        val s = job.settings
        publishRunning(idx, total, entry.name, 0f, "")

        // 1. 关键帧（有缓存则直接复用）
        val keyframes = Probe.probeKeyframes(this, entry.docUri)

        // 2. 对齐后的计划
        val plan = TrimPlanner.alignedPlan(entry, s, job.override, keyframes)
        if (!plan.ok) {
            return FileResult(entry, plan, Outcome.SKIPPED, entry.sizeBytes, reason = plan.skipReason)
        }

        // 5. 输出目标：单文件另存为 / 目录内 .part 流程
        val target = Containers.resolve(s.container, entry.ext)
            ?: return FileResult(
                entry, plan, Outcome.FAILED, entry.sizeBytes,
                reason = "不支持的容器 .${entry.ext}，请改用 MP4/MKV 输出"
            )

        // 轨道映射（按勾选逐轨 -map 0:i）
        val dropped = job.override?.droppedStreams ?: emptySet()
        val kept = entry.probe.streams.map { it.index }.filter { it !in dropped }
        if (kept.isEmpty()) {
            return FileResult(entry, plan, Outcome.FAILED, entry.sizeBytes, reason = "未保留任何轨道")
        }

        // 无实际改动（全片保留、未丢轨道、容器不变且为覆盖模式）：跳过重写，
        // 避免"未设置裁剪"的文件被无意义地删除重建（覆盖模式下原文件会被替换）
        if (job.outputUri == null && s.overwrite &&
            plan.actualStart <= 0.001 &&
            plan.actualEnd >= entry.probe.durationSec - 0.001 &&
            target.ext == entry.ext && dropped.isEmpty()
        ) {
            return FileResult(
                entry, plan, Outcome.SKIPPED, entry.sizeBytes,
                reason = "全片保留、未丢轨道且容器不变，无需处理"
            )
        }

        val inParam = FFmpegKitConfig.getSafParameterForRead(this, entry.docUri)
        val durSec = plan.duration

        // ---- 单文件模式：直接写另存目标（无目录写权限，不走 .part/rename） ----
        if (job.outputUri != null) {
            val outParam = FFmpegKitConfig.getSafParameterForWrite(this, job.outputUri)
            val cmd = buildCommand(inParam, outParam, plan, kept, target)
            val session = runFfmpeg(cmd) { timeMs, speed ->
                val p = (timeMs / 1000.0 / durSec).toFloat().coerceIn(0f, 1f)
                publishRunning(idx, total, entry.name, p, String.format(Locale.US, "%.1fx", speed))
            }
            val rc = session?.returnCode
            if (rc == null) {
                DocUtils.delete(this, job.outputUri)
                return FileResult(entry, plan, Outcome.FAILED, entry.sizeBytes, reason = "ffmpeg 会话异常结束")
            }
            if (rc.isValueCancel || TrimController.cancelRequested) {
                DocUtils.delete(this, job.outputUri)
                return FileResult(entry, plan, Outcome.CANCELLED, entry.sizeBytes, reason = "已取消（原文件未动）")
            }
            if (!rc.isValueSuccess) {
                DocUtils.delete(this, job.outputUri)
                return FileResult(entry, plan, Outcome.FAILED, entry.sizeBytes, reason = extractError(session))
            }
            val newSize = DocUtils.length(this, job.outputUri).coerceAtLeast(0)
            // 终检：输出必须真实可解析（防"ffprobe 找不到 moov"这类坏文件冒充成功）
            val outProbe = Probe.probeMedia(this, job.outputUri)
            if (newSize <= 0 || !outProbe.probeOk) {
                DocUtils.delete(this, job.outputUri)
                return FileResult(
                    entry, plan, Outcome.FAILED, entry.sizeBytes,
                    reason = "输出校验失败（${outProbe.error ?: "空文件"}），请重试"
                )
            }
            publishRunning(idx + 1, total, entry.name, 1f, "")
            return FileResult(entry, plan, Outcome.SUCCESS, entry.sizeBytes, newSize, reason = "已另存为新文件")
        }

        // ---- 目录模式：输出目录与目标文件名 ----
        val outFolder: Uri
        val finalName: String
        if (s.overwrite) {
            outFolder = entry.folderUri ?: return FileResult(
                entry, plan, Outcome.FAILED, entry.sizeBytes, reason = "缺少目录权限"
            )
            finalName = if (target.ext == entry.ext) entry.name else "${entry.baseName}.${target.ext}"
        } else {
            val cutDir = ensureCutDir(entry.folderUri ?: return FileResult(
                entry, plan, Outcome.FAILED, entry.sizeBytes, reason = "缺少目录权限"
            ))
                ?: return FileResult(
                    entry, plan, Outcome.FAILED, entry.sizeBytes,
                    reason = "无法创建/访问 CutVideos 子目录"
                )
            outFolder = cutDir
            finalName = "${entry.baseName}.${target.ext}"
        }

        // 6. 创建 .part 临时文件
        val partName = "$finalName.part"
        DocUtils.findChild(this, outFolder, partName)?.let { DocUtils.delete(this, it) }
        val partUri = DocUtils.create(this, outFolder, "application/octet-stream", partName)
            ?: return FileResult(
                entry, plan, Outcome.FAILED, entry.sizeBytes,
                reason = "无法创建临时文件 $partName"
            )

        // 7. 执行 ffmpeg（stream copy）
        val outParam = FFmpegKitConfig.getSafParameterForWrite(this, partUri)
        val cmd = buildCommand(inParam, outParam, plan, kept, target)
        val session = runFfmpeg(cmd) { timeMs, speed ->
            val p = (timeMs / 1000.0 / durSec).toFloat().coerceIn(0f, 1f)
            publishRunning(idx, total, entry.name, p, String.format(Locale.US, "%.1fx", speed))
        }

        val rc = session?.returnCode
        if (rc == null) {
            DocUtils.delete(this, partUri)
            return FileResult(entry, plan, Outcome.FAILED, entry.sizeBytes, reason = "ffmpeg 会话异常结束")
        }
        if (rc.isValueCancel || TrimController.cancelRequested) {
            DocUtils.delete(this, partUri)
            return FileResult(entry, plan, Outcome.CANCELLED, entry.sizeBytes, reason = "已取消（原文件未动）")
        }
        if (!rc.isValueSuccess) {
            DocUtils.delete(this, partUri)
            return FileResult(entry, plan, Outcome.FAILED, entry.sizeBytes, reason = extractError(session))
        }

        // 8. 成功：替换文件（铁律：备份未做成绝不动原片；最终文件未校验绝不删备份）
        val partLen = DocUtils.length(this, partUri).coerceAtLeast(0)
        var finalUri: Uri? = null
        var backupUri: Uri? = null      // 覆盖模式：原片备份
        var displacedUri: Uri? = null   // CutVideos 模式：被顶替的旧成片
        if (s.overwrite) {
            val backupName = "${entry.baseName}.trimbackup.${System.currentTimeMillis()}"
            if (DocUtils.exists(this, entry.docUri)) {
                backupUri = DocUtils.rename(this, entry.docUri, backupName)
                if (backupUri == null) {
                    // 备份改名失败（该目录不支持 rename 等）：直接跳过此文件，
                    // 绝不再走"先删原件再拷贝"的老路——那样一旦中途闪退就留下无备份的半截文件
                    DocUtils.delete(this, partUri)
                    return FileResult(
                        entry, plan, Outcome.FAILED, entry.sizeBytes,
                        reason = "无法备份原片（此目录不支持改名），已跳过，原文件未动"
                    )
                }
            }
            finalUri = DocUtils.rename(this, partUri, finalName)
            if (finalUri == null) {
                finalUri = DocUtils.copyTo(this, partUri, outFolder, target.mime, finalName)
                if (finalUri != null) DocUtils.delete(this, partUri)
            }
        } else {
            val existing = DocUtils.findChild(this, outFolder, finalName)
            if (existing != null) {
                // 旧成片先改名挪走而不是直接删：万一新输出校验失败还能还原
                displacedUri = DocUtils.rename(this, existing, "$finalName.oldtrim")
                if (displacedUri == null) DocUtils.delete(this, existing)
            }
            finalUri = DocUtils.rename(this, partUri, finalName)
            if (finalUri == null) {
                finalUri = DocUtils.copyTo(this, partUri, outFolder, target.mime, finalName)
                if (finalUri != null) DocUtils.delete(this, partUri)
            }
        }
        if (finalUri == null) {
            // 回滚：备份/旧成片还原原名，数据完整
            backupUri?.let { DocUtils.rename(this, it, entry.name) }
            displacedUri?.let { DocUtils.rename(this, it, finalName) }
            return FileResult(
                entry, plan, Outcome.FAILED, entry.sizeBytes,
                reason = "输出替换失败（数据完整保留在 $partName，可手动改名）"
            )
        }

        // 终检一：最终文件字节数必须与 .part 一致（中途被打断的拷贝会缺尾）
        val finalLen = DocUtils.length(this, finalUri).coerceAtLeast(0)
        val sizeBad = partLen > 0 && finalLen != partLen
        // 终检二：输出必须真的能被 ffprobe 解析（防 moov 缺失等坏文件冒充成功）
        val finalProbe = Probe.probeMedia(this, finalUri)
        if (sizeBad || !finalProbe.probeOk) {
            DocUtils.delete(this, finalUri)
            backupUri?.let { DocUtils.rename(this, it, entry.name) }       // 还原原片
            displacedUri?.let { DocUtils.rename(this, it, finalName) }     // 还原旧成片
            return FileResult(
                entry, plan, Outcome.FAILED, entry.sizeBytes,
                reason = "输出校验失败（${finalProbe.error ?: "字节数不一致"}）${if (backupUri != null) "，已回滚为原文件" else ""}，请重试"
            )
        }
        // 校验通过，才允许删除备份与残留
        backupUri?.let { DocUtils.delete(this, it) }
        displacedUri?.let { DocUtils.delete(this, it) }
        val newSize = if (finalLen > 0) finalLen else partLen
        publishRunning(idx + 1, total, entry.name, 1f, "")
        return FileResult(entry, plan, Outcome.SUCCESS, entry.sizeBytes, newSize)
    }

    private fun ensureCutDir(folderUri: Uri): Uri? {
        DocUtils.findChild(this, folderUri, "CutVideos")?.let { return it }
        return try {
            android.provider.DocumentsContract.createDocument(
                contentResolver, folderUri,
                android.provider.DocumentsContract.Document.MIME_TYPE_DIR, "CutVideos"
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun buildCommand(
        inParam: String,
        outParam: String,
        plan: TrimPlan,
        kept: List<Int>,
        target: OutputTarget,
    ): String {
        val sb = StringBuilder()
        sb.append("-hide_banner -y -ss ").append(Formats.secs3(plan.actualStart))
        sb.append(" -noaccurate_seek -i \"").append(inParam).append("\"")
        sb.append(" -t ").append(Formats.secs3(plan.duration))
        for (i in kept) sb.append(" -map 0:").append(i)
        sb.append(" -c copy -map_metadata 0 -avoid_negative_ts make_zero")
        if (target.muxer == "mp4") sb.append(" -movflags +faststart")
        sb.append(" -f ").append(target.muxer)
        sb.append(" \"").append(outParam).append("\"")
        return sb.toString()
    }

    private fun extractError(session: FFmpegSession): String {
        // 优先取 SessionBridge 定格的完整日志（会话被挤出 ffmpeg-kit 历史时
        // session.allLogsAsString 只有残缺片段），回退到会话自带日志
        val logs = SessionBridge.takeDoneLogs(session.sessionId)
            ?: session.allLogsAsString
            ?: return "ffmpeg 失败（无日志）"
        val lines = logs.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("[info]", true) }
        val interesting = lines.filter {
            it.contains("error", true) || it.contains("invalid", true) ||
                    it.contains("could not", true) || it.contains("failed", true) ||
                    it.contains("only", true) || it.contains("not", true)
        }
        val picked = (interesting.ifEmpty { lines.takeLast(4) }).takeLast(4)
        val reason = picked.joinToString(" | ").take(400)
        return reason.ifEmpty { "ffmpeg 失败（返回码 ${session.returnCode?.value}）" }
    }

    /**
     * 挂起等待 ffmpeg 完成，返回 session；协程取消时会触发 FFmpegKit.cancel。
     * 日志与进度统计均经 SessionBridge 全局回调采集/路由：ffmpeg 运行期间其他
     * ffprobe 会话可能把它挤出 ffmpeg-kit 的会话历史（history=2），会话级回调
     * 会因此丢失（进度冻结、错误日志残缺），全局回调不受影响。
     */
    private suspend fun runFfmpeg(
        cmd: String,
        onStat: (timeMs: Double, speed: Double) -> Unit,
    ): FFmpegSession? = suspendCancellableCoroutine { cont ->
        SessionBridge.init()
        val session = FFmpegSession.create(
            FFmpegKitConfig.parseArguments(cmd),
            { s ->
                SessionBridge.endLogs(s.sessionId)
                SessionBridge.endStats(s.sessionId)
                if (cont.isActive) cont.resume(s)
            },
            null, // 日志经 SessionBridge 采集
            null, // 进度经 SessionBridge 路由
        )
        SessionBridge.beginLogs(session.sessionId)
        SessionBridge.beginStats(session.sessionId) { timeMs, speed -> onStat(timeMs, speed) }
        FFmpegKitConfig.asyncFFmpegExecute(session)
        cont.invokeOnCancellation {
            try {
                FFmpegKit.cancel(session.sessionId)
            } catch (_: Exception) {
            }
            SessionBridge.cleanup(session.sessionId)
        }
    }
}
