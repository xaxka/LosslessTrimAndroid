package com.xixka.losslesstrim.update

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
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
import java.util.concurrent.atomic.AtomicBoolean

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

        /** 已提交 PackageInstaller 会话，等待系统安装确认/结果 */
        data object Installing : State
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
                lastReady = State.ReadyToInstall(info, apk)
                state.value = lastReady
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

    /** 会话安装结果回调的 action（PendingIntent 广播，仅本应用可收） */
    private const val ACTION_INSTALL_RESULT =
        "com.xixka.losslesstrim.update.INSTALL_RESULT"

    /** 会话安装防重入 */
    private val sessionInstalling = AtomicBoolean(false)

    /** 最近一次"更新包已就绪"状态（Installing 卡死时恢复重试入口用） */
    private var lastReady: State.ReadyToInstall? = null

    /** 会话安装的运行时接收器（Installing 期间存活，终态注销） */
    private var installReceiver: BroadcastReceiver? = null

    /** 注册接收器所用的 app context（注销时用同一个） */
    private var installReceiverContext: Context? = null

    /**
     * 安装已下载的更新包，两级路径：
     *
     * 1. ACTION_VIEW 拉起系统安装器——标准 UX，但部分设备/ROM（targetSdk 34 +
     *    Android 14/15，个别国产定制系统）的安装器 Activity 不解析该隐式 intent，
     *    抛 ActivityNotFoundException（"未找到可用的安装器"即由此来）；
     * 2. 兜底 PackageInstaller 会话 API——系统为应用安装提供的正式通道（应用商店
     *    均走此路），不依赖 intent 解析，ROM 兼容性最好。提交后系统经
     *    PENDING_USER_ACTION 回调下发确认页 intent，由我们拉起，最终确认/安装
     *    结果再经回调返回。
     */
    fun install(context: Context) {
        val st = state.value as? State.ReadyToInstall ?: return

        // 路径 1：ACTION_VIEW（标准安装器 UI）
        try {
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", st.apk
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                // 防御：若调用方传入非 Activity context 时必需
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
            // 该设备安装器不响应 ACTION_VIEW：转 PackageInstaller 会话安装
        } catch (_: Exception) {
            // FileProvider 失败等：同样尝试会话路径，两条路都失败才报错
        }

        // 路径 2：PackageInstaller 会话 API
        installWithSession(context.applicationContext, st.info, st.apk)
    }

    /**
     * PackageInstaller 会话安装：建会话 → 写入 APK → commit（带回调）。
     * 用户确认页由系统回调 PENDING_USER_ACTION 下发的 intent 拉起。
     */
    private fun installWithSession(app: Context, info: ReleaseInfo, apk: File) {
        if (sessionInstalling.getAndSet(true)) return

        // 先注册结果接收器，再提交会话（顺序不能反，避免错过首个回调）
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val status = intent.getIntExtra(
                    PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE
                )
                when (status) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        // 系统下发确认页 intent，必须由应用拉起（仍是安装器 UI）
                        val confirm = parcelableIntent(intent)
                        if (confirm != null) {
                            try {
                                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                app.startActivity(confirm)
                            } catch (_: Exception) {
                                finishSessionInstall()
                                state.value = State.Error("无法启动安装确认页，请手动安装")
                            }
                        } else {
                            finishSessionInstall()
                            state.value = State.Error("安装请求被系统拒绝")
                        }
                    }

                    PackageInstaller.STATUS_SUCCESS -> {
                        // 安装成功后应用进程随即被系统替换重启，这里基本来不及展示
                        finishSessionInstall()
                        state.value = State.Idle
                    }

                    else -> {
                        finishSessionInstall()
                        if (status == PackageInstaller.STATUS_FAILURE_ABORTED && apk.isFile) {
                            // 用户在系统确认页取消：回到"已就绪"，安装按钮可直接重试
                            state.value = State.ReadyToInstall(info, apk)
                        } else {
                            state.value = State.Error("安装失败：${friendlyInstallStatus(status, intent)}")
                        }
                    }
                }
            }
        }
        unregisterInstallReceiver(app)
        installReceiver = receiver
        installReceiverContext = app
        val filter = IntentFilter(ACTION_INSTALL_RESULT)
        if (Build.VERSION.SDK_INT >= 33) {
            // Android 13+ 运行时接收器必须声明 exported 与否；PendingIntent 广播
            // 按创建者（本应用）身份投递，NOT_EXPORTED 即可接收
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            // 旧平台无此要求，普通注册即可（本应用自发的广播不存在跨应用问题）
            app.registerReceiver(receiver, filter)
        }

        try {
            val installer = app.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            )
            val sessionId = installer.createSession(params)
            val session = installer.openSession(sessionId)
            try {
                apk.inputStream().use { input ->
                    session.openWrite("package", 0, apk.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                // PendingIntent 广播按创建者（本应用）身份投递，NOT_EXPORTED 接收器可收；
                // 系统需向 intent 填充状态 extras，Android 12+ 必须显式 FLAG_MUTABLE
                val resultIntent = Intent(ACTION_INSTALL_RESULT).setPackage(app.packageName)
                val pi = PendingIntent.getBroadcast(
                    app, sessionId, resultIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or
                        if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                )
                session.commit(pi.intentSender)
                state.value = State.Installing
            } finally {
                session.close()   // commit 后 close 不会放弃会话
            }
        } catch (e: Exception) {
            finishSessionInstall()
            state.value = State.Error(
                "无法发起安装：${e.message ?: e.javaClass.simpleName}"
            )
        }
    }

    /** 注销结果接收器并复位防重入标志（终态调用） */
    private fun finishSessionInstall() {
        sessionInstalling.set(false)
        val ctx = installReceiverContext
        val rcv = installReceiver
        if (ctx != null && rcv != null) {
            try {
                ctx.unregisterReceiver(rcv)
            } catch (_: Exception) {
                // 未注册/已注销：忽略
            }
        }
        installReceiver = null
        installReceiverContext = null
    }

    /**
     * Installing 卡死（个别 ROM 回调丢失/确认页未弹出）时的手动恢复：
     * 丢弃本次会话回到"已就绪"，可重新点安装。
     */
    fun retryInstall() {
        if (state.value !is State.Installing) return
        finishSessionInstall()
        val ready = lastReady
        if (ready != null && ready.apk.isFile) {
            state.value = ready
        } else {
            lastInfo?.let { state.value = State.Available(it) }
        }
    }

    private fun unregisterInstallReceiver(app: Context) {
        installReceiver?.let {
            try {
                app.unregisterReceiver(it)
            } catch (_: Exception) {
            }
        }
        installReceiver = null
        installReceiverContext = null
    }

    /** 兼容 API 33+ 的 getParcelableExtra 类型安全版本 */
    private fun parcelableIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    /** PackageInstaller 状态码 → 可读文案（系统原始消息仅在无专属文案时兜底） */
    private fun friendlyInstallStatus(status: Int, intent: Intent): String = when (status) {
        PackageInstaller.STATUS_FAILURE_ABORTED ->
            "已取消（在系统确认页选择了取消或拒绝）"
        PackageInstaller.STATUS_FAILURE_STORAGE ->
            "存储空间不足"
        PackageInstaller.STATUS_FAILURE_CONFLICT ->
            "与已安装版本签名或版本冲突"
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
            "安装包与该设备不兼容"
        PackageInstaller.STATUS_FAILURE_BLOCKED ->
            "被系统或安全软件拦截"
        PackageInstaller.STATUS_FAILURE_INVALID ->
            "安装包无效或已损坏"
        else ->
            intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "未知错误"
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
