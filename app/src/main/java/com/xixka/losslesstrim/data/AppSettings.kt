package com.xixka.losslesstrim.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class TrimMode { HEAD_TAIL, INTERVAL }
enum class AlignStrategy { CUT_MORE, CUT_LESS, AUTO }   // 宁多切 / 宁少切 / 自动
enum class OutputContainer { KEEP, MP4, MKV }

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class AppSettings(
    val mode: TrimMode = TrimMode.HEAD_TAIL,
    val headSec: Double = 0.0,          // 默认 0 = 不切
    val tailSec: Double = 0.0,          // 默认 0 = 不切
    val intervalStartSec: Double = -1.0, // -1 = 不切（起点归一化为 0）
    val intervalEndSec: Double = -1.0,   // -1 = 不切（终点归一化为片长）
    val alignment: AlignStrategy = AlignStrategy.CUT_MORE,
    val container: OutputContainer = OutputContainer.KEEP,
    val overwrite: Boolean = true,
    val includeSubdirs: Boolean = true,
    /** 区间模式结束时间超片长：true=按片尾截断，false=跳过该文件 */
    val truncateOverlong: Boolean = true,
    /** 首次覆盖确认已展示过 */
    val overwriteConfirmed: Boolean = false,
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val LAST_TREE = stringPreferencesKey("last_tree_uri")
        val MODE = intPreferencesKey("mode")
        val HEAD = doublePreferencesKey("head_sec")
        val TAIL = doublePreferencesKey("tail_sec")
        val INTERVAL_START = doublePreferencesKey("interval_start")
        val INTERVAL_END = doublePreferencesKey("interval_end")
        val ALIGNMENT = intPreferencesKey("alignment")
        val CONTAINER = intPreferencesKey("container")
        val OVERWRITE = booleanPreferencesKey("overwrite")
        val SUBDIRS = booleanPreferencesKey("include_subdirs")
        val TRUNCATE = booleanPreferencesKey("truncate_overlong")
        val CONFIRMED = booleanPreferencesKey("overwrite_confirmed")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            mode = p[Keys.MODE]?.let { TrimMode.entries.getOrNull(it) } ?: TrimMode.HEAD_TAIL,
            headSec = p[Keys.HEAD] ?: 0.0,
            tailSec = p[Keys.TAIL] ?: 0.0,
            intervalStartSec = p[Keys.INTERVAL_START] ?: -1.0,
            intervalEndSec = p[Keys.INTERVAL_END] ?: -1.0,
            alignment = p[Keys.ALIGNMENT]?.let { AlignStrategy.entries.getOrNull(it) } ?: AlignStrategy.CUT_MORE,
            container = p[Keys.CONTAINER]?.let { OutputContainer.entries.getOrNull(it) } ?: OutputContainer.KEEP,
            overwrite = p[Keys.OVERWRITE] ?: true,
            includeSubdirs = p[Keys.SUBDIRS] ?: true,
            truncateOverlong = p[Keys.TRUNCATE] ?: true,
            overwriteConfirmed = p[Keys.CONFIRMED] ?: false,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    val lastTreeUri: Flow<String> = context.dataStore.data.map { it[Keys.LAST_TREE] ?: "" }

    suspend fun setLastTreeUri(uri: String) {
        context.dataStore.edit { it[Keys.LAST_TREE] = uri }
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { p ->
            val cur = AppSettings(
                mode = p[Keys.MODE]?.let { TrimMode.entries.getOrNull(it) } ?: TrimMode.HEAD_TAIL,
                headSec = p[Keys.HEAD] ?: 0.0,
                tailSec = p[Keys.TAIL] ?: 0.0,
                intervalStartSec = p[Keys.INTERVAL_START] ?: -1.0,
                intervalEndSec = p[Keys.INTERVAL_END] ?: -1.0,
                alignment = p[Keys.ALIGNMENT]?.let { AlignStrategy.entries.getOrNull(it) } ?: AlignStrategy.CUT_MORE,
                container = p[Keys.CONTAINER]?.let { OutputContainer.entries.getOrNull(it) } ?: OutputContainer.KEEP,
                overwrite = p[Keys.OVERWRITE] ?: true,
                includeSubdirs = p[Keys.SUBDIRS] ?: true,
                truncateOverlong = p[Keys.TRUNCATE] ?: true,
                overwriteConfirmed = p[Keys.CONFIRMED] ?: false,
            )
            val next = transform(cur)
            p[Keys.MODE] = next.mode.ordinal
            p[Keys.HEAD] = next.headSec
            p[Keys.TAIL] = next.tailSec
            p[Keys.INTERVAL_START] = next.intervalStartSec
            p[Keys.INTERVAL_END] = next.intervalEndSec
            p[Keys.ALIGNMENT] = next.alignment.ordinal
            p[Keys.CONTAINER] = next.container.ordinal
            p[Keys.OVERWRITE] = next.overwrite
            p[Keys.SUBDIRS] = next.includeSubdirs
            p[Keys.TRUNCATE] = next.truncateOverlong
            p[Keys.CONFIRMED] = next.overwriteConfirmed
        }
    }
}
