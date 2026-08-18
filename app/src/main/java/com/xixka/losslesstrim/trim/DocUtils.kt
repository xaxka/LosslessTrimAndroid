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

    /** 流拷贝兜底（rename 失败时用） */
    fun copyTo(context: Context, srcUri: Uri, folderUri: Uri, mime: String, displayName: String): Uri? {
        return try {
            val dst = create(context, folderUri, mime, displayName) ?: return null
            context.contentResolver.openInputStream(srcUri)?.use { input ->
                context.contentResolver.openOutputStream(dst, "w")?.use { output ->
                    input.copyTo(output, 1024 * 256)
                }
            } ?: return dst
            dst
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
