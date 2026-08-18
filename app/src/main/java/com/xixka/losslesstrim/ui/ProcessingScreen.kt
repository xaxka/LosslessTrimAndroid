package com.xixka.losslesstrim.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xixka.losslesstrim.trim.QueueUi
import com.xixka.losslesstrim.trim.TrimController

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
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            "正在批量处理  ${state.done}/${state.total}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            state.currentName,
                            fontSize = 13.sp,
                            maxLines = 2,
                        )
                        // 当前文件进度
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                "当前文件 ${(state.progress * 100).toInt()}%",
                                fontSize = 12.sp,
                            )
                            Text(
                                state.speed,
                                fontSize = 12.sp,
                            )
                        }
                        // 总进度
                        val overall = if (state.total > 0) {
                            (state.done + state.progress) / state.total
                        } else 0f
                        LinearProgressIndicator(
                            progress = { overall },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("总进度 ${(overall * 100).toInt()}%", fontSize = 12.sp)
                        Spacer(Modifier.height(4.dp))
                        Button(
                            onClick = { TrimController.cancel() },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("取消（.part 将被删除，原文件不动）") }
                    }
                }
            }

            else -> {
                Text("队列未在运行", fontSize = 14.sp)
            }
        }
    }
}
