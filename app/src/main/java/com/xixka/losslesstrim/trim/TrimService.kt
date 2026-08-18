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
            ACTION_CANCEL -> TrimController.cancel()
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
                if (res.outcome == Outcome.CANCELLED) {
                    stopped = true
                    break
                }
                idx++
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
            if (rc == null || !rc.isValueSuccess || TrimController.cancelRequested) {
                DocUtils.delete(this, job.outputUri)
                return FileResult(
                    entry, plan,
                    if (rc != null && rc.isValueCancel) Outcome.CANCELLED else Outcome.FAILED,
                    entry.sizeBytes,
                    reason = if (rc == null) "ffmpeg 会话异常结束" else extractError(session!!)
                )
            }
            val newSize = DocUtils.length(this, job.outputUri).coerceAtLeast(0)
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

        // 8. 成功：替换文件
        var newSize = DocUtils.length(this, partUri)
        var finalUri: Uri? = null
        if (s.overwrite) {
            if (DocUtils.exists(this, entry.docUri)) {
                DocUtils.delete(this, entry.docUri)
            }
            finalUri = DocUtils.rename(this, partUri, finalName)
            if (finalUri == null) {
                finalUri = DocUtils.copyTo(this, partUri, outFolder, target.mime, finalName)
                if (finalUri != null) DocUtils.delete(this, partUri)
            }
        } else {
            DocUtils.findChild(this, outFolder, finalName)?.let { DocUtils.delete(this, it) }
            finalUri = DocUtils.rename(this, partUri, finalName)
            if (finalUri == null) {
                finalUri = DocUtils.copyTo(this, partUri, outFolder, target.mime, finalName)
                if (finalUri != null) DocUtils.delete(this, partUri)
            }
        }
        if (finalUri == null) {
            return FileResult(
                entry, plan, Outcome.FAILED, entry.sizeBytes,
                reason = "输出替换失败（数据完整保留在 $partName，可手动改名）"
            )
        }
        if (newSize <= 0) newSize = DocUtils.length(this, finalUri)
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
        val logs = session.allLogsAsString ?: return "ffmpeg 失败（无日志）"
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

    /** 挂起等待 ffmpeg 完成，返回 session；协程取消时会触发 FFmpegKit.cancel */
    private suspend fun runFfmpeg(
        cmd: String,
        onStat: (timeMs: Double, speed: Double) -> Unit,
    ): FFmpegSession? = suspendCancellableCoroutine { cont ->
        val session = FFmpegKit.executeAsync(
            cmd,
            { s -> if (cont.isActive) cont.resume(s) },
            { /* 日志由 session 收集 */ },
            { stat -> onStat(stat.time, stat.speed) },
        )
        cont.invokeOnCancellation {
            try {
                FFmpegKit.cancel(session.sessionId)
            } catch (_: Exception) {
            }
        }
    }
}
