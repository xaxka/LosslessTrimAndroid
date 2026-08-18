package com.xixka.losslesstrim

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.xixka.losslesstrim.trim.TrimService

class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val ch = NotificationChannel(
            TrimService.CHANNEL_ID, "批量剪辑进度", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "无损批量剪辑处理进度"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }
}
