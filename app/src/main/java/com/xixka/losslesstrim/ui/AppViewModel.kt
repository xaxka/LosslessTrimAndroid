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

    private val _scanMsg = MutableStateFlow<String?>(null)
    val scanMsg = _scanMsg.asStateFlow()

    private val _treeUri = MutableStateFlow<Uri?>(null)
    val treeUri = _treeUri.asStateFlow()

    private val _overrides = MutableStateFlow<Map<Uri, PerFileOverride>>(emptyMap())
    val overrides = _overrides.asStateFlow()

    private var scanJob: Job? = null

    init {
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

    /** 剩余空间预警（尽力而为，仅主存储目录可估算） */
    val spaceWarning: StateFlow<String?> =
        combine(files, settings, treeUri) { f, s, tree ->
            Triple(f, s, tree)
        }.map { (f, s, tree) ->
            val treeUri = tree ?: return@map null
            val okFiles = f.filter { TrimPlanner.logicalPlan(it, s, null).ok }
            val maxFile = okFiles.maxOfOrNull { it.sizeBytes } ?: return@map null
            val free = DocUtils.freeBytesOfTree(treeUri) ?: return@map null
            val need = (maxFile * 1.1).toLong()
            if (free < need) {
                "剩余空间可能不足：约需 ${Formats.size(need)}，当前可用 ${Formats.size(free)}"
            } else null
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { repo.update(transform) }
    }

    fun onFolderPicked(uri: Uri) {
        val app = getApplication<Application>()
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

    /** 单文件编辑：直接探测该文件并进入列表（覆盖模式不可用，处理时另存为） */
    fun onSingleFilePicked(uri: Uri) {
        _treeUri.value = null
        _scanning.value = true
        _scanMsg.value = null
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
        scanJob = viewModelScope.launch {
            try {
                val list = Scanner.scanFolder(getApplication(), tree)
                _files.value = list
                _scanMsg.value = when {
                    list.isEmpty() -> "该文件夹里没有找到视频文件"
                    else -> {
                        val bad = list.count { !it.probe.probeOk }
                        if (bad > 0) "共 ${list.size} 个视频，其中 $bad 个不可处理"
                        else "共 ${list.size} 个视频"
                    }
                }
            } catch (e: Exception) {
                _files.value = emptyList()
                _scanMsg.value = "扫描失败：${e.message}"
            } finally {
                _scanning.value = false
            }
        }
    }

    fun setOverride(uri: Uri, o: PerFileOverride?) {
        _overrides.update { m ->
            if (o == null || o.isEmpty) m - uri else m + (uri to o)
        }
    }

    /** 把变换写入所有视频的单独设置（合并保留已有字段）；结果为空则移除该文件的覆盖 */
    private fun applyOverrideToAll(transform: (PerFileOverride) -> PerFileOverride) {
        _overrides.update { m ->
            val result = m.toMutableMap()
            _files.value.forEach { f ->
                val o = transform(m[f.docUri] ?: PerFileOverride())
                if (o.isEmpty) result.remove(f.docUri) else result[f.docUri] = o
            }
            result
        }
    }

    /** 头尾裁剪模式：统一全部视频的片头/片尾（0 = 不裁剪） */
    fun applyHeadTailToAll(head: Double, tail: Double) = applyOverrideToAll {
        it.copy(headSec = head.takeIf { v -> v > 0.0 }, tailSec = tail.takeIf { v -> v > 0.0 })
    }

    /** 区间模式：统一全部视频的开始/结束（-1 = 保留全片） */
    fun applyIntervalToAll(start: Double, end: Double) = applyOverrideToAll {
        it.copy(
            intervalStartSec = start.takeIf { v -> v >= 0.0 },
            intervalEndSec = end.takeIf { v -> v >= 0.0 },
        )
    }

    fun confirmOverwrite() {
        updateSettings { it.copy(overwriteConfirmed = true) }
    }

    fun startBatch(outputUri: Uri? = null) {
        val s = settings.value
        val st = statuses.value.filter { it.plan.ok }
        if (st.isEmpty()) return
        val single = outputUri
        val jobs = st.map {
            TrimJob(
                it.entry, s,
                it.override?.takeIf { o -> !o.isEmpty },
                outputUri = if (single != null && it.entry.isSingleFile) single else null,
            )
        }
        TrimController.start(getApplication(), jobs)
    }

    fun retryFailed() {
        val results = TrimController.lastResults.value
        val retryable = results
            .filter { it.outcome == Outcome.FAILED || it.outcome == Outcome.CANCELLED }
            .map { it.entry.docUri }
            .toSet()
        if (retryable.isEmpty()) return
        val s = settings.value
        // 单文件模式无目录权限，重试需重新走另存为（本页无法提供目标），排除之
        val st = statuses.value.filter {
            it.plan.ok && it.entry.docUri in retryable && !it.entry.isSingleFile
        }
        if (st.isEmpty()) return
        val jobs = st.map { TrimJob(it.entry, s, it.override?.takeIf { o -> !o.isEmpty }) }
        TrimController.start(getApplication(), jobs)
    }

    fun clearResults() {
        TrimController.lastResults.value = emptyList()
        TrimController.queueUi.value = com.xixka.losslesstrim.trim.QueueUi.Idle
    }

    fun lastResults(): List<FileResult> = TrimController.lastResults.value
}
