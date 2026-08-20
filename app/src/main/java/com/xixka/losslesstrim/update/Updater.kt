package com.xixka.losslesstrim.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * 应用内更新：GitHub Releases 查版本 → 下载 APK → 拉起系统安装器。
 *
 * 版本判定用 CI 发布模板写入 release body 的 versionCode（YYMMDDHHt，含构建时刻，
 * 比 versionName 的日期粒度细）：远端 > 本地即有更新。release 资产列表里可能残留
 * 历史版本的 APK（CI 每次发布只新增、不清理旧资产），因此下载地址必须按
 * "v{versionName}.apk" 精确匹配，不能拿第一个 .apk 就下。
 */
object Updater {

    private const val API_RELEASES =
        "https://api.github.com/repos/xaxka/LosslessTrimAndroid/releases"
    private const val APK_MIME = "application/vnd.android-package-archive"
    private const val USER_AGENT = "LosslessTrimAndroid-Updater"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val UPDATES_DIR = "updates"

    /** 解析自 GitHub Release 的更新包信息 */
    data class ReleaseInfo(
        val versionName: String,   // "26.08.20"
        val versionCode: Long,     // 260820120
        val apkName: String,       // LosslessTrimAndroid-v26.08.20.apk
        val apkUrl: String,
        val apkSize: Long,
    )

    /** 更新流程状态机；进程级单例持有，页面切换/旋转不中断 */
    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class Available(val info: ReleaseInfo) : State
        data object UpToDate : State
        data class Downloading(val received: Long, val total: Long) : State
        data class ReadyToInstall(val info: ReleaseInfo, val apk: File) : State
        data class Error(val message: String) : State
    }

    val state = MutableStateFlow<State>(State.Idle)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null

    @Volatile
    private var lastInfo: ReleaseInfo? = null

    /** 本地版本：(versionName, versionCode) */
    fun currentVersion(context: Context): Pair<String, Long> = try {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        (pi.versionName ?: "?") to PackageInfoCompat.getLongVersionCode(pi)
    } catch (_: Exception) {
        "?" to 0L
    }

    /** 检查更新；检查中/下载中时忽略重复点击 */
    fun check(context: Context) {
        val st = state.value
        if (st is State.Checking || st is State.Downloading) return
        val app = context.applicationContext
        scope.launch {
            state.value = State.Checking
            try {
                val info = fetchLatestRelease()
                lastInfo = info
                val local = currentVersion(app)
                state.value =
                    if (info.versionCode > local.second) State.Available(info) else State.UpToDate
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.value = State.Error("检查更新失败：${friendlyError(e)}")
            }
        }
    }

    /** 下载上次检查到的更新包（先写 .part，字节/魔数校验通过后改名为 .apk） */
    fun download(context: Context) {
        val info = lastInfo ?: return
        if (downloadJob?.isActive == true) return
        val app = context.applicationContext
        downloadJob = scope.launch {
            state.value = State.Downloading(0L, info.apkSize)
            try {
                val dir = updatesDir(app)
                // 清掉上次残留（旧版本 APK / 未完成的 .part）
                dir.listFiles()?.forEach { f -> f.delete() }
                val apk = downloadTo(info, dir)
                state.value = State.ReadyToInstall(info, apk)
            } catch (e: CancellationException) {
                // 用户取消：回到"发现新版本"，可重新下载
                state.value = State.Available(info)
                throw e
            } catch (e: Exception) {
                state.value = State.Error("下载失败：${friendlyError(e)}")
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
    }

    /** 拉起系统安装器安装已下载的更新包 */
    fun install(context: Context) {
        val st = state.value as? State.ReadyToInstall ?: return
        try {
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", st.apk
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            state.value = State.Error("未找到可用的安装器，无法安装")
        } catch (e: Exception) {
            state.value = State.Error("无法启动安装：${e.message ?: "未知错误"}")
        }
    }

    /** 是否已允许本应用安装未知来源应用（安装前必须为 true，否则系统会拒绝） */
    fun canInstall(context: Context): Boolean = try {
        context.packageManager.canRequestPackageInstalls()
    } catch (_: Exception) {
        false
    }

    /** 跳转系统"允许安装未知应用"授权页（允许后返回，需再点一次安装） */
    fun requestInstallPermission(context: Context) {
        try {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                )
            )
        } catch (_: Exception) {
        }
    }

    // ---------------- 内部实现 ----------------

    /** 优先外部私有目录（空间大、系统不轻易清理），不可用时退回 cacheDir */
    private fun updatesDir(app: Context): File {
        val dir = app.getExternalFilesDir(null)?.let { File(it, UPDATES_DIR) }
            ?: File(app.cacheDir, UPDATES_DIR)
        dir.mkdirs()
        return dir
    }

    /** GitHub API 必须带 User-Agent（否则 403）；403/429 = 未认证限流（60 次/小时/IP） */
    private fun httpGet(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        return conn
    }

    /** 遍历全部 Release，取 versionCode 最高且带匹配 APK 资产的一条 */
    private fun fetchLatestRelease(): ReleaseInfo {
        val conn = httpGet("$API_RELEASES?per_page=30")
        try {
            val code = conn.responseCode
            if (code == 403 || code == 429) {
                throw IOException("GitHub 接口限流（未登录每小时 60 次），请稍后再试")
            }
            if (code != 200) throw IOException("GitHub 接口返回 HTTP $code")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONArray(body)
            var best: ReleaseInfo? = null
            for (i in 0 until arr.length()) {
                val info = arr.optJSONObject(i)?.let { parseRelease(it) } ?: continue
                if (best == null || info.versionCode > best.versionCode) best = info
            }
            return best ?: throw IOException("发布信息中未解析到可下载的 APK 版本")
        } finally {
            conn.disconnect()
        }
    }

    /** CI 发布模板：body 含 "Version: v26.08.20 (260820120)" */
    private val versionInBody = Regex("""Version:\s*v(\d{2}\.\d{2}\.\d{2})\s*\((\d{5,10})\)""")

    private fun parseRelease(rel: JSONObject): ReleaseInfo? {
        val m = versionInBody.find(rel.optString("body")) ?: return null
        val versionName = m.groupValues[1]
        val versionCode = m.groupValues[2].toLongOrNull() ?: return null
        val assets = rel.optJSONArray("assets") ?: return null
        for (j in 0 until assets.length()) {
            val a = assets.optJSONObject(j) ?: continue
            val name = a.optString("name")
            // 按文件名精确匹配本版本 APK，防止下载到 release 里残留的旧版本资产
            if (name.endsWith(".apk", true) && name.contains("v$versionName.")) {
                return ReleaseInfo(
                    versionName = versionName,
                    versionCode = versionCode,
                    apkName = name,
                    apkUrl = a.optString("browser_download_url"),
                    apkSize = a.optLong("size", 0L),
                )
            }
        }
        return null
    }

    /** 流式下载 + 进度上报；字节校验/魔数校验通过后改名为 .apk 返回 */
    private suspend fun downloadTo(info: ReleaseInfo, dir: File): File {
        val conn = httpGet(info.apkUrl)
        val part = File(dir, "${info.apkName}.part")
        try {
            val code = conn.responseCode
            if (code != 200) throw IOException("下载服务返回 HTTP $code")
            val total = conn.contentLengthLong   // -1 = 未知
            var received = 0L
            var lastEmit = 0L
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (!currentCoroutineContext().isActive) throw CancellationException("下载已取消")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        received += n
                        // 进度节流：StateFlow 本身会合并，200ms 一报避免高频重组
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastEmit >= 200) {
                            state.value = State.Downloading(received, total)
                            lastEmit = now
                        }
                    }
                }
            }
            state.value = State.Downloading(received, if (total > 0) total else received)
            if (received <= 0) throw IOException("下载内容为空")
            if (total > 0 && received != total) {
                throw IOException("下载不完整（${received}/${total} 字节），请重试")
            }
            if (info.apkSize > 0 && received != info.apkSize) {
                throw IOException("文件大小与发布信息不符（${received}/${info.apkSize} 字节）")
            }
            // APK 本质是 zip（魔数 PK）：防止错误页/HTML 被当成安装包存盘
            part.inputStream().use {
                val magic = ByteArray(2)
                if (it.read(magic) != 2 ||
                    magic[0] != 'P'.code.toByte() || magic[1] != 'K'.code.toByte()
                ) {
                    throw IOException("下载内容不是有效的 APK 文件")
                }
            }
            val final = File(dir, info.apkName)
            if (final.exists()) final.delete()
            if (!part.renameTo(final)) throw IOException("保存更新包失败")
            return final
        } catch (e: Exception) {
            part.delete()
            throw e
        } finally {
            conn.disconnect()
        }
    }

    private fun friendlyError(e: Exception): String = when (e) {
        is UnknownHostException -> "网络不可用或无法访问 GitHub"
        is SocketTimeoutException -> "连接 GitHub 超时，请重试"
        is ConnectException -> "连接 GitHub 失败"
        is JSONException -> "解析发布信息失败"
        else -> e.message?.takeIf { it.isNotBlank() } ?: "未知错误（${e.javaClass.simpleName}）"
    }
}
