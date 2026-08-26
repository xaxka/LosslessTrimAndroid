package com.xixka.losslesstrim.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xixka.losslesstrim.data.FileResult
import com.xixka.losslesstrim.data.Outcome
import com.xixka.losslesstrim.trim.TrimController
import com.xixka.losslesstrim.ui.theme.BlExt
import com.xixka.losslesstrim.util.Formats

/** 结果页：居中状态大图标 + 统计卡（三列大数字着色）+ 明细列表 */
@Composable
fun ResultScreen(
    vm: AppViewModel,
    onBackHome: () -> Unit,
    onRetry: () -> Unit,
) {
    val results by TrimController.lastResults.collectAsState()

    val success = results.count { it.outcome == Outcome.SUCCESS }
    val failed = results.count { it.outcome == Outcome.FAILED }
    val skipped = results.count { it.outcome == Outcome.SKIPPED }
    val cancelled = results.count { it.outcome == Outcome.CANCELLED }

    val successOrig = results.filter { it.outcome == Outcome.SUCCESS }.sumOf { it.origSize }
    val successNew = results.filter { it.outcome == Outcome.SUCCESS }.sumOf { it.newSize }
    val saved = successOrig - successNew

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        // 顶部：左上角返回图标（叠在状态大图标同一高度内，不额外占行），
        // 取代原底部“返回主页”文字按钮——腾出的空间让给下方明细列表
        Box(Modifier.fillMaxWidth()) {
            IconButton(
                onClick = onBackHome,
                modifier = Modifier.align(Alignment.TopStart),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回主页")
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val allOk = failed == 0 && skipped == 0 && cancelled == 0
                val (icon, tint) = when {
                    failed > 0 -> Icons.Default.Info to BlExt.error
                    skipped + cancelled > 0 -> Icons.Default.Info to BlExt.warning
                    else -> Icons.Default.CheckCircle to BlExt.success
                }
                Icon(
                    imageVector = if (allOk) Icons.Default.CheckCircle else icon,
                    contentDescription = null,
                    tint = if (allOk) BlExt.success else tint,
                    modifier = Modifier.size(56.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    when {
                        allOk -> "全部完成"
                        failed > 0 -> "部分失败"
                        else -> "完成（含跳过）"
                    },
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        }

        // 三列统计卡
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("$success", "成功", BlExt.success, Modifier.weight(1f))
            StatCard("$failed", "失败", BlExt.error, Modifier.weight(1f))
            StatCard("$skipped", "跳过", BlExt.warning, Modifier.weight(1f))
        }
        if (cancelled > 0) {
            Spacer(Modifier.height(8.dp))
            Text(
                "已取消 $cancelled 项",
                style = MaterialTheme.typography.labelMedium,
                color = BlExt.textSecondary,
            )
        }
        if (success > 0) {
            Spacer(Modifier.height(8.dp))
            // 节省比例 = 节省字节 / 原体积；原体积为 0 时（理论上不应出现）兜底跳过
            val ratioStr = if (saved > 0 && successOrig > 0) {
                val pct = saved.toDouble() * 100.0 / successOrig.toDouble()
                String.format(java.util.Locale.US, "（%.1f%%）", pct)
            } else ""
            val savedStr = if (saved > 0) "，共节省 ${Formats.size(saved)}$ratioStr" else ""
            Text(
                "体积：${Formats.size(successOrig)} → ${Formats.size(successNew)}$savedStr",
                style = MaterialTheme.typography.bodyMedium,
                color = BlExt.success,
            )
        }
        // 重试（唯一保留的操作行；返回主页改由左上角图标承担）
        val hasRetryable = results.any {
            (it.outcome == Outcome.FAILED || it.outcome == Outcome.CANCELLED) &&
                    !it.entry.isSingleFile
        }
        if (hasRetryable) {
            Button(
                onClick = onRetry,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
            ) { Text("重试失败项") }
        } else if (failed + cancelled > 0) {
            Text(
                "失败项为单文件模式，请点左上角返回后重新选择另存目标",
                style = MaterialTheme.typography.labelSmall,
                color = BlExt.textSecondary,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }

        // 明细列表（白卡 + 分隔线）
        OutlinedCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = androidx.compose.foundation.BorderStroke(
                1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            ),
        ) {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                // key 含下标：即使出现同文件同状态的重复条目也不会撞 key 崩溃
                itemsIndexed(results, key = { i, r -> "$i-${r.entry.docUri}-${r.outcome.name}" }) { _, r ->
                    ResultRow(r)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
    }
}

@Composable
private fun ResultRow(r: FileResult) {
    val (color, tag) = when (r.outcome) {
        Outcome.SUCCESS -> BlExt.success to "✓ 成功"
        Outcome.FAILED -> BlExt.error to "✗ 失败"
        Outcome.SKIPPED -> BlExt.warning to "⊘ 跳过"
        Outcome.CANCELLED -> BlExt.textSecondary to "已取消"
        Outcome.PENDING -> BlExt.textSecondary to "未处理"
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MonoText(
                r.entry.name,
                modifier = Modifier.weight(1f),
                color = if (r.outcome == Outcome.FAILED) BlExt.error else BlExt.textSecondary,
            )
            StatusTag(tag, color)
        }
        Text(
            buildString {
                append(Formats.size(r.origSize))
                if (r.outcome == Outcome.SUCCESS && r.newSize > 0) {
                    append(" → ").append(Formats.size(r.newSize))
                }
                if (r.outcome == Outcome.SUCCESS) {
                    append(" · 保留 ").append(Formats.clock(r.plan.duration))
                }
            },
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (r.reason != null && r.outcome != Outcome.SUCCESS) {
            Text(
                r.reason,
                style = MaterialTheme.typography.labelSmall,
                color = BlExt.textSecondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 成功但有兼容性风险的非阻断提示（旋转元数据/Dolby Vision 等）
        if (r.warnings.isNotEmpty()) {
            Text(
                r.warnings.joinToString("\n"),
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFB58500),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
