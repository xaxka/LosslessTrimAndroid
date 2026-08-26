package com.xixka.losslesstrim.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 探测结果持久缓存（Room，L2）。
 *
 * 背景：ffprobe 探测结果此前只放进程内内存缓存（Scanner.probeCache /
 * Probe.keyframeCache），进程被杀/重启后全丢，35 个大文件要重新串行探测
 * （1~3 分钟）；且 ffmpeg-kit fork 的日志投递通道偶发截断，失败的结果
 * 无法复用，重扫一遍等于再赌一次运气。持久化后：首次成功探测即落库，
 * 之后任何会话（含重启后）按 uri + 大小 + 修改时间 命中即秒回。
 *
 * 陈旧防护（双重）：文件大小或修改时间与入库时不一致视为失效（文件被改过）；
 * 入库超过 [ProbeStore.CACHE_TTL_MS]（24 小时）视为过期，重新解析——
 * 一天内不重复解析，一天后自然刷新。
 * 缓存层任何异常都只影响提速、不影响功能：
 * 全部 IO 走 Dispatchers.IO 且 runCatching 吞掉，绝不向上抛。
 */
@Entity(tableName = "probe_cache")
data class ProbeCacheEntity(
    @PrimaryKey val uri: String,
    val sizeBytes: Long,
    val modifiedMs: Long,
    val durationSec: Double,
    val formatName: String,
    /** ffprobe format.start_time（可空：null=旧行/未知，消费侧按 0 处理） */
    val startTimeSec: Double?,
    val streamsJson: String,
    val updatedAt: Long,
)

/**
 * 关键帧**全量**扫描持久缓存（Room，L2）。只存全量扫描结果——
 * probeKeyframesNear 的邻域窗口结果只覆盖切点附近，冒充全量会污染
 * 后续换切点的对齐，严禁入**本表**（邻域结果有自己的表，见
 * [NearKfCacheEntity]，键含切点集合，不会与全量混淆）。
 */
@Entity(tableName = "keyframe_cache")
data class KeyframeCacheEntity(
    @PrimaryKey val uri: String,
    val sizeBytes: Long,
    val modifiedMs: Long,
    val keyframesJson: String,
    val updatedAt: Long,
)

/**
 * 切点**邻域**关键帧持久缓存（Room，L2）：键 = uri + 切点集合。
 * 批处理每文件一次邻域探测（1~3s），重试失败项/重跑同批任务时全部
 * 重新探测是纯浪费——同文件同切点的窗口结果直接复用。
 * 与全量表严格分离：本表行只对"这组切点"有效，换切点自然 miss 重新
 * 探测，不存在污染全量语义的问题。
 */
@Entity(tableName = "near_keyframe_cache", primaryKeys = ["uri", "pointsKey"])
data class NearKfCacheEntity(
    val uri: String,
    /** 切点集合的确定性编码（升序 "%.3f" 逗号连接），同组切点即同键 */
    val pointsKey: String,
    val sizeBytes: Long,
    val modifiedMs: Long,
    val keyframesJson: String,
    val updatedAt: Long,
)

@Dao
interface CacheDao {
    @Query("SELECT * FROM probe_cache WHERE uri = :uri")
    suspend fun findProbe(uri: String): ProbeCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putProbe(entity: ProbeCacheEntity)

    @Query("SELECT * FROM keyframe_cache WHERE uri = :uri")
    suspend fun findKeyframes(uri: String): KeyframeCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putKeyframes(entity: KeyframeCacheEntity)

    @Query("SELECT * FROM near_keyframe_cache WHERE uri = :uri AND pointsKey = :pointsKey")
    suspend fun findNearKeyframes(uri: String, pointsKey: String): NearKfCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putNearKeyframes(entity: NearKfCacheEntity)

    /** 同文件换切点后的旧行清理：只保留该 uri 最近一行（REPLACE 同键，删异键） */
    @Query("DELETE FROM near_keyframe_cache WHERE uri = :uri AND pointsKey != :keepKey")
    suspend fun pruneNearOtherPoints(uri: String, keepKey: String): Int

    @Query("DELETE FROM probe_cache WHERE updatedAt < :threshold")
    suspend fun pruneProbe(threshold: Long): Int

    @Query("DELETE FROM keyframe_cache WHERE updatedAt < :threshold")
    suspend fun pruneKeyframes(threshold: Long): Int

    @Query("DELETE FROM near_keyframe_cache WHERE updatedAt < :threshold")
    suspend fun pruneNearKeyframes(threshold: Long): Int

    /** 用户主动清除缓存：清空全部探测结果、关键帧与邻域关键帧表 */
    @Query("DELETE FROM probe_cache")
    suspend fun clearProbe(): Int

    @Query("DELETE FROM keyframe_cache")
    suspend fun clearKeyframes(): Int

    @Query("DELETE FROM near_keyframe_cache")
    suspend fun clearNearKeyframes(): Int

    /** 行数估算：用于设置页"缓存 N 行"展示（COUNT(*) 兜底失败返回 0） */
    @Query("SELECT COUNT(*) FROM probe_cache")
    suspend fun probeCount(): Int

    @Query("SELECT COUNT(*) FROM keyframe_cache")
    suspend fun keyframeCount(): Int

    @Query("SELECT COUNT(*) FROM near_keyframe_cache")
    suspend fun nearKeyframeCount(): Int
}

@Database(
    entities = [ProbeCacheEntity::class, KeyframeCacheEntity::class, NearKfCacheEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class CacheDb : RoomDatabase() {
    abstract fun dao(): CacheDao

    companion object {
        @Volatile
        private var instance: CacheDb? = null

        fun get(context: Context): CacheDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                CacheDb::class.java,
                "probe_cache.db",
            )
                // 纯缓存库，无迁移价值：结构升级直接重建（miss 一次而已）
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
        }
    }
}

/** 缓存读写门面：调用方不感知 Room，异常全部内吞（缓存层只负责提速） */
object ProbeStore {

    /**
     * 缓存有效期（24 小时）：入库起一天内命中（且文件大小/修改时间未变）
     * 直接复用，不重复解析；过期一律重新探测——文件即使没动，一天后也
     * 重新解析一次，兜底信息（如平台 API 粗粒度结果）与探测逻辑升级的
     * 修正都能自然刷入。L1 内存缓存（Scanner/Probe 的 LRU）引用同一
     * 常量做同款时效判定，进程长活也不会绕过时效。
     */
    const val CACHE_TTL_MS = 24L * 60 * 60 * 1000

    private val pruned = AtomicBoolean(false)

    /** 行是否仍在有效期内 */
    private fun fresh(updatedAt: Long): Boolean =
        System.currentTimeMillis() - updatedAt <= CACHE_TTL_MS

    /** 命中且未过期、未陈旧（大小/修改时间一致）才返回结果，否则 null */
    suspend fun loadProbe(context: Context, uri: String, file: File): ProbeResult? =
        withContext(Dispatchers.IO) {
            runCatching {
                val e = CacheDb.get(context).dao().findProbe(uri) ?: return@runCatching null
                if (!fresh(e.updatedAt) || e.sizeBytes != file.length() || e.modifiedMs != file.lastModified()) {
                    null
                } else {
                    ProbeResult(
                        probeOk = true,
                        durationSec = e.durationSec,
                        formatName = e.formatName,
                        startTimeSec = e.startTimeSec,
                        streams = streamsFromJson(e.streamsJson),
                    )
                }
            }.getOrNull()
        }

    /** 只存成功结果（probeOk 由调用方保证） */
    suspend fun saveProbe(context: Context, uri: String, file: File, result: ProbeResult) {
        withContext(Dispatchers.IO) {
            runCatching {
                CacheDb.get(context).dao().putProbe(
                    ProbeCacheEntity(
                        uri = uri,
                        sizeBytes = file.length(),
                        modifiedMs = file.lastModified(),
                        durationSec = result.durationSec,
                        formatName = result.formatName,
                        startTimeSec = result.startTimeSec,
                        streamsJson = streamsToJson(result.streams),
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                pruneOnce(context)
            }
        }
    }

    /** 命中且未过期、未陈旧才返回（升序全量关键帧列表），否则 null */
    suspend fun loadKeyframes(context: Context, uri: String, file: File): List<Double>? =
        withContext(Dispatchers.IO) {
            runCatching {
                val e = CacheDb.get(context).dao().findKeyframes(uri) ?: return@runCatching null
                if (!fresh(e.updatedAt) || e.sizeBytes != file.length() || e.modifiedMs != file.lastModified()) {
                    null
                } else {
                    kfsFromJson(e.keyframesJson).takeIf { it.isNotEmpty() }
                }
            }.getOrNull()
        }

    suspend fun saveKeyframes(context: Context, uri: String, file: File, kfs: List<Double>) {
        withContext(Dispatchers.IO) {
            runCatching {
                CacheDb.get(context).dao().putKeyframes(
                    KeyframeCacheEntity(
                        uri = uri,
                        sizeBytes = file.length(),
                        modifiedMs = file.lastModified(),
                        keyframesJson = kfsToJson(kfs),
                        updatedAt = System.currentTimeMillis(),
                    )
                )
            }
        }
    }

    /** 命中且未过期、未陈旧才返回（该切点集合的邻域关键帧），否则 null */
    suspend fun loadNearKeyframes(
        context: Context,
        uri: String,
        pointsKey: String,
        file: File,
    ): List<Double>? = withContext(Dispatchers.IO) {
        runCatching {
            val e = CacheDb.get(context).dao().findNearKeyframes(uri, pointsKey)
                ?: return@runCatching null
            if (!fresh(e.updatedAt) || e.sizeBytes != file.length() || e.modifiedMs != file.lastModified()) {
                null
            } else {
                kfsFromJson(e.keyframesJson).takeIf { it.isNotEmpty() }
            }
        }.getOrNull()
    }

    /**
     * 存邻域结果并清掉同文件其他切点的旧行：一行只占几百字节，
     * 但不清理会在用户反复调切点时无限累积；"最近一组切点"是
     * 重试场景的实际命中集，保留一行足够。
     */
    suspend fun saveNearKeyframes(
        context: Context,
        uri: String,
        pointsKey: String,
        file: File,
        kfs: List<Double>,
    ) {
        withContext(Dispatchers.IO) {
            runCatching {
                val dao = CacheDb.get(context).dao()
                dao.putNearKeyframes(
                    NearKfCacheEntity(
                        uri = uri,
                        pointsKey = pointsKey,
                        sizeBytes = file.length(),
                        modifiedMs = file.lastModified(),
                        keyframesJson = kfsToJson(kfs),
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                dao.pruneNearOtherPoints(uri, pointsKey)
            }
        }
    }

    private suspend fun pruneOnce(context: Context) {
        if (!pruned.compareAndSet(false, true)) return
        runCatching {
            // 过期行已不可能命中（TTL 判定在前），清掉防止无限累积；每进程一次
            val threshold = System.currentTimeMillis() - CACHE_TTL_MS
            CacheDb.get(context).dao().pruneProbe(threshold)
            CacheDb.get(context).dao().pruneKeyframes(threshold)
            CacheDb.get(context).dao().pruneNearKeyframes(threshold)
        }
    }

    /**
     * 用户主动清空持久缓存（设置页"清除缓存"）：清空三张表全部行，
     * 下次扫描重新探测入库（条目身份 = uri + 大小 + 修改时间，与文件
     * 状态无关，全部失效）。pruned 复位 false 让下一轮 pruneOnce 重启。
     */
    suspend fun clearAll(context: Context) {
        withContext(Dispatchers.IO) {
            runCatching {
                val dao = CacheDb.get(context).dao()
                dao.clearProbe()
                dao.clearKeyframes()
                dao.clearNearKeyframes()
            }
            pruned.set(false)
        }
    }

    /**
     * 行数统计（设置页展示用）：三表 COUNT(*) 求和。Room 失败返回 0，
     * UI 仍显示"0 行"——不影响"清除缓存"按钮可用。
     */
    suspend fun totalRows(context: Context): Int = withContext(Dispatchers.IO) {
        runCatching {
            val dao = CacheDb.get(context).dao()
            dao.probeCount() + dao.keyframeCount() + dao.nearKeyframeCount()
        }.getOrDefault(0)
    }

    // ---- StreamInfo ↔ JSON（org.json，零新增依赖） ----

    private fun streamsToJson(streams: List<StreamInfo>): String {
        val arr = JSONArray()
        for (s in streams) {
            val o = JSONObject()
            o.put("index", s.index)
            o.put("codecType", s.codecType)
            o.put("codecName", s.codecName)
            s.language?.let { o.put("language", it) }
            s.title?.let { o.put("title", it) }
            s.channels?.let { o.put("channels", it) }
            s.channelLayout?.let { o.put("channelLayout", it) }
            s.sampleRate?.let { o.put("sampleRate", it) }
            s.bitRate?.let { o.put("bitRate", it) }
            s.width?.let { o.put("width", it) }
            s.height?.let { o.put("height", it) }
            o.put("attachedPic", s.attachedPic)
            s.hasBFrames?.let { o.put("hasBFrames", it) }
            s.rotation?.let { o.put("rotation", it) }
            s.codecTag?.let { o.put("codecTag", it) }
            s.pixFmt?.let { o.put("pixFmt", it) }
            s.dispositionDefault?.let { o.put("dispositionDefault", it) }
            arr.put(o)
        }
        return arr.toString()
    }

    private fun streamsFromJson(json: String): List<StreamInfo> {
        val arr = JSONArray(json)
        val out = ArrayList<StreamInfo>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                StreamInfo(
                    index = o.getInt("index"),
                    codecType = o.optString("codecType", ""),
                    codecName = o.optString("codecName", "未知"),
                    language = o.optString("language").takeIf { it.isNotEmpty() },
                    title = o.optString("title").takeIf { it.isNotEmpty() },
                    channels = if (o.has("channels")) o.getInt("channels") else null,
                    channelLayout = o.optString("channelLayout").takeIf { it.isNotEmpty() },
                    // 旧缓存行无此字段 → null（未知）
                    sampleRate = if (o.has("sampleRate")) o.optInt("sampleRate") else null,
                    // 旧缓存行无此字段 → null；bitRate 字符串→Long 双向兼容
                    bitRate = if (o.has("bitRate")) o.optLong("bitRate").takeIf { it > 0 } else null,
                    width = if (o.has("width")) o.getInt("width") else null,
                    height = if (o.has("height")) o.getInt("height") else null,
                    attachedPic = o.optBoolean("attachedPic", false),
                    // 旧缓存行无此字段 → null（未知），消费侧按"含 B 帧"处理
                    hasBFrames = if (o.has("hasBFrames")) o.optInt("hasBFrames") else null,
                    rotation = if (o.has("rotation")) o.optInt("rotation") else null,
                    codecTag = o.optString("codecTag").takeIf { it.isNotEmpty() },
                    // 旧缓存行无此字段 → null（未知，硬解分流按保守软解处理）
                    pixFmt = o.optString("pixFmt").takeIf { it.isNotEmpty() },
                    // 旧缓存行无此字段 → null（未知，音轨兜底按"源无默认"处理）
                    dispositionDefault = if (o.has("dispositionDefault")) o.optBoolean("dispositionDefault") else null,
                )
            )
        }
        return out
    }

    private fun kfsToJson(kfs: List<Double>): String {
        val arr = JSONArray()
        for (v in kfs) arr.put(v)
        return arr.toString()
    }

    private fun kfsFromJson(json: String): List<Double> {
        val arr = JSONArray(json)
        val out = ArrayList<Double>(arr.length())
        for (i in 0 until arr.length()) {
            val v = arr.optDouble(i, -1.0)
            if (v >= 0) out.add(v)
        }
        return out
    }
}
