package com.xixka.losslesstrim.data

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

enum class TrimMode { HEAD_TAIL, INTERVAL }
enum class AlignStrategy { CUT_MORE, CUT_LESS, AUTO }   // 多切 / 少切 / 自动
enum class OutputContainer { KEEP, MP4, MKV }

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class AppSettings(
    val mode: TrimMode = TrimMode.HEAD_TAIL,
    // 两种模式的剪辑数值均由每个视频单独设置（分析页 PerFileOverride）：
    // 头尾：headSec/tailSec；区间：intervalStartSec/intervalEndSec（-1 = 不切）
    val alignment: AlignStrategy = AlignStrategy.CUT_LESS,
    val container: OutputContainer = OutputContainer.KEEP,
    val overwrite: Boolean = true,
    /** 区间模式结束时间超片长：true=按片尾截断，false=跳过该文件 */
    val truncateOverlong: Boolean = true,
    /** 首次覆盖确认已展示过 */
    val overwriteConfirmed: Boolean = false,
    /**
     * 缩略图硬解（设置页“缩略图解码方式”三选一中的实验项）：FFmpeg 内部走
     * mediacodec 硬解，快 5-10 倍但 10-bit HEVC 颜色可能不可靠。
     * 与 [mcDecodeThumbs] 互斥（设置页已改为单一三选一，选一个自动关另一个；
     * 两个独立开关并存时用户能同时打开，语义重叠且困惑，已废弃该形态）。
     */
    val hwDecodeThumbs: Boolean = false,
    /**
     * 实验性：MediaCodec 直解缩略图（三选一中的另一实验项，绕开 FFmpeg，
     * 直接用系统解码器 + 自管颜色）：10-bit 走 P010→8bit CPU 转换，HDR 尝试
     * 请求 tone-map；失败自动回退 FFmpeg。用于验证“硬解10bit缩略图.txt”方案。
     * 与 [hwDecodeThumbs] 互斥；旧数据两个都为 true 时 ThumbStore 优先本项。
     */
    val mcDecodeThumbs: Boolean = false,
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val LAST_TREE = stringPreferencesKey("last_tree_uri")
        val MODE = intPreferencesKey("mode")
        val ALIGNMENT = intPreferencesKey("alignment")
        val CONTAINER = intPreferencesKey("container")
        val OVERWRITE = booleanPreferencesKey("overwrite")
        val TRUNCATE = booleanPreferencesKey("truncate_overlong")
        val CONFIRMED = booleanPreferencesKey("overwrite_confirmed")
        val HW_THUMBS = booleanPreferencesKey("hw_decode_thumbs")
        val MC_THUMBS = booleanPreferencesKey("mc_decode_thumbs")
        /** 每文件覆盖设置（含丢弃轨道）整体序列化为一个 JSON 串 */
        val OVERRIDES = stringPreferencesKey("per_file_overrides")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            mode = p[Keys.MODE]?.let { TrimMode.entries.getOrNull(it) } ?: TrimMode.HEAD_TAIL,
            alignment = p[Keys.ALIGNMENT]?.let { AlignStrategy.entries.getOrNull(it) } ?: AlignStrategy.CUT_LESS,
            container = p[Keys.CONTAINER]?.let { OutputContainer.entries.getOrNull(it) } ?: OutputContainer.KEEP,
            overwrite = p[Keys.OVERWRITE] ?: true,
            truncateOverlong = p[Keys.TRUNCATE] ?: true,
            overwriteConfirmed = p[Keys.CONFIRMED] ?: false,
            hwDecodeThumbs = p[Keys.HW_THUMBS] ?: false,
            mcDecodeThumbs = p[Keys.MC_THUMBS] ?: false,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    val lastTreeUri: Flow<String> = context.dataStore.data.map { it[Keys.LAST_TREE] ?: "" }

    /** 每文件覆盖设置（含丢弃轨道）：进程重启/被杀后恢复，否则“保存本片设置”名存实亡 */
    val overrides: Flow<Map<Uri, PerFileOverride>> =
        context.dataStore.data.map { p -> overridesFromJson(p[Keys.OVERRIDES] ?: "{}") }

    suspend fun setLastTreeUri(uri: String) {
        context.dataStore.edit { it[Keys.LAST_TREE] = uri }
    }

    suspend fun saveOverrides(map: Map<Uri, PerFileOverride>) {
        context.dataStore.edit { it[Keys.OVERRIDES] = overridesToJson(map) }
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { p ->
            val cur = AppSettings(
                mode = p[Keys.MODE]?.let { TrimMode.entries.getOrNull(it) } ?: TrimMode.HEAD_TAIL,
                alignment = p[Keys.ALIGNMENT]?.let { AlignStrategy.entries.getOrNull(it) } ?: AlignStrategy.CUT_LESS,
                container = p[Keys.CONTAINER]?.let { OutputContainer.entries.getOrNull(it) } ?: OutputContainer.KEEP,
                overwrite = p[Keys.OVERWRITE] ?: true,
                truncateOverlong = p[Keys.TRUNCATE] ?: true,
                overwriteConfirmed = p[Keys.CONFIRMED] ?: false,
                hwDecodeThumbs = p[Keys.HW_THUMBS] ?: false,
                mcDecodeThumbs = p[Keys.MC_THUMBS] ?: false,
            )
            val next = transform(cur)
            p[Keys.MODE] = next.mode.ordinal
            p[Keys.ALIGNMENT] = next.alignment.ordinal
            p[Keys.CONTAINER] = next.container.ordinal
            p[Keys.OVERWRITE] = next.overwrite
            p[Keys.TRUNCATE] = next.truncateOverlong
            p[Keys.CONFIRMED] = next.overwriteConfirmed
            p[Keys.HW_THUMBS] = next.hwDecodeThumbs
            p[Keys.MC_THUMBS] = next.mcDecodeThumbs
        }
    }
}

// ---- PerFileOverride ↔ JSON（org.json，零新增依赖，与 CacheDb 同风格） ----

private fun overrideToJson(o: PerFileOverride): JSONObject = JSONObject().apply {
    o.headSec?.let { put("head", it) }
    o.tailSec?.let { put("tail", it) }
    o.intervalStartSec?.let { put("start", it) }
    o.intervalEndSec?.let { put("end", it) }
    if (o.droppedStreams.isNotEmpty()) {
        put("dropped", JSONArray().apply { o.droppedStreams.sorted().forEach { put(it) } })
    }
    o.defaultAudioIndex?.let { put("defaultAudio", it) }
    o.defaultSubIndex?.let { put("defaultSub", it) }
}

private fun jsonToOverride(o: JSONObject): PerFileOverride = PerFileOverride(
    headSec = if (o.has("head")) o.getDouble("head") else null,
    tailSec = if (o.has("tail")) o.getDouble("tail") else null,
    intervalStartSec = if (o.has("start")) o.getDouble("start") else null,
    intervalEndSec = if (o.has("end")) o.getDouble("end") else null,
    droppedStreams = o.optJSONArray("dropped")?.let { arr ->
        (0 until arr.length()).mapTo(mutableSetOf()) { arr.getInt(it) }
    } ?: emptySet(),
    // 旧缓存行无此字段 → null（默认跟随第一个保留音轨 / 不设默认字幕）
    defaultAudioIndex = if (o.has("defaultAudio")) o.getInt("defaultAudio") else null,
    defaultSubIndex = if (o.has("defaultSub")) o.getInt("defaultSub") else null,
)

/** 序列化失败（uri 非法等）只丢单条，不影响其余 */
private fun overridesToJson(map: Map<Uri, PerFileOverride>): String {
    val obj = JSONObject()
    map.forEach { (u, o) ->
        runCatching { obj.put(u.toString(), overrideToJson(o)) }
    }
    return obj.toString()
}

/** 反序列化整体容错：任何异常返回空 Map（首启无数据也是 "{}"） */
internal fun overridesFromJson(json: String): Map<Uri, PerFileOverride> = runCatching {
    val obj = JSONObject(json)
    val out = LinkedHashMap<Uri, PerFileOverride>()
    for (k in obj.keys()) {
        runCatching {
            val o = jsonToOverride(obj.getJSONObject(k))
            if (!o.isEmpty) out[Uri.parse(k)] = o
        }
    }
    out
}.getOrDefault(emptyMap())
