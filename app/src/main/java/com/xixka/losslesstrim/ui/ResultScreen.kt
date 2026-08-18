package com.xixka.losslesstrim.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
        // 居中大图标 + 结论
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
            Text(
                "体积：${Formats.mb(successOrig)} → ${Formats.mb(successNew)}，共节省 ${Formats.mb(saved)}",
                style = MaterialTheme.typography.bodyMedium,
                color = BlExt.success,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(vertical = 12.dp),
        ) {
            if (failed + cancelled > 0) {
                Button(onClick = onRetry) { Text("重试失败项") }
            }
            BlTextButton(onClick = onBackHome) { Text("返回主页") }
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
                items(results, key = { it.entry.docUri.toString() + it.outcome.name }) { r ->
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
    }
}
