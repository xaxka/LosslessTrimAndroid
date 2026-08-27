package com.xixka.losslesstrim.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xixka.losslesstrim.data.AppSettings
import com.xixka.losslesstrim.data.FileResult
import com.xixka.losslesstrim.data.Outcome
import com.xixka.losslesstrim.data.PerFileOverride
import com.xixka.losslesstrim.data.ScanProgress
import com.xixka.losslesstrim.data.Scanner
import com.xixka.losslesstrim.data.SettingsRepository
import com.xixka.losslesstrim.data.VideoEntry
import com.xixka.losslesstrim.data.matchDroppedBySignature
import com.xixka.losslesstrim.trim.DocUtils
import com.xixka.losslesstrim.trim.QueueUi
import com.xixka.losslesstrim.trim.TrimController
import com.xixka.losslesstrim.trim.TrimJob
import com.xixka.losslesstrim.trim.TrimPlanner
import com.xixka.losslesstrim.data.TrimPlan
import com.xixka.losslesstrim.util.Formats
import com.xixka.losslesstrim.util.ThumbStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 列表条目 + 当前参数下的处理计划 */
data class EntryStatus(
    val entry: VideoEntry,
    val plan: TrimPlan,
    val override: PerFileOverride?,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SettingsRepository(app)

    val settings: StateFlow<AppSettings> =
        repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _files = MutableStateFlow<List<VideoEntry>>(emptyList())
    val files = _files.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning = _scanning.asStateFlow()

    /** 扫描进度（解析中才有值）：已完成数/总数 + 当前文件名，让长扫描可见 */
    private val _scanProgress = MutableStateFlow<ScanProgress?>(null)
    val scanProgress = _scanProgress.asStateFlow()

    private val _scanMsg = MutableStateFlow<String?>(null)
    val scanMsg = _scanMsg.asStateFlow()

    /** 扫描发现的残留中间文件（.trimbackup / .part / .oldtrim），用于恢复提示 */
    private val _orphans = MutableStateFlow<List<String>>(emptyList())
    val orphans = _orphans.asStateFlow()

    private val _treeUri = MutableStateFlow<Uri?>(null)
    val treeUri = _treeUri.asStateFlow()

    private val _overrides = MutableStateFlow<Map<Uri, PerFileOverride>>(emptyMap())
    val overrides = _overrides.asStateFlow()

    private var scanJob: Job? = null

    // ---------------- 缓存管理（设置页"清除缓存"用） ----------------

    /** 缓存概览：磁盘字节数 + 缩略图文件数 + Room 缓存行数 + 上次清除时间 */
    data class CacheInfo(
        val diskBytes: Long = 0L,
        val thumbFiles: Int = 0,
        val roomRows: Int = 0,
        val lastClearedAt: Long? = null,
    )

    private val _cacheInfo = MutableStateFlow(CacheInfo())
    val cacheInfo = _cacheInfo.asStateFlow()

    private val _clearingCache = MutableStateFlow(false)
    val clearingCache = _clearingCache.asStateFlow()

    init {
        // 缩略图解码开关同步到 ThumbStore（thumb() 调用点分散在 Composable，全局 volatile 开关最省侵入）：
        // mcDecodeThumbs = 实验性 MediaCodec 直解路线（FFmpeg 硬解已移除，软解为默认主路径）
        viewModelScope.launch {
            settings.collect {
                ThumbStore.mcThumbEnabled = it.mcDecodeThumbs
            }
        }
        // 恢复持久化的每文件覆盖设置（片头/片尾/区间/丢弃轨道）：只读一次，
        // 后续以内存态为准并主动落盘，避免落盘回显与本地修改互相覆盖
        viewModelScope.launch {
            val persisted = repo.overrides.first()
            _overrides.update { cur -> if (cur.isEmpty()) persisted else cur }
        }
        // 任务完成：清掉已成功文件的覆盖设置（成功即消费）。覆盖模式下 uri 不变
        // 但文件已换成剪辑产物，残留的旧切点/丢弃轨道会作用在新时间轴上
        // （点开视频仍显示旧时间与"将丢弃 N 条轨道"）；失败/取消项保留供重试
        viewModelScope.launch {
            TrimController.queueUi
                .filterIsInstance<QueueUi.Finished>()
                .collect { fin ->
                    val doneUris = fin.results
                        .filter { it.outcome == Outcome.SUCCESS }
                        .map { it.entry.docUri }
                        .toSet()
                    if (doneUris.isNotEmpty() && _overrides.value.keys.any { it in doneUris }) {
                        _overrides.update { m -> m - doneUris }
                        persistOverrides()
                    }
                }
        }
        // 恢复上次目录（持久化权限仍有效时自动重扫）
        viewModelScope.launch {
            val treeStr = repo.lastTreeUri.first()
            if (treeStr.isNotEmpty()) {
                val uri = Uri.parse(treeStr)
                val persisted = getApplication<Application>().contentResolver
                    .persistedUriPermissions.any {
                        it.uri == uri && it.isReadPermission && it.isWritePermission
                    }
                if (persisted) {
                    _treeUri.value = uri
                    rescan()
                }
            }
        }
    }

    val statuses: StateFlow<List<EntryStatus>> =
        combine(files, settings, overrides) { f, s, o ->
            f.map { EntryStatus(it, TrimPlanner.logicalPlan(it, s, o[it.docUri]), o[it.docUri]) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 是否单文件模式（无目录权限，只能另存为） */
    val isSingleFile: Boolean
        get() = _files.value.size == 1 && _files.value.first().isSingleFile

    val processableCount: StateFlow<Int> =
        statuses.map { st -> st.count { it.plan.ok } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** 剩余空间预警（尽力而为，仅主存储目录可估算）；按每文件实际覆盖参数判断 */
    val spaceWarning: StateFlow<String?> =
        combine(files, settings, treeUri, overrides) { f, s, tree, ov ->
            val treeUri = tree ?: return@combine null
            val okFiles = f.filter { TrimPlanner.logicalPlan(it, s, ov[it.docUri]).ok }
            if (okFiles.isEmpty()) return@combine null
            // 覆盖模式逐个替换只需装下最大文件；CutVideos 模式要装下全部输出
            val basis = if (s.overwrite) {
                okFiles.maxOfOrNull { it.sizeBytes } ?: return@combine null
            } else {
                val sum = okFiles.sumOf { it.sizeBytes }
                if (sum <= 0) return@combine null else sum
            }
            val free = DocUtils.freeBytesOfTree(treeUri) ?: return@combine null
            val need = (basis * 1.1).toLong()
            if (free < need) {
                "剩余空间可能不足：约需 ${Formats.size(need)}，当前可用 ${Formats.size(free)}"
            } else null
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { repo.update(transform) }
    }

    fun onFolderPicked(uri: Uri) {
        val app = getApplication<Application>()
        if (_treeUri.value != null && _treeUri.value != uri) releaseOldTreePermission()
        try {
            app.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: SecurityException) {
        } catch (_: IllegalArgumentException) {
        }
        _treeUri.value = uri
        viewModelScope.launch { repo.setLastTreeUri(uri.toString()) }
        rescan()
    }

    /** 释放不再使用的旧目录持久化授权（系统槽位有限，避免逐次泄漏） */
    private fun releaseOldTreePermission() {
        val old = _treeUri.value ?: return
        try {
            getApplication<Application>().contentResolver.releasePersistableUriPermission(
                old,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {
        }
    }

    /** 单文件编辑：直接探测该文件并进入列表（覆盖模式不可用，处理时另存为） */
    fun onSingleFilePicked(uri: Uri) {
        releaseOldTreePermission()
        _treeUri.value = null
        _scanning.value = true
        _scanMsg.value = null
        _orphans.value = emptyList()
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            try {
                val probe = com.xixka.losslesstrim.ffmpeg.Probe.probeMedia(getApplication(), uri)
                val name = DocUtils.queryDisplayName(getApplication(), uri)
                    ?: uri.lastPathSegment?.substringAfterLast('/') ?: "video"
                val entry = VideoEntry(
                    treeUri = uri,
                    folderUri = null,
                    docUri = uri,
                    name = name,
                    sizeBytes = DocUtils.length(getApplication(), uri).coerceAtLeast(0),
                    probe = probe,
                    filePath = com.xixka.losslesstrim.util.StorageAccess
                        .accessibleFile(getApplication(), uri)?.absolutePath,
                )
                _files.value = listOf(entry)
                _scanMsg.value = if (probe.probeOk) {
                    "单文件：$name"
                } else {
                    "该文件不可处理（${probe.error ?: "解析失败"}）"
                }
            } catch (e: Exception) {
                _files.value = emptyList()
                _scanMsg.value = "读取失败：${e.message}"
            } finally {
                _scanning.value = false
            }
        }
    }

    fun rescan() {
        val tree = _treeUri.value ?: return
        scanJob?.cancel()
        _scanning.value = true
        _scanMsg.value = null
        _scanProgress.value = null
        scanJob = viewModelScope.launch {
            try {
                val result = Scanner.scanFolder(getApplication(), tree) { p ->
                    _scanProgress.value = p
                }
                val list = result.entries
                _files.value = list
                _orphans.value = result.orphans
                _scanMsg.value = when {
                    // 致命错误（未授权/目录不可达）：直接展示，不进入常规统计文案
                    result.error != null -> result.error
                    list.isEmpty() -> "该文件夹里没有找到视频文件"
                    else -> {
                        val bad = list.count { !it.probe.probeOk }
                        val base = if (bad > 0) "共 ${list.size} 个视频，其中 $bad 个不可处理"
                        else "共 ${list.size} 个视频"
                        // 处理队列还在跑时目录正被改写（覆盖/改名/临时文件），本次
                        // 扫描结果可能不完整，提示结束后重扫而不是让用户误判
                        if (TrimController.running) "$base（处理进行中，结束后请重扫）" else base
                    }
                }
            } catch (e: Exception) {
                _files.value = emptyList()
                _orphans.value = emptyList()
                _scanMsg.value = "扫描失败：${e.message}"
            } finally {
                _scanning.value = false
                _scanProgress.value = null
            }
        }
    }

    fun setOverride(uri: Uri, o: PerFileOverride?) {
        _overrides.update { m ->
            if (o == null || o.isEmpty) m - uri else m + (uri to o)
        }
        persistOverrides()
    }

    /** 覆盖设置写入 DataStore（异步，失败不影响内存态） */
    private fun persistOverrides() {
        viewModelScope.launch {
            runCatching { repo.saveOverrides(_overrides.value) }
        }
    }

    /** 把变换写入所有视频的单独设置（合并保留已有字段，需文件信息换算时用 entry）；结果为空则移除该文件的覆盖 */
    private fun applyOverrideToAll(transform: (VideoEntry, PerFileOverride) -> PerFileOverride) {
        _overrides.update { m ->
            val result = m.toMutableMap()
            _files.value.forEach { f ->
                val o = transform(f, m[f.docUri] ?: PerFileOverride())
                if (o.isEmpty) result.remove(f.docUri) else result[f.docUri] = o
            }
            result
        }
        persistOverrides()
    }

    /**
     * 头尾裁剪模式：统一全部视频的片头/片尾/丢弃轨道（0 = 不裁剪/不丢）。
     *
     * 丢弃轨道跨文件按**签名**匹配（类型/编码/语言/标题/声道）：各文件轨道
     * 排列不同，同一索引指向不同内容，直接下发会错丢轨；其他文件丢"同款轨"，
     * 没有同款轨的保持不动。默认轨仍是单文件语义不随行（一律重置为跟随源
     * 默认）；当前编辑片的完整状态由调用方随后 setOverride 写回。
     */
    fun applyHeadTailToAll(
        head: Double,
        tail: Double,
        source: VideoEntry,
        dropped: Set<Int> = emptySet(),
    ) = applyOverrideToAll { f, it ->
        val dur = f.probe.durationSec
        it.copy(
            headSec = head.takeIf { v -> v > 0.0 },
            tailSec = tail.takeIf { v -> v > 0.0 },
            intervalStartSec = head.takeIf { v -> v > 0.0 },
            intervalEndSec = (dur - tail).takeIf { tail > 0.0 && it > 0.0 },
            droppedStreams = droppedForFile(source, dropped, f),
            defaultAudioIndex = null,
            defaultSubIndex = null,
        )
    }

    /** 区间模式：统一全部视频的开始/结束/丢弃轨道（-1 = 保留全片）；丢弃轨签名匹配、默认轨不随行，同上 */
    fun applyIntervalToAll(
        start: Double,
        end: Double,
        source: VideoEntry,
        dropped: Set<Int> = emptySet(),
    ) = applyOverrideToAll { f, it ->
        val dur = f.probe.durationSec
        val endSec = if (end < 0) dur else end
        it.copy(
            intervalStartSec = start.takeIf { v -> v >= 0.0 },
            intervalEndSec = end.takeIf { v -> v >= 0.0 },
            headSec = start.takeIf { v -> v > 0.0 },
            tailSec = (dur - endSec).takeIf { endSec < dur && it > 0.0 },
            droppedStreams = droppedForFile(source, dropped, f),
            defaultAudioIndex = null,
            defaultSubIndex = null,
        )
    }

    /**
     * 每文件应丢的轨集：本片精确按索引；其余文件按轨道签名匹配同款轨
     * （索引是文件内位置，跨文件不可比；见 matchDroppedBySignature）。
     */
    private fun droppedForFile(source: VideoEntry, dropped: Set<Int>, target: VideoEntry): Set<Int> =
        if (target.docUri == source.docUri) dropped
        else matchDroppedBySignature(source.probe.streams, dropped, target.probe.streams)

    fun confirmOverwrite() {
        updateSettings { it.copy(overwriteConfirmed = true) }
    }

    /** 返回 false = 没有可处理文件、已有队列在运行或服务启动失败 */
    fun startBatch(outputUri: Uri? = null): Boolean {
        val s = settings.value
        val st = statuses.value.filter { it.plan.ok }
        if (st.isEmpty()) return false
        val single = outputUri
        val jobs = st.map {
            TrimJob(
                it.entry, s,
                it.override?.takeIf { o -> !o.isEmpty },
                outputUri = if (single != null && it.entry.isSingleFile) single else null,
            )
        }
        return TrimController.start(getApplication(), jobs)
    }

    /** 重试失败/取消项；返回 false = 没有可重试的任务或启动失败（调用方不要跳转处理页） */
    fun retryFailed(): Boolean {
        val results = TrimController.lastResults.value
        val retryable = results
            .filter { it.outcome == Outcome.FAILED || it.outcome == Outcome.CANCELLED }
            .map { it.entry.docUri }
            .toSet()
        if (retryable.isEmpty()) return false
        val s = settings.value
        // 单文件模式无目录权限，重试需重新走另存为（本页无法提供目标），排除之
        val st = statuses.value.filter {
            it.plan.ok && it.entry.docUri in retryable && !it.entry.isSingleFile
        }
        if (st.isEmpty()) return false
        val jobs = st.map { TrimJob(it.entry, s, it.override?.takeIf { o -> !o.isEmpty }) }
        return TrimController.start(getApplication(), jobs)
    }

    fun clearResults() {
        TrimController.lastResults.value = emptyList()
        TrimController.queueUi.value = com.xixka.losslesstrim.trim.QueueUi.Idle
    }

    fun lastResults(): List<FileResult> = TrimController.lastResults.value

    /**
     * VM 销毁（Activity 配置变更 / 进程未杀但 UI 退走）：主动清掉 ThumbStore 内存层，
     * 避免配置变更瞬间一次性产生多份旧 memCache 残留（Compose 重组 + viewModel()
     * 默认作用域绑定到 Activity，但缩略图 LruCache 是单例，无人替它响应 VM 释放）。
     * 磁盘缓存保留——下次进入页面从盘上重读秒出，比重新抽帧便宜。
     *
     * 注：系统层 onTrimMemory 也覆盖此场景，但只在压力下触发；这里是用户主动
     * 离开页面的"温和释放点"，比 LMK 更早一步。
     */
    override fun onCleared() {
        super.onCleared()
        try {
            ThumbStore.onTrimMemory(
                android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
            )
        } catch (_: Exception) {
            // 单例 release 异常不可影响 VM 销毁主路径
        }
    }

    // ---------------- 缓存管理 ----------------

    /**
     * 刷新缓存概览（设置页进入时调用）：扫 thumbs/ + ffmpeg-thumb/ 计字节数，
     * 查 ProbeStore Room 行数。先返回上次 lastClearedAt（如有）防止 UI 闪空。
     */
    fun refreshCacheInfo() {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val lastCleared = _cacheInfo.value.lastClearedAt
            // 磁盘缓存：thumbs/ + ffmpeg-thumb/ 两目录下全部文件 size 累加
            var bytes = 0L
            var files = 0
            listOf("thumbs", "ffmpeg-thumb").forEach { name ->
                val dir = java.io.File(app.cacheDir, name)
                if (dir.isDirectory) {
                    dir.listFiles()?.forEach { f ->
                        if (f.isFile) {
                            bytes += f.length()
                            files++
                        }
                    }
                }
            }
            // Room 三表行数和（可能返回 0：表为空或读失败，UI 仍可显示）
            val rows = com.xixka.losslesstrim.data.ProbeStore.totalRows(app)
            _cacheInfo.value = CacheInfo(
                diskBytes = bytes,
                thumbFiles = files,
                roomRows = rows,
                lastClearedAt = lastCleared,
            )
        }
    }

    /**
     * 清除缓存（设置页按钮）：组合清 ThumbStore 内存+磁盘+失败哨兵、
     * ProbeStore L2 Room 三表、Probe 进程内 L1 keyframe cache、Scanner
     * 进程内 L1 probe cache——修复前的花屏 JPEG 会被删掉，下次进入页面
     * 重新抽帧/探测。重入保护：clearing 期间忽略重复点击。
     */
    fun clearCache() {
        if (_clearingCache.value) return
        viewModelScope.launch {
            _clearingCache.value = true
            try {
                val app = getApplication<Application>()
                // 1. ThumbStore: 内存 LruCache + 磁盘 thumbs/ffmpeg-thumb + failed 哨兵
                com.xixka.losslesstrim.util.ThumbStore.clearAll(app)
                // 2. ProbeStore: L2 Room（probe/keyframe/near 三表）
                com.xixka.losslesstrim.data.ProbeStore.clearAll(app)
                // 3. Probe.clearKeyframeCache: 进程内 L1 keyframe cache（全量+邻域）
                com.xixka.losslesstrim.ffmpeg.Probe.clearKeyframeCache()
                // 4. Scanner.clearProbeCache: 进程内 L1 probe cache
                Scanner.clearProbeCache()
                val now = System.currentTimeMillis()
                _cacheInfo.value = CacheInfo(
                    diskBytes = 0L,
                    thumbFiles = 0,
                    roomRows = 0,
                    lastClearedAt = now,
                )
            } finally {
                _clearingCache.value = false
            }
        }
    }

    /** 最近导出诊断的文件路径（用于在 UI 上显示让用户去找） */
    private val _diagPath = MutableStateFlow<String?>(null)
    val diagPath = _diagPath.asStateFlow()

    private val _exportingDiag = MutableStateFlow(false)
    val exportingDiag = _exportingDiag.asStateFlow()

    /**
     * 导出诊断日志到 Movies/LosslessTrim/thumbstore_diag_<ts>.txt。包含设备信息、
     * ffmpeg-kit 版本、最近 N 次抽帧失败的命令/returnCode/stderr/源文件路径。
     * 用户分享后可用于诊断花屏/卡加载中根因。导出文件路径会在 UI 显示。
     */
    fun exportDiagnostics() {
        if (_exportingDiag.value) return
        viewModelScope.launch {
            _exportingDiag.value = true
            try {
                val app = getApplication<Application>()
                val file = withContext(Dispatchers.IO) {
                    com.xixka.losslesstrim.util.ThumbStore.exportDiagnostics(app)
                }
                _diagPath.value = file?.absolutePath
                    ?: "导出失败（写入失败，可能未授予\"所有文件\"权限）"
            } finally {
                _exportingDiag.value = false
            }
        }
    }
}
