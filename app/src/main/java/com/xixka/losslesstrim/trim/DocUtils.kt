package com.xixka.losslesstrim.trim

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.os.StatFs
import androidx.documentfile.provider.DocumentFile
import java.io.File

/** SAF 文档操作（基于 DocumentsContract，folderUri 须为 tree 作用域的 document uri） */
object DocUtils {

    fun findChild(context: Context, folderUri: Uri, name: String): Uri? {
        return try {
            val docId = DocumentsContract.getDocumentId(folderUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(folderUri, docId)
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                ),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    if (c.getString(1) == name) {
                        return DocumentsContract.buildDocumentUriUsingTree(folderUri, c.getString(0))
                    }
                }
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun create(context: Context, folderUri: Uri, mime: String, displayName: String): Uri? {
        return try {
            DocumentsContract.createDocument(context.contentResolver, folderUri, mime, displayName)
        } catch (e: Exception) {
            null
        }
    }

    fun delete(context: Context, uri: Uri): Boolean {
        return try {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        } catch (e: Exception) {
            false
        }
    }

    fun rename(context: Context, uri: Uri, displayName: String): Uri? {
        return try {
            DocumentsContract.renameDocument(context.contentResolver, uri, displayName)
        } catch (e: Exception) {
            null
        }
    }

    fun length(context: Context, uri: Uri): Long {
        return try {
            DocumentFile.fromSingleUri(context, uri)?.length() ?: -1L
        } catch (e: Exception) {
            -1L
        }
    }

    fun exists(context: Context, uri: Uri): Boolean {
        return try {
            DocumentFile.fromSingleUri(context, uri)?.exists() == true
        } catch (e: Exception) {
            false
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

    /** 流拷贝兜底（rename 失败时用）；任一流打开失败则清理目标并返回 null，绝不留下空文件冒充成功 */
    fun copyTo(context: Context, srcUri: Uri, folderUri: Uri, mime: String, displayName: String): Uri? {
        var dst: Uri? = null
        return try {
            dst = create(context, folderUri, mime, displayName) ?: return null
            val input = context.contentResolver.openInputStream(srcUri)
            if (input == null) {
                delete(context, dst)
                return null
            }
            val ok = input.use { ins ->
                context.contentResolver.openOutputStream(dst, "w")?.use { output ->
                    ins.copyTo(output, 1024 * 256)
                    true
                } ?: false
            }
            if (!ok) {
                delete(context, dst)
                return null
            }
            dst
        } catch (e: Exception) {
            // 中途异常：清理已创建的目标，绝不留下半成品文件
            dst?.let { delete(context, it) }
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
