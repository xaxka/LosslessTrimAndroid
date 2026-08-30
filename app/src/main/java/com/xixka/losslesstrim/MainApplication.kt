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
     * 注意：Android 的 level 数值不代表严重度梯度（RUNNING_MODERATE=5、
     * RUNNING_LOW=10、RUNNING_CRITICAL=15、UI_HIDDEN=20、BACKGROUND=40、
     * MODERATE=60、COMPLETE=80），不能用一条 >= 阈值分级——这里只负责把
     * 任一压力事件路由给全部缓存模块，力度由各模块自行判定：
     *  - ThumbStore：后台家族清 memCache、前台吃紧连失败哨兵一起清、
     *    温和档只清过期哨兵（见其 onTrimMemory）
     *  - Scanner / Probe / SessionBridge：缓存皆可重建（Room L2 / 磁盘仍在），
     *    任何压力档都清——批处理内存吃紧时不释放它们正是旧实现 OOM 的诱因之一
     *
     * 磁盘缓存不动（下次进入页面从盘上重读到内存的开销远小于杀进程的代价）。
     * onTrimMemory 不会在进程启动时调用；初值 = 各 LRU 上限。
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level < ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE) return
        ThumbStore.onTrimMemory(level)
        Scanner.onTrimMemory(level)
        Probe.onTrimMemory(level)
        SessionBridge.onTrimMemory(level)
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
