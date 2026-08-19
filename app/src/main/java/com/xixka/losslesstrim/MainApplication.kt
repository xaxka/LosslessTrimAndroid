package com.xixka.losslesstrim

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.antonkarpenko.ffmpegkit.FFmpegKitConfig
import com.xixka.losslesstrim.trim.TrimService

class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // ffmpeg-kit 默认保留最近 10 个会话（连同全部日志缓冲）驻内存；
        // 关键帧探测的输出可达几十 MB，积压多个会话是批处理时 OOM 的诱因之一。
        // 本应用只使用 execute/executeAsync 的直接返回值，不依赖会话历史，压到 2 即可。
        try {
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
}
