package com.xixka.losslesstrim.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xixka.losslesstrim.data.FileResult
import com.xixka.losslesstrim.data.Outcome
import com.xixka.losslesstrim.trim.TrimController
import com.xixka.losslesstrim.util.Formats

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
            .padding(horizontal = 12.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "处理完成",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    buildString {
                        append("✓ 成功 $success")
                        append("　✗ 失败 $failed")
                        append("　⊘ 跳过 $skipped")
                        if (cancelled > 0) append("　已取消 $cancelled")
                    },
                    fontSize = 13.sp,
                )
                if (success > 0) {
                    Text(
                        "体积：${Formats.mb(successOrig)} → ${Formats.mb(successNew)}，节省 ${Formats.mb(saved)}",
                        fontSize = 13.sp,
                        color = Color(0xFF059669),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (failed + cancelled > 0) {
                        Button(onClick = onRetry) { Text("重试失败项") }
                    }
                    TextButton(onClick = onBackHome) { Text("返回主页") }
                }
            }
        }

        HorizontalDivider()

        LazyColumn(
            Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
        ) {
            items(results, key = { it.entry.docUri.toString() + it.outcome.name }) { r ->
                ResultRow(r)
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ResultRow(r: FileResult) {
    val (color, tag) = when (r.outcome) {
        Outcome.SUCCESS -> Color(0xFF059669) to "✓ 成功"
        Outcome.FAILED -> MaterialTheme.colorScheme.error to "✗ 失败"
        Outcome.SKIPPED -> Color(0xFFD97706) to "⊘ 跳过"
        Outcome.CANCELLED -> Color(0xFF64748B) to "已取消"
        Outcome.PENDING -> Color(0xFF64748B) to "未处理"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                r.entry.name,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append(tag)
                    append(" · ").append(Formats.size(r.origSize))
                    if (r.outcome == Outcome.SUCCESS && r.newSize > 0) {
                        append(" → ").append(Formats.size(r.newSize))
                    }
                    if (r.outcome == Outcome.SUCCESS) {
                        append(" · 保留 ").append(Formats.clock(r.plan.duration))
                    }
                },
                fontSize = 11.sp,
                color = color,
            )
            if (r.reason != null && r.outcome != Outcome.SUCCESS) {
                Text(
                    r.reason,
                    fontSize = 11.sp,
                    color = Color(0xFF64748B),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
