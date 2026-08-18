package com.xixka.losslesstrim.util

import java.util.Locale

object Formats {

    /** 秒 → "01:23:45" 或 "03:25" */
    fun clock(sec: Double): String {
        val s = sec.coerceAtLeast(0.0)
        val total = s.toLong()
        val h = total / 3600
        val m = (total % 3600) / 60
        val ss = total % 60
        return if (h > 0) String.format(Locale.US, "%02d:%02d:%02d", h, m, ss)
        else String.format(Locale.US, "%02d:%02d", m, ss)
    }

    /** 秒 → "01:23.5"（分:秒.小数） */
    fun clockMs(sec: Double): String {
        val s = sec.coerceAtLeast(0.0)
        val total = s.toLong()
        val m = total / 60
        val ss = total % 60
        val tenth = ((s - total) * 10).toInt().coerceIn(0, 9)
        return String.format(Locale.US, "%02d:%02d.%d", m, ss, tenth)
    }

    /** 毫秒 → "mm:ss" */
    fun ms(millis: Long): String = clock(millis / 1000.0)

    /** 字节 → 可读大小 */
    fun size(bytes: Long): String {
        if (bytes < 0) return "?"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1 -> String.format(Locale.US, "%.2f GB", gb)
            mb >= 1 -> String.format(Locale.US, "%.1f MB", mb)
            kb >= 1 -> String.format(Locale.US, "%.0f KB", kb)
            else -> "$bytes B"
        }
    }

    fun mb(bytes: Long): String = String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)

    /** "05:30" / "-1" / "330" → 秒；非法返回 null。负值保留（-1 = 不切的语义标记） */
    fun parseTime(text: String): Double? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val parts = t.split(":")
        if (parts.size > 3) return null
        var sec = 0.0
        for (p in parts) {
            val v = p.toDoubleOrNull() ?: return null
            sec = sec * 60 + v
        }
        return sec
    }

    /** 纯秒数字段（头尾时长），支持小数与负值；非法返回 null */
    fun parseSeconds(text: String): Double? {
        return text.trim().toDoubleOrNull()
    }

    fun secs3(v: Double): String = String.format(Locale.US, "%.3f", v)
}
