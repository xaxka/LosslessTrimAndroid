package com.xixka.losslesstrim.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.io.File

/**
 * "所有文件"权限（All Files Access）+ SAF uri → 直文件路径 换算。
 *
 * 背景：ffmpeg-kit 的 saf: 协议把 SAF 输出以 "w"（只写）描述符包成 AVIOContext，
 * `-movflags +faststart` 收尾阶段需要回 seek 移动数据并把 moov 重写到文件头，
 * 该"写后再回写"在部分设备/存储卷（FUSE 中转、外置 SD）上不可靠——ffmpeg 返回
 * 成功但产物缺 moov（"moov atom not found"），终检判坏删除后表现为"转码成功的
 * 文件消失了"。改为直接文件路径后 ffmpeg 走普通文件 I/O，faststart 完全可靠。
 *
 * 权限未授予时所有换算一律返回 null，调用方自动退回原 saf: 流程（功能不失效）。
 */
object StorageAccess {

    /**
     * 是否具备直接文件读写能力：
     * API 30+：用户在设置里授予"允许管理所有文件"（isExternalStorageManager）；
     * API 26-29：WRITE_EXTERNAL_STORAGE 运行时权限（配合清单里的
     * requestLegacyExternalStorage，Android 10 上同样按传统路径访问）。
     */
    fun hasAllFilesAccess(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /** 跳转本应用"所有文件"授权设置页（API 30+） */
    fun allFilesAccessIntent(context: Context): Intent = Intent(
        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        Uri.parse("package:${context.packageName}"),
    )

    /**
     * 本地存储的 SAF document/tree uri → 绝对路径。
     * 仅识别 ExternalStorageProvider（内置存储/外置 SD 的本地目录），
     * 云盘等第三方 provider 返回 null。
     *
     * uri 形态（pathSegments 解码后）：
     *  - 根目录 tree uri：  [tree, "primary:Movies"]                    → 取 tree 段
     *  - 子文档 uri：       [tree, X, document, "primary:Movies/a.mp4"]  → 取 document 段
     *  - 平铺 document uri：[document, "primary:a.mp4"]                  → 取 document 段
     * 子文档误取 tree 段会把每个文件都映射到根目录，必须先判 document 段。
     */
    fun docUriToPath(uri: Uri): String? = try {
        if (uri.scheme != "content") null
        else if (uri.authority != "com.android.externalstorage.documents") null
        else {
            val segs = uri.pathSegments
            val docId = when {
                segs.size >= 2 && segs[0] == "document" -> DocumentsContract.getDocumentId(uri)
                segs.size >= 4 && segs[2] == "document" -> DocumentsContract.getDocumentId(uri)
                segs.isNotEmpty() && segs[0] == "tree" -> DocumentsContract.getTreeDocumentId(uri)
                else -> DocumentsContract.getDocumentId(uri)
            }
            docIdToPath(docId)
        }
    } catch (_: Exception) {
        null
    }

    /** ExternalStorageProvider 的 docId（形如 "primary:Movies/a.mp4"）→ 绝对路径 */
    private fun docIdToPath(docId: String): String? {
        val sep = docId.indexOf(':')
        if (sep <= 0) return null
        val volume = docId.substring(0, sep)
        val rel = docId.substring(sep + 1)
        val root = if (volume == "primary") "/storage/emulated/0" else "/storage/$volume"
        return if (rel.isEmpty()) root else "$root/$rel"
    }

    /** tree uri → 其根目录 File（已授权全部文件权限且为本地存储卷时非空） */
    fun treeRootFile(context: Context, treeUri: Uri): File? {
        if (!hasAllFilesAccess(context)) return null
        if (treeUri.authority != "com.android.externalstorage.documents") return null
        val docId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (_: Exception) {
            return null
        }
        return docIdToPath(docId)?.let { File(it) }?.takeIf { it.isDirectory }
    }

    /**
     * 树内文件/目录 → 树作用域 document uri（content://.../tree/X/document/Y），
     * 与 DocumentFile.listFiles 得到的子文档 uri 完全同构。直路径扫描时用它
     * 构造条目的 docUri/folderUri，保证条目身份（覆盖参数、列表 key 均以
     * docUri 为键）与 SAF 扫描一致——两种扫描模式互切不丢用户逐文件设置。
     */
    fun buildChildUri(treeUri: Uri, file: File): Uri? {
        return try {
            val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val volume = treeDocId.substringBefore(':', "")
            if (volume.isEmpty()) null
            else {
                val volumeRoot = if (volume == "primary") "/storage/emulated/0" else "/storage/$volume"
                val abs = file.absolutePath
                val rel = when {
                    abs == volumeRoot -> ""
                    abs.startsWith("$volumeRoot/") -> abs.removePrefix("$volumeRoot/")
                    else -> return null
                }
                DocumentsContract.buildDocumentUriUsingTree(treeUri, "$volume:$rel")
            }
        } catch (_: Exception) {
            null
        }
    }

    /** uri 对应的已存在文件/目录（须已具备直接读写能力，否则 null） */
    fun accessibleFile(context: Context, uri: Uri): File? {
        if (!hasAllFilesAccess(context)) return null
        return docUriToPath(uri)?.let { p -> File(p).takeIf { it.exists() } }
    }

    /** uri 对应的可写目标文件（文件本身可不存在，父目录必须存在且可写；否则 null） */
    fun writableTarget(context: Context, uri: Uri): File? {
        if (!hasAllFilesAccess(context)) return null
        val path = docUriToPath(uri) ?: return null
        val parent = File(path).parentFile ?: return null
        return if (parent.isDirectory && parent.canWrite()) File(path) else null
    }
}
