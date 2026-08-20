package com.xixka.losslesstrim.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.xixka.losslesstrim.ffmpeg.Probe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Collections

/** 扫描进度：已完成探测的文件数 / 总数 + 当前正在探测的文件名 */
data class ScanProgress(
    val parsed: Int,
    val total: Int,
    val current: String,
)

/** 扫描结果：视频列表 + 残留中间文件（闪退遗留的备份/临时文件，供恢复提示） */
data class ScanResult(
    val entries: List<VideoEntry>,
    val orphans: List<String>,
)

/**
 * 扫描 SAF 目录下的视频文件并逐个 ffprobe（仅当前目录，不递归子目录）。
 *
 * 探测顺序执行：ffprobe 会话已在 SessionBridge 执行层全局串行（并发会话的
 * 日志会互相串扰，见 SessionBridge.executeMutex），这里不再 async 并发——
 * 并发发起只会全部挤在执行锁上排队，反而让进度回调无法反映真实节奏。
 * 顺序执行 + [onProgress] 回调让 UI 能显示"第 X/N 个"，避免长时间扫描
 * 看起来像卡死（35 个大文件串行探测约 1~3 分钟）。
 */
object Scanner {

    private val VIDEO_EXTS = setOf(
        "mp4", "m4v", "m4a", "mov", "mkv", "webm", "avi", "flv",
        "ts", "m2ts", "mts", "mpeg", "mpg", "3gp", "3g2", "wmv", "ogv"
    )

    /** 探测结果缓存上限（文件数）：同一会话内重扫（如处理结束后）秒回 */
    private const val PROBE_CACHE_MAX = 128

    /**
     * 探测结果缓存：uri + 大小 + 修改时间 未变 → 视为同一文件直接复用。
     * 仅缓存成功结果；失败的（可能因串扰/超时误伤）重扫时重新探测。
     */
    private val probeCache = Collections.synchronizedMap(
        object : LinkedHashMap<ProbeKey, ProbeResult>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ProbeKey, ProbeResult>?): Boolean =
                size > PROBE_CACHE_MAX
        }
    )

    private data class ProbeKey(val uri: String, val size: Long, val modified: Long)

    suspend fun scanFolder(
        context: Context,
        treeUri: Uri,
        onProgress: (ScanProgress) -> Unit = {},
    ): ScanResult = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext ScanResult(emptyList(), emptyList())
        val files = ArrayList<Pair<DocumentFile, DocumentFile>>() // (file, folder)
        val orphans = ArrayList<String>()
        collectFiles(root, files, orphans)
        if (files.isEmpty()) return@withContext ScanResult(emptyList(), orphans)

        val total = files.size
        val entries = ArrayList<VideoEntry>(total)
        for (i in files.indices) {
            val (file, folder) = files[i]
            val name = file.name ?: file.uri.toString()
            onProgress(ScanProgress(parsed = i, total = total, current = name))
            val probe = probeCached(context, file)
            entries.add(
                VideoEntry(
                    treeUri = treeUri,
                    folderUri = folder.uri,
                    docUri = file.uri,
                    name = name,
                    sizeBytes = if (file.length() > 0) file.length() else 0L,
                    probe = probe,
                )
            )
        }
        entries.sortBy { it.name.lowercase() }
        ScanResult(entries, orphans.sorted())
    }

    /** 带缓存的探测：命中（同 uri/大小/修改时间且上次成功）直接复用，否则重新探测 */
    private suspend fun probeCached(context: Context, file: DocumentFile): ProbeResult {
        val key = ProbeKey(
            uri = file.uri.toString(),
            size = file.length(),
            modified = file.lastModified(),
        )
        probeCache[key]?.let { return it }
        val probe = Probe.probeMedia(context, file.uri)
        if (probe.probeOk) probeCache[key] = probe
        return probe
    }

    private fun collectFiles(
        folder: DocumentFile,
        out: ArrayList<Pair<DocumentFile, DocumentFile>>,
        orphans: ArrayList<String>,
    ) {
        val children = folder.listFiles()
        for (child in children) {
            if (child.isFile) {
                val name = child.name ?: continue
                // 上次闪退/失败遗留的中间文件：提示用户可手动恢复，不参与扫描
                if (name.endsWith(".part") || name.contains(".trimbackup.") || name.endsWith(".oldtrim")) {
                    orphans.add(name)
                    continue
                }
                val ext = name.substringAfterLast('.', "").lowercase()
                val mime = child.type
                val isVideo = ext in VIDEO_EXTS ||
                        (mime != null && mime.startsWith("video/", ignoreCase = true))
                if (isVideo) {
                    out.add(child to folder)
                }
            }
        }
    }
}
