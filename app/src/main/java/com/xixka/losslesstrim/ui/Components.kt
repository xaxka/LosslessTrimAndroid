package com.xixka.losslesstrim.ui

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xixka.losslesstrim.util.Formats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 下拉选择行 */
@Composable
fun ChoiceField(
    label: String,
    options: List<String>,
    selected: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { expanded = true }
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    options.getOrElse(selected) { "?" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(" ▾", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEachIndexed { i, opt ->
                    DropdownMenuItem(
                        text = { Text(opt) },
                        onClick = {
                            expanded = false
                            onSelect(i)
                        },
                    )
                }
            }
        }
    }
}

/** 状态小标签 */
@Composable
fun StatusTag(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = color,
        fontSize = 11.sp,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** 视频首帧缩略图（失败显示占位） */
@Composable
fun VideoThumb(uri: android.net.Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bmp by produceState<Bitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) { extractThumb(context, uri) }
    }
    Box(
        modifier = modifier
            .size(width = 96.dp, height = 54.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFFE2E8F0)),
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(width = 96.dp, height = 54.dp),
            )
        } else {
            Text("…", color = Color(0xFF64748B), fontSize = 12.sp)
        }
    }
}

private fun extractThumb(context: Context, uri: android.net.Uri): Bitmap? {
    val mmr = MediaMetadataRetriever()
    return try {
        mmr.setDataSource(context, uri)
        val frame = mmr.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        frame?.let { b ->
            val w = 192
            val h = (b.height.toLong() * w / b.width.coerceAtLeast(1)).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(b, w, h, true)
        }
    } catch (e: Exception) {
        null
    } finally {
        try {
            mmr.release()
        } catch (_: Exception) {
        }
    }
}

/** 切点处抽帧预览 */
@Composable
fun FramePreview(uri: android.net.Uri, tSec: Double, label: String, timeLabel: String) {
    val context = LocalContext.current
    val bmp by produceState<Bitmap?>(null, uri, tSec) {
        value = withContext(Dispatchers.IO) {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(context, uri)
                mmr.getFrameAtTime((tSec * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } catch (e: Exception) {
                null
            } finally {
                try {
                    mmr.release()
                } catch (_: Exception) {
                }
            }
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(width = 128.dp, height = 72.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFFE2E8F0)),
            contentAlignment = Alignment.Center,
        ) {
            val b = bmp
            if (b != null) {
                Image(
                    bitmap = b.asImageBitmap(),
                    contentDescription = label,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(width = 128.dp, height = 72.dp),
                )
            } else {
                Text("加载中…", color = Color(0xFF64748B), fontSize = 11.sp)
            }
        }
        Text(label, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        Text(timeLabel, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
    }
}

/** 区块卡片 */
@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}
