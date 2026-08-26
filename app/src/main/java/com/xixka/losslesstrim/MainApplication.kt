package com.xixka.losslesstrim

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentCallbacks2
import com.antonkarpenko.ffmpegkit.FFmpegKitConfig
import com.xixka.losslesstrim.data.Scanner
import com.xixka.losslesstrim.ffmpeg.Probe
import com.xixka.losslesstrim.ffmpeg.SessionBridge
import com.xixka.losslesstrim.trim.TrimService
import com.xixka.losslesstrim.util.ThumbStore

class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // ffmpeg-kit 默认保留最近 10 个会话（连同全部日志缓冲）驻内存；
        // 关键帧探测的输出可达几十 MB，积压多个会话是批处理时 OOM 的诱因之一。
        // 历史（history=2）仅作库内部登记使用：应用所需的会话日志/统计一律经
        // SessionBridge 的全局回调采集，不受会话被挤出历史影响，也不会截断。
        try {
            SessionBridge.init()
            FFmpegKitConfig.setSessionHistorySize(2)
        } catch (_: Exception) {
        }
        val ch = NotificationChannel(
            TrimService.CHANNEL_ID, "批量剪辑进度", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "无损批量剪辑处理进度"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    /**
     * 系统内存压力回调：分梯度释放各层缓存，避免应用独占 RAM 触发 LMK
     * 杀掉后台进程（音乐 / 微信 / 浏览器等）。
     *
     * 分级（按 Android ComponentCallbacks2 文档）：
     *  - TRIM_MEMORY_RUNNING_LOW / CRITICAL：用户正用本应用且内存吃紧 → 立即全清
     *    可重建的进程内 L1/L2 缓存（缩略图 memCache / 关键帧 / probe / done 日志），
     *    仅保留正在跑的 ffmpeg 会话缓冲（裁剪途中不可丢）；磁盘缓存不动（下次进入
     *    页面从盘上重读到内存的开销远小于杀进程的代价）
     *  - TRIM_MEMORY_BACKGROUND / UI_HIDDEN：应用退到后台 → 清中等量
     *    （doneLogs 全清、probeCache / keyframeCache 清；ThumbStore memCache
     *    保留一半，下次回前台秒出第一屏）
     *  - TRIM_MEMORY_MODERATE / RUNNING_MODERATE：温和档清最易重建的部分
     *    （doneLogs 老条目、keyframeCache 邻域条目；ThumbStore memCache 不动，
     *    列表缩略图秒出比省这几 MB 更重要）
     *
     * 注意：onTrimMemory 不会在进程启动时调用；初值 = 各 LRU 上限。
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                // 严重压力：全清可重建的进程内缓存
                ThumbStore.onTrimMemory(level)
                Scanner.onTrimMemory(level)
                Probe.onTrimMemory(level)
                SessionBridge.onTrimMemory(level)
            }
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> {
                // 应用进后台：清中量
                ThumbStore.onTrimMemory(level)
                Scanner.onTrimMemory(level)
                Probe.onTrimMemory(level)
                SessionBridge.onTrimMemory(level)
            }
            level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE -> {
                // 温和压力：清最易重建的部分
                Probe.onTrimMemory(level)
                SessionBridge.onTrimMemory(level)
            }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        // 老 API（无 level 区分），按最严档处理
        ThumbStore.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        Scanner.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        Probe.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        SessionBridge.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
    }
}
