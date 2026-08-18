package com.xixka.losslesstrim.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.xixka.losslesstrim.trim.QueueUi
import com.xixka.losslesstrim.trim.TrimController
import com.xixka.losslesstrim.ui.theme.BlExt
import com.xixka.losslesstrim.ui.theme.BlSurfaceVariant

/** 处理中页：标题 + N/M + 4dp 圆角进度条 + 当前文件名（等宽灰字） */
@Composable
fun ProcessingScreen() {
    val q by TrimController.queueUi.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (val state = q) {
            is QueueUi.Running -> {
                SectionCard(title = null, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Text("正在批量处理", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "${state.done} / ${state.total}",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    // 当前文件进度（4dp 圆角）
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(MaterialTheme.shapes.extraSmall),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = BlSurfaceVariant,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            "当前文件 ${(state.progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = BlExt.textSecondary,
                        )
                        Text(
                            state.speed,
                            style = MaterialTheme.typography.labelSmall,
                            color = BlExt.textSecondary,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    // 当前文件名（等宽 + 灰）
                    MonoText(
                        state.currentName,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    // 总进度
                    val overall = if (state.total > 0) (state.done + state.progress) / state.total else 0f
                    LinearProgressIndicator(
                        progress = { overall },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(MaterialTheme.shapes.extraSmall),
                        color = MaterialTheme.colorScheme.secondary,
                        trackColor = BlSurfaceVariant,
                    )
                    Text(
                        "总进度 ${(overall * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = BlExt.textSecondary,
                    )
                    Spacer(Modifier.height(12.dp))
                    BlOutlinedButton(
                        onClick = {
                            TrimController.cancel()
                            // 立即中断正在运行的 ffmpeg 会话，不必等当前文件跑完
                            try {
                                com.antonkarpenko.ffmpegkit.FFmpegKit.cancel()
                            } catch (_: Exception) {
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("取消（.part 将被删除，原文件不动）") }
                }
            }

            else -> {
                EmptyState(
                    title = "队列未在运行",
                    subtitle = "返回主页开始批量处理",
                )
            }
        }
    }
}
