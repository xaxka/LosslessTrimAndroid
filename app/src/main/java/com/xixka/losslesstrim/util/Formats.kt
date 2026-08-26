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

    /** 秒 → "01:23:45.5"（≥1 小时含小时段，与 clock 口径一致；不足 1 小时为 "03:25.4"） */
    fun clockMs(sec: Double): String {
        val s = sec.coerceAtLeast(0.0)
        val total = s.toLong()
        val h = total / 3600
        val m = (total % 3600) / 60
        val ss = total % 60
        val tenth = ((s - total) * 10).toInt().coerceIn(0, 9)
        return if (h > 0) String.format(Locale.US, "%02d:%02d:%02d.%d", h, m, ss, tenth)
        else String.format(Locale.US, "%02d:%02d.%d", m, ss, tenth)
    }

    /** 毫秒 → "mm:ss" */
    fun ms(millis: Long): String = clock(millis / 1000.0)

    /** 毫秒 → "HH:MM:SS.mmm"（小时为 0 时省略） */
    fun msFull(millis: Long): String {
        val m = millis.coerceAtLeast(0)
        val h = m / 3_600_000
        val mm = (m % 3_600_000) / 60_000
        val ss = (m % 60_000) / 1000
        val msPart = (m % 1000).toString().padStart(3, '0')
        return if (h > 0) {
            String.format(Locale.US, "%02d:%02d:%02d.%s", h, mm, ss, msPart)
        } else {
            String.format(Locale.US, "%02d:%02d.%s", mm, ss, msPart)
        }
    }

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

    /** 比特率（bps） → "128 kbps" / "1.50 Mbps" / "8.20 Mbps" 等，用于轨道信息展示 */
    fun bitrate(bps: Long): String {
        if (bps <= 0) return "?"
        val kbps = bps / 1000.0
        val mbps = kbps / 1000.0
        return when {
            mbps >= 1 -> String.format(Locale.US, "%.2f Mbps", mbps)
            kbps >= 1 -> String.format(Locale.US, "%.0f kbps", kbps)
            else -> "$bps bps"
        }
    }

    /** "05:30" / "-1" / "330" → 秒；非法返回 null。"-1" 为不切哨兵（仅裸 "-1"），其余负值/空段非法。
     *  多段输入时：分/秒必须为非负整数且 < 60（"5:70"、"1.5:30" 这类按非法处理，不再静默进位）；
     *  小时段不限上限；单段纯秒数允许小数 */
    fun parseTime(text: String): Double? {
        val t = text.trim()
        if (t.isEmpty()) return null
        if (t == "-1") return -1.0
        val parts = t.split(":")
        if (parts.size > 3) return null
        var sec = 0.0
        parts.forEachIndexed { i, p ->
            val seg = p.trim()
            if (seg.isEmpty()) return null
            val v = seg.toDoubleOrNull() ?: return null
            if (v < 0) return null
            if (parts.size > 1) {
                val isLast = i == parts.size - 1
                if (isLast) {
                    // 秒段：允许小数，但必须 < 60
                    if (v >= 60) return null
                } else {
                    // 时/分段：必须为整数；分段还需 < 60（小时不限）
                    if (seg.contains('.')) return null
                    if (parts.size == 3 && i == 1 && v >= 60) return null
                }
            }
            sec = sec * 60 + v
        }
        return sec
    }

    /** 纯秒数字段（头尾时长），支持小数与负值；非法返回 null */
    fun parseSeconds(text: String): Double? {
        return text.trim().toDoubleOrNull()
    }

    fun secs3(v: Double): String = String.format(Locale.US, "%.3f", v)

    /**
     * 时间戳（毫秒）→ 相对当前时间的"3 分钟前 / 1 小时前 / 2 天前"等。
     * 1 分钟内统一显示"刚刚"。负值/0 视为未知返回"—"。
     */
    fun timeAgo(timestampMs: Long): String {
        if (timestampMs <= 0L) return "—"
        val delta = System.currentTimeMillis() - timestampMs
        if (delta < 0) return "刚刚"
        val sec = delta / 1000
        if (sec < 60) return "刚刚"
        val min = sec / 60
        if (min < 60) return "$min 分钟前"
        val hour = min / 60
        if (hour < 24) return "$hour 小时前"
        val day = hour / 24
        return "$day 天前"
    }
}
