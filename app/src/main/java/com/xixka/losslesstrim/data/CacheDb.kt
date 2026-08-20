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
 * 陈旧防护：文件大小或修改时间与入库时不一致视为失效（文件被改过），
 * 返回 miss 走实时探测。缓存层任何异常都只影响提速、不影响功能：
 * 全部 IO 走 Dispatchers.IO 且 runCatching 吞掉，绝不向上抛。
 */
@Entity(tableName = "probe_cache")
data class ProbeCacheEntity(
    @PrimaryKey val uri: String,
    val sizeBytes: Long,
    val modifiedMs: Long,
    val durationSec: Double,
    val formatName: String,
    val streamsJson: String,
    val updatedAt: Long,
)

/**
 * 关键帧**全量**扫描持久缓存（Room，L2）。只存全量扫描结果——
 * probeKeyframesNear 的邻域窗口结果只覆盖切点附近，冒充全量会污染
 * 后续换切点的对齐，严禁入库。
 */
@Entity(tableName = "keyframe_cache")
data class KeyframeCacheEntity(
    @PrimaryKey val uri: String,
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

    @Query("DELETE FROM probe_cache WHERE updatedAt < :threshold")
    suspend fun pruneProbe(threshold: Long): Int

    @Query("DELETE FROM keyframe_cache WHERE updatedAt < :threshold")
    suspend fun pruneKeyframes(threshold: Long): Int
}

@Database(
    entities = [ProbeCacheEntity::class, KeyframeCacheEntity::class],
    version = 1,
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

    /** 行保留期：过期行（对应文件大概率已删/已改）每进程清理一次 */
    private const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000

    private val pruned = AtomicBoolean(false)

    /** 命中且未陈旧（大小/修改时间一致）才返回结果，否则 null */
    suspend fun loadProbe(context: Context, uri: String, file: File): ProbeResult? =
        withContext(Dispatchers.IO) {
            runCatching {
                val e = CacheDb.get(context).dao().findProbe(uri) ?: return@runCatching null
                if (e.sizeBytes != file.length() || e.modifiedMs != file.lastModified()) {
                    null
                } else {
                    ProbeResult(
                        probeOk = true,
                        durationSec = e.durationSec,
                        formatName = e.formatName,
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
                        streamsJson = streamsToJson(result.streams),
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                pruneOnce(context)
            }
        }
    }

    /** 命中且未陈旧才返回（升序全量关键帧列表），否则 null */
    suspend fun loadKeyframes(context: Context, uri: String, file: File): List<Double>? =
        withContext(Dispatchers.IO) {
            runCatching {
                val e = CacheDb.get(context).dao().findKeyframes(uri) ?: return@runCatching null
                if (e.sizeBytes != file.length() || e.modifiedMs != file.lastModified()) {
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

    private suspend fun pruneOnce(context: Context) {
        if (!pruned.compareAndSet(false, true)) return
        runCatching {
            val threshold = System.currentTimeMillis() - RETENTION_MS
            CacheDb.get(context).dao().pruneProbe(threshold)
            CacheDb.get(context).dao().pruneKeyframes(threshold)
        }
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
            s.width?.let { o.put("width", it) }
            s.height?.let { o.put("height", it) }
            o.put("attachedPic", s.attachedPic)
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
                    width = if (o.has("width")) o.getInt("width") else null,
                    height = if (o.has("height")) o.getInt("height") else null,
                    attachedPic = o.optBoolean("attachedPic", false),
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
