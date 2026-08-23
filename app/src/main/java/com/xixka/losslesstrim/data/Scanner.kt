package com.xixka.losslesstrim.data

import android.content.Context
import android.net.Uri
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

/** 扫描结果：视频列表 + 残留中间文件（闪退遗留的备份/临时文件，供恢复提示）+ 致命错误 */
data class ScanResult(
    val entries: List<VideoEntry>,
    val orphans: List<String>,
    /** 非空 = 扫描根本无法进行（未授权/目录不可达），UI 直接展示 */
    val error: String? = null,
)

/**
 * 扫描目录下的视频文件并逐个 ffprobe（仅当前目录，不递归子目录）。
 *
 * 全直路径管线：File.list() 列目录 + 按绝对路径探测。ffmpeg-kit 的 saf:
 * 描述符读在 fork 上有越界崩溃（"length=11; index=11"）且慢 1~2 个数量级，
 * 已彻底移除 SAF 数据通道——未授予"所有文件"权限时不再静默降级，直接报错
 * 引导授权（见 [StorageAccess.hasAllFilesAccess]）。条目 docUri 仍按 SAF
 * 同构规则构造（[StorageAccess.buildChildUri]），逐文件覆盖参数以 docUri
 * 为键的状态不受影响。
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
    /** L1 条目：结果 + 入缓时间（超 [ProbeStore.CACHE_TTL_MS] 过期，与 L2 同款时效） */
    private class CachedProbe(val result: ProbeResult, val at: Long)

    private val probeCache = Collections.synchronizedMap(
        object : LinkedHashMap<ProbeKey, CachedProbe>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ProbeKey, CachedProbe>?): Boolean =
                size > PROBE_CACHE_MAX
        }
    )

    private data class ProbeKey(val uri: String, val size: Long, val modified: Long)

    suspend fun scanFolder(
        context: Context,
        treeUri: Uri,
        onProgress: (ScanProgress) -> Unit = {},
    ): ScanResult = withContext(Dispatchers.IO) {
        // 未授予"所有文件"权限：直路径不可用，SAF 数据通道已移除，直接报错引导授权
        if (!StorageAccess.hasAllFilesAccess(context)) {
            return@withContext ScanResult(
                emptyList(), emptyList(),
                error = "未授予\u201c所有文件\u201d权限，无法读取文件（SAF 通道已移除，请到设置授权后重试）"
            )
        }
        val root = StorageAccess.treeRootFile(context, treeUri)
            ?: return@withContext ScanResult(
                emptyList(), emptyList(),
                error = "所选目录无法定位为本地存储路径（云盘/特殊位置不支持），请选择本机或 SD 卡目录"
            )
        scanWithFileApi(context, treeUri, root, onProgress)
            ?: ScanResult(
                emptyList(), emptyList(),
                error = "目录读取失败（存储离线或权限异常），请重新选择文件夹"
            )
    }

    /**
     * 直路径（File API）扫描管线。docUri/folderUri 按 SAF 同构规则构造，
     * 条目身份与历史版本完全一致；uri 构造失败返回 null（上层报错提示，
     * 而不是丢弃部分条目或身份错位）。
     */
    private suspend fun scanWithFileApi(
        context: Context,
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
            val probe = probeCached(context, docUri, file)
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

    /**
     * 带缓存的探测（直路径）：缓存键用 docUri，跨会话身份稳定。
     * 两级：L1 进程内存 LRU（同一会话重扫秒回）→ L2 Room 持久库（跨进程
     * 重启有效）→ 实时探测（成功回填两级）。两级均按 24 小时时效判定
     * （[ProbeStore.CACHE_TTL_MS]）：一天内不重复解析，过期重新探测。
     */
    private suspend fun probeCached(context: Context, docUri: Uri, file: File): ProbeResult {
        val key = ProbeKey(
            uri = docUri.toString(),
            size = file.length(),
            modified = file.lastModified(),
        )
        val now = System.currentTimeMillis()
        probeCache[key]?.takeIf { now - it.at <= ProbeStore.CACHE_TTL_MS }
            ?.let { return it.result }
        ProbeStore.loadProbe(context, key.uri, file)?.let {
            probeCache[key] = CachedProbe(it, now)
            return it
        }
        val probe = Probe.probeMediaPath(file.absolutePath)
        if (probe.probeOk) {
            probeCache[key] = CachedProbe(probe, now)
            ProbeStore.saveProbe(context, key.uri, file, probe)
        }
        return probe
    }

    /**
     * 清空进程内 L1 探测缓存（设置页"清除缓存"调用）。配合 ProbeStore.clearAll
     * （L2 Room）+ Probe.clearKeyframeCache + ThumbStore.clearAll 一起清。
     * 下次扫描重新探测入库。
     */
    fun clearProbeCache() {
        probeCache.clear()
    }

    /**
     * 缓存大小估算（设置页展示用）：L1 条目数（仅成功结果入缓，不含失败项）。
     */
    fun probeCacheSize(): Int = probeCache.size
}
