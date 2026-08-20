package com.xixka.losslesstrim.trim

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentUris
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.FFmpegKitConfig
import com.antonkarpenko.ffmpegkit.FFmpegSession
import com.xixka.losslesstrim.data.FileResult
import com.xixka.losslesstrim.data.Outcome
import com.xixka.losslesstrim.data.TrimPlan
import com.xixka.losslesstrim.data.VideoEntry
import com.xixka.losslesstrim.ffmpeg.Probe
import com.xixka.losslesstrim.ffmpeg.SessionBridge
import com.xixka.losslesstrim.util.Formats
import com.xixka.losslesstrim.util.StorageAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume

/**
 * 前台服务：串行执行无损剪辑队列。
 * 流程：ffprobe 关键帧 → 计算对齐切点 → ffmpeg -c copy 写 .part → 成功后删原文件并重命名。
 * 全直路径 I/O：须已授予"所有文件"权限，ffmpeg 直接读写真实路径（faststart
 * 可靠）；SAF(saf:) 通道已移除，路径不可定位直接失败并提示。
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
            else -> {
                startAsForeground()
                // 认领启动请求：running 已被 start() 提前占位置 true（防重入），
                // 不能再用 !running 判断，否则 runQueue 永远不会被启动
                if (TrimController.takeStartRequest()) {
                    serviceScope.launch { runQueue() }
                } else if (!TrimController.running) {
                    // 防御：无待启动请求且无队列在跑（重复投递/启动失败回滚后的
                    // 残留投递），撤前台通知直接退出，避免"准备中…"僵尸通知
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
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

        // 1. 逻辑计划先行：不可处理/剪完为空的文件直接跳过，不做任何扫描
        val logical = TrimPlanner.logicalPlan(entry, s, job.override)
        if (!logical.ok) {
            return FileResult(entry, logical, Outcome.SKIPPED, entry.sizeBytes, reason = logical.skipReason)
        }

        // 2. 关键帧对齐：只对"真要切"的文件探测，且只读切点邻域（-read_intervals
        //    定点读几 MB）而非整文件全量扫描（GB 级 4K 片源每次整读数十秒，是
        //    "每处理完一个文件干等半天"的主因）。全片保留的文件对齐无意义，
        //    直接用逻辑计划（对齐 0/片长最多各缩一个 GOP，不值得为此读全片）
        val dur = entry.probe.durationSec
        val needTrim = logical.requestedStart > 0.001 || logical.requestedEnd < dur - 0.001
        val plan = if (needTrim) {
            val kfs = Probe.probeKeyframesNear(
                this, entry.docUri,
                listOf(logical.requestedStart, logical.requestedEnd), dur
            )
            TrimPlanner.alignedPlan(entry, s, job.override, kfs)
        } else {
            logical
        }
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

        // SAF(saf:)读通道已移除：fork 的 SAF 参数构造存在越界崩溃且描述符读慢。
        // filePath 必须存在（扫描时已按授权状态记录），缺失即判定为配置问题
        val inParam = entry.filePath?.let { File(it).takeIf { f -> f.exists() }?.absolutePath }
            ?: return FileResult(
                entry, plan, Outcome.FAILED, entry.sizeBytes,
                reason = "无法定位源文件路径（未授予\u201c所有文件\u201d权限？SAF 通道已移除，授权后请重扫）"
            )
        val durSec = plan.duration

        // ---- 单文件模式：直接写另存目标（无目录写权限，不走 .part/rename） ----
        // 直路径写出：绕开 saf: 只写描述符——faststart 收尾要回 seek 移数据重写
        // moov，只写 SAF fd 上不可靠，正是"输出校验失败 moov atom not found"的
        // 根因。SAF 写通道已移除，目标不可定位直接失败并提示。
        if (job.outputUri != null) {
            val outFile = StorageAccess.writableTarget(this, job.outputUri)
                ?: return FileResult(
                    entry, plan, Outcome.FAILED, entry.sizeBytes,
                    reason = "另存目标无法定位为本地路径（未授权或非本地存储），SAF 通道已移除"
                )
            val cmd = buildCommand(inParam, outFile.absolutePath, plan, kept, target, entry)
            val session = runFfmpeg(cmd) { timeMs, speed ->
                val p = (timeMs / 1000.0 / durSec).toFloat().coerceIn(0f, 1f)
                publishRunning(idx, total, entry.name, p, String.format(Locale.US, "%.1fx", speed))
            }
            val rc = session?.returnCode
            if (rc == null) {
                outFile.delete()
                return FileResult(entry, plan, Outcome.FAILED, entry.sizeBytes, reason = "ffmpeg 会话异常结束")
            }
            if (rc.isValueCancel || TrimController.cancelRequested) {
                outFile.delete()
                return FileResult(entry, plan, Outcome.CANCELLED, entry.sizeBytes, reason = "已取消（原文件未动）")
            }
            if (!rc.isValueSuccess) {
                outFile.delete()
                return FileResult(entry, plan, Outcome.FAILED, entry.sizeBytes, reason = extractError(session))
            }
            val newSize = outFile.length().coerceAtLeast(0)
            // 轻量终检：只读容器头（hev1 内嵌参数集的 HEVC 全量 -show_streams 会读码流包）
            val outProbe = Probe.verifyMedia(outFile.absolutePath)
            if (newSize <= 0 || !outProbe.probeOk) {
                outFile.delete()
                return FileResult(
                    entry, plan, Outcome.FAILED, entry.sizeBytes,
                    reason = "输出校验失败（${outProbe.error ?: "空文件"}），请重试"
                )
            }
            publishRunning(idx + 1, total, entry.name, 1f, "")
            refreshMediaStore(outFile.absolutePath)
            return FileResult(entry, plan, Outcome.SUCCESS, entry.sizeBytes, newSize, reason = "已另存为新文件")
        }

        // ---- 目录模式：输出目录（直路径）与目标文件名 ----
        // SAF 输出管线已移除：输出目录必须能定位为本地路径，否则直接失败。
        val inDirFile = StorageAccess.accessibleFile(this, entry.folderUri ?: return FileResult(
            entry, plan, Outcome.FAILED, entry.sizeBytes, reason = "缺少目录信息"
        ))?.takeIf { it.isDirectory }
            ?: return FileResult(
                entry, plan, Outcome.FAILED, entry.sizeBytes,
                reason = "输出目录无法定位为本地路径（未授权或非本地存储），SAF 通道已移除"
            )
        val outDirFile: File
        val finalName: String
        if (s.overwrite) {
            outDirFile = inDirFile
            finalName = if (target.ext == entry.ext) entry.name else "${entry.baseName}.${target.ext}"
        } else {
            outDirFile = ensureCutDir(inDirFile)
                ?: return FileResult(
                    entry, plan, Outcome.FAILED, entry.sizeBytes,
                    reason = "无法创建/访问 CutVideos 子目录"
                )
            finalName = "${entry.baseName}.${target.ext}"
        }

        // ---- 直路径管线：普通文件 I/O ----
        // 写 .part → File 改名替换 → 直路径终检。
        // 规避 saf: 只写描述符上 faststart 回移数据不可靠导致坏 MP4 的问题。
        val partFile = File(outDirFile, "$finalName.part")
        if (partFile.exists()) partFile.delete()

        val cmd = buildCommand(inParam, partFile.absolutePath, plan, kept, target, entry)
        val session = runFfmpeg(cmd) { timeMs, speed ->
            val p = (timeMs / 1000.0 / durSec).toFloat().coerceIn(0f, 1f)
            publishRunning(idx, total, entry.name, p, String.format(Locale.US, "%.1fx", speed))
        }

        val rc = session?.returnCode
        if (rc == null) {
            partFile.delete()
            return FileResult(entry, plan, Outcome.FAILED, entry.sizeBytes, reason = "ffmpeg 会话异常结束")
        }
        if (rc.isValueCancel || TrimController.cancelRequested) {
            partFile.delete()
            return FileResult(entry, plan, Outcome.CANCELLED, entry.sizeBytes, reason = "已取消（原文件未动）")
        }
        if (!rc.isValueSuccess) {
            partFile.delete()
            return FileResult(entry, plan, Outcome.FAILED, entry.sizeBytes, reason = extractError(session))
        }

        // 成功：替换文件（铁律：备份未做成绝不动原片；最终文件未校验绝不删备份）
        val partLen = partFile.length().coerceAtLeast(0)
        val finalFile = File(outDirFile, finalName)
        val origFile = entry.filePath?.let { File(it) }?.takeIf { it.exists() }
            ?: File(outDirFile, entry.name)
        var backupFile: File? = null      // 覆盖模式：原片备份
        var displacedFile: File? = null   // CutVideos 模式：被顶替的旧成片
        if (s.overwrite) {
            if (origFile.exists()) {
                backupFile = File(outDirFile, "${entry.baseName}.trimbackup.${System.currentTimeMillis()}")
                if (!origFile.renameTo(backupFile)) {
                    // 备份改名失败：跳过此文件，原片不动（.part 清理）
                    partFile.delete()
                    return FileResult(
                        entry, plan, Outcome.FAILED, entry.sizeBytes,
                        reason = "无法备份原片（此目录不支持改名），已跳过，原文件未动"
                    )
                }
            }
        } else {
            if (finalFile.exists()) {
                displacedFile = File(outDirFile, "$finalName.oldtrim")
                if (!finalFile.renameTo(displacedFile)) finalFile.delete()
            }
        }
        if (!partFile.renameTo(finalFile)) {
            // 回滚：备份/旧成片还原原名，数据完整
            backupFile?.renameTo(origFile)
            displacedFile?.renameTo(finalFile)
            return FileResult(
                entry, plan, Outcome.FAILED, entry.sizeBytes,
                reason = "输出替换失败（数据完整保留在 ${partFile.name}，可手动改名）"
            )
        }

        // 终检一：最终文件字节数必须与 .part 一致；终检二：轻量探测必须可解析
        // （防 moov 缺失等坏文件冒充成功——直路径下 faststart 可靠，此检查退化为兜底）
        val finalLen = finalFile.length().coerceAtLeast(0)
        val sizeBad = partLen > 0 && finalLen != partLen
        val finalProbe = Probe.verifyMedia(finalFile.absolutePath)
        if (sizeBad || !finalProbe.probeOk) {
            finalFile.delete()
            backupFile?.renameTo(origFile)       // 还原原片
            displacedFile?.renameTo(finalFile)   // 还原旧成片
            return FileResult(
                entry, plan, Outcome.FAILED, entry.sizeBytes,
                reason = "输出校验失败（${finalProbe.error ?: "字节数不一致"}）${if (backupFile != null) "，已回滚为原文件" else ""}，请重试"
            )
        }
        // 校验通过，才允许删除备份与残留
        backupFile?.delete()
        displacedFile?.delete()
        // 刷新 MediaStore：同路径改名替换绕过了媒体库，相册/播放器否则仍按
        // 旧条目显示旧时间轴；覆盖 + 容器转换时旧扩展名路径已不存在，清残留行
        if (s.overwrite) refreshMediaStore(finalFile.absolutePath, origFile.absolutePath)
        else refreshMediaStore(finalFile.absolutePath)
        publishRunning(idx + 1, total, entry.name, 1f, "")
        return FileResult(entry, plan, Outcome.SUCCESS, entry.sizeBytes, finalLen)
    }

    /** CutVideos 子目录（直路径）：已存在直接复用，否则 mkdirs 创建 */
    private fun ensureCutDir(parent: File): File? {
        val dir = File(parent, "CutVideos")
        return when {
            dir.isDirectory -> dir
            dir.mkdir() || dir.mkdirs() -> dir
            else -> null
        }
    }

    /**
     * 剪辑落盘后刷新 MediaStore。覆盖模式成品与原片**同路径**（File 改名替换），
     * 全程绕过 MediaStore——系统相册/播放器读到的仍是旧条目（旧时长、旧缩略图），
     * 表现为"切完还显示原来的时间轴"。对仍存在的路径触发媒体扫描让元数据归位；
     * 已不存在的路径（容器转换换扩展名后原文件被改名走）顺手清残留行。
     */
    private fun refreshMediaStore(vararg paths: String) {
        val existing = paths.filter { File(it).exists() }.distinct()
        if (existing.isNotEmpty()) {
            try {
                MediaScannerConnection.scanFile(this, existing.toTypedArray(), null, null)
            } catch (_: Exception) {
            }
        }
        for (p in paths.filterNot { File(it).exists() }) removeStaleMediaRow(p)
    }

    /** 清掉指向已不存在文件的 MediaStore 残留行（API 29+；尽力而为，失败不影响剪辑结果） */
    private fun removeStaleMediaRow(path: String) {
        if (Build.VERSION.SDK_INT < 29) return
        try {
            val filesUri = MediaStore.Files.getContentUri("external")
            val ids = ArrayList<Long>()
            contentResolver.query(
                filesUri,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DATA}=?",
                arrayOf(path),
                null,
            )?.use { c ->
                while (c.moveToNext()) ids.add(c.getLong(0))
            }
            // 先收齐再删：避免边遍历边删导致游标窗口失效
            for (id in ids) {
                contentResolver.delete(ContentUris.withAppendedId(filesUri, id), null, null)
            }
        } catch (_: Exception) {
        }
    }

    private fun buildCommand(
        inParam: String,
        outParam: String,
        plan: TrimPlan,
        kept: List<Int>,
        target: OutputTarget,
        entry: VideoEntry,
    ): String {
        // ffmpeg 对未设 AVFMT_SEEK_TO_PTS 的封装（matroska/webm 等）在视频含 B 帧时，
        // 会把 -ss 的 seek 目标前移 3/23s（≈130.4ms，DTS 启发修正，ffmpeg_opt.c）；
        // MKV 的 Cues 条目又精确落在各关键帧 PTS 上，目标被前移后向后搜索命中前一个
        // 关键帧，实际起点比对齐点早一个 GOP（详见 docs/mkv-bframe-seek-offset.md）。
        // 这里把该量预先补回：-ss 加 0.131（3/23 向上取整到 ms，抵消 secs3 格式化
        // 舍入后仍留 ~66µs 余量）；-t 锚定在 -ss 值上（停止条件 pts ≥ ss+t），
        // 须同步减去同量，终点才能仍落在 actualEnd。
        // 门控：matroska/webm 输入 && 视频含 B 帧 && 起点在片中（片头无"更早的
        // 关键帧"可错落，不加）。hasBFrames 未知（旧缓存/平台兜底）按含 B 帧处理：
        // 无 B 帧误加仅当 GOP<131ms 才可能出错（极罕见），反向漏加则稳定复现本 bug。
        val matroskaIn = entry.probe.formatName.split(',').any {
            val f = it.trim(); f == "matroska" || f == "webm"
        }
        val hasB = entry.probe.streams.firstOrNull { it.isVideo }?.hasBFrames ?: 1
        val fudge = if (matroskaIn && plan.actualStart > 0.001 && hasB > 0) 0.131 else 0.0
        val ss = plan.actualStart + fudge
        val dur = (plan.actualEnd - ss).coerceAtLeast(0.001)
        val sb = StringBuilder()
        sb.append("-hide_banner -y -ss ").append(Formats.secs3(ss))
        sb.append(" -noaccurate_seek -i \"").append(inParam).append("\"")
        sb.append(" -t ").append(Formats.secs3(dur))
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
     * 执行全程持有 SessionBridge 全局执行锁（至会话完成回调）：并发执行多个
     * 会话时日志的会话归属不可靠——扫描 ffprobe 与本 ffmpeg 重叠时，本会话
     * stderr 行会串进 ffprobe 的 JSON 输出（"不可处理"误判），反之亦然。
     */
    private suspend fun runFfmpeg(
        cmd: String,
        onStat: (timeMs: Double, speed: Double) -> Unit,
    ): FFmpegSession? = SessionBridge.withExecuteLock {
        suspendCancellableCoroutine { cont ->
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
}
