package com.xixka.losslesstrim.trim

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.os.StatFs
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * SAF 文档查询（基于 DocumentsContract / DocumentFile）。
 *
 * 2026-09-11 清理（L6）：SAF 数据通道整体移除后，findChild/create/delete/
 * rename/exists/copyTo 等写侧操作已无调用方（copyTo 的"绝不留半成品"纪律由
 * TrimService 单文件/目录模式统一的 .part → 校验 → 原子改名流程承接），仅保留
 * 单文件模式扫描仍在用的三个只读查询。
 */
object DocUtils {

    fun length(context: Context, uri: Uri): Long {
        return try {
            DocumentFile.fromSingleUri(context, uri)?.length() ?: -1L
        } catch (e: Exception) {
            -1L
        }
    }

    /** 查询 DISPLAY_NAME（单文件模式取文件名） */
    fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(
                uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 尽力估算 tree 目录的可用空间（仅主存储可解析） */
    fun freeBytesOfTree(treeUri: Uri): Long? {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            if (!docId.startsWith("primary:")) return null
            val rel = docId.removePrefix("primary:")
            val path = File(android.os.Environment.getExternalStorageDirectory(), rel)
            if (!path.exists()) return null
            StatFs(path.absolutePath).availableBytes
        } catch (e: Exception) {
            null
        }
    }
}
