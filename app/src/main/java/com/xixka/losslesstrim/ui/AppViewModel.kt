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
import com.xixka.losslesstrim.trim.DocUtils
import com.xixka.losslesstrim.trim.TrimController
import com.xixka.losslesstrim.trim.TrimJob
import com.xixka.losslesstrim.trim.TrimPlanner
import com.xixka.losslesstrim.data.TrimPlan
import com.xixka.losslesstrim.util.Formats
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

    init {
        // 恢复持久化的每文件覆盖设置（片头/片尾/区间/丢弃轨道）：只读一次，
        // 后续以内存态为准并主动落盘，避免落盘回显与本地修改互相覆盖
        viewModelScope.launch {
            val persisted = repo.overrides.first()
            _overrides.update { cur -> if (cur.isEmpty()) persisted else cur }
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

    /** 头尾裁剪模式：统一全部视频的片头/片尾/丢弃轨道（0 = 不裁剪/不丢），同步换算区间字段（参数互通） */
    fun applyHeadTailToAll(head: Double, tail: Double, dropped: Set<Int> = emptySet()) =
        applyOverrideToAll { f, it ->
            val dur = f.probe.durationSec
            it.copy(
                headSec = head.takeIf { v -> v > 0.0 },
                tailSec = tail.takeIf { v -> v > 0.0 },
                intervalStartSec = head.takeIf { v -> v > 0.0 },
                intervalEndSec = (dur - tail).takeIf { tail > 0.0 && it > 0.0 },
                droppedStreams = dropped,
            )
        }

    /** 区间模式：统一全部视频的开始/结束/丢弃轨道（-1 = 保留全片），同步换算头尾字段（参数互通） */
    fun applyIntervalToAll(start: Double, end: Double, dropped: Set<Int> = emptySet()) =
        applyOverrideToAll { f, it ->
            val dur = f.probe.durationSec
            val endSec = if (end < 0) dur else end
            it.copy(
                intervalStartSec = start.takeIf { v -> v >= 0.0 },
                intervalEndSec = end.takeIf { v -> v >= 0.0 },
                headSec = start.takeIf { v -> v > 0.0 },
                tailSec = (dur - endSec).takeIf { endSec < dur && it > 0.0 },
                droppedStreams = dropped,
            )
        }

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
}
