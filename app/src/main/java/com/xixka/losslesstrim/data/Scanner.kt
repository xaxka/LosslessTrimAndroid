package com.xixka.losslesstrim.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.xixka.losslesstrim.ffmpeg.Probe
import com.xixka.losslesstrim.util.StorageAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
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
 * 扫描目录下的视频文件并逐个 ffprobe（仅当前目录，不递归子目录）。
 *
 * 双管线：已授予"所有文件"权限时优先 File API 直路径扫描——File.list()
 * 比 SAF 的 DocumentsContract 子文档查询快 1~2 个数量级（大目录/慢存储更明显），
 * 且条目 docUri 仍按 SAF 同构规则构造（见 StorageAccess.buildChildUri），
 * 逐文件覆盖参数等以 docUri 为键的状态不受扫描方式影响；未授权或 uri
 * 构造失败自动退回 DocumentFile/SAF 扫描。
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
        // 优先直路径扫描（快 1~2 个数量级）；不可用/构造失败时 null → 走 SAF
        StorageAccess.treeRootFile(context, treeUri)?.let { root ->
            scanWithFileApi(treeUri, root, onProgress)?.let { return@withContext it }
        }
        scanWithSaf(context, treeUri, onProgress)
    }

    /** SAF（DocumentFile）扫描管线：未授予全部文件权限 / 云盘目录时使用 */
    private suspend fun scanWithSaf(
        context: Context,
        treeUri: Uri,
        onProgress: (ScanProgress) -> Unit,
    ): ScanResult {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return ScanResult(emptyList(), emptyList())
        val files = ArrayList<Pair<DocumentFile, DocumentFile>>() // (file, folder)
        val orphans = ArrayList<String>()
        collectFiles(root, files, orphans)
        if (files.isEmpty()) return ScanResult(emptyList(), orphans)

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
                    // 已授权全部文件权限时记录绝对路径：ffmpeg/ffprobe 改走直路径读写
                    filePath = StorageAccess.accessibleFile(context, file.uri)?.absolutePath,
                )
            )
        }
        entries.sortBy { it.name.lowercase() }
        return ScanResult(entries, orphans.sorted())
    }

    /**
     * 直路径（File API）扫描管线。docUri/folderUri 按 SAF 同构规则构造，
     * 条目身份与 SAF 扫描完全一致；uri 构造失败返回 null（整体退回 SAF，
     * 而不是丢弃部分条目或身份错位）。文件识别沿用扩展名白名单（File 模式
     * 无 mime 可查，无扩展名但 mime 为视频的极边缘文件会漏，SAF 模式仍可识别）。
     */
    private suspend fun scanWithFileApi(
        treeUri: Uri,
        rootDir: File,
        onProgress: (ScanProgress) -> Unit,
    ): ScanResult? {
        val children = rootDir.listFiles() ?: return null
        val files = ArrayList<File>()
        val orphans = ArrayList<String>()
        for (child in children) {
            if (!child.isFile) continue
            val name = child.name
            // 上次闪退/失败遗留的中间文件：提示用户可手动恢复，不参与扫描
            if (name.endsWith(".part") || name.contains(".trimbackup.") || name.endsWith(".oldtrim")) {
                orphans.add(name)
                continue
            }
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext in VIDEO_EXTS) files.add(child)
        }

        val folderUri = StorageAccess.buildChildUri(treeUri, rootDir) ?: return null
        val total = files.size
        val entries = ArrayList<VideoEntry>(total)
        for (i in files.indices) {
            val file = files[i]
            onProgress(ScanProgress(parsed = i, total = total, current = file.name))
            val docUri = StorageAccess.buildChildUri(treeUri, file) ?: return null
            val probe = probeCached(docUri, file)
            entries.add(
                VideoEntry(
                    treeUri = treeUri,
                    folderUri = folderUri,
                    docUri = docUri,
                    name = file.name,
                    sizeBytes = file.length().coerceAtLeast(0),
                    probe = probe,
                    filePath = file.absolutePath,
                )
            )
        }
        entries.sortBy { it.name.lowercase() }
        return ScanResult(entries, orphans.sorted())
    }

    /** 带缓存的探测（SAF）：命中（同 uri/大小/修改时间且上次成功）直接复用，否则重新探测 */
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

    /** 带缓存的探测（直路径）：缓存键仍用 docUri，与 SAF 扫描共享同一缓存身份 */
    private suspend fun probeCached(docUri: Uri, file: File): ProbeResult {
        val key = ProbeKey(
            uri = docUri.toString(),
            size = file.length(),
            modified = file.lastModified(),
        )
        probeCache[key]?.let { return it }
        val probe = Probe.probeMediaPath(file.absolutePath)
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
