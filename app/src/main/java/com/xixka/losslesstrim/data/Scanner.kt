package com.xixka.losslesstrim.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.xixka.losslesstrim.ffmpeg.Probe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** 扫描结果：视频列表 + 残留中间文件（闪退遗留的备份/临时文件，供恢复提示） */
data class ScanResult(
    val entries: List<VideoEntry>,
    val orphans: List<String>,
)

/** 扫描 SAF 目录下的视频文件并逐个 ffprobe（仅当前目录，不递归子目录） */
object Scanner {

    private val VIDEO_EXTS = setOf(
        "mp4", "m4v", "m4a", "mov", "mkv", "webm", "avi", "flv",
        "ts", "m2ts", "mts", "mpeg", "mpg", "3gp", "3g2", "wmv", "ogv"
    )

    suspend fun scanFolder(context: Context, treeUri: Uri): ScanResult =
        withContext(Dispatchers.IO) {
            val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext ScanResult(emptyList(), emptyList())
            val files = ArrayList<Pair<DocumentFile, DocumentFile>>() // (file, folder)
            val orphans = ArrayList<String>()
            collectFiles(root, files, orphans)
            if (files.isEmpty()) return@withContext ScanResult(emptyList(), orphans)

            val sem = Semaphore(4)
            val entries = coroutineScope {
                files.map { (file, folder) ->
                    async {
                        sem.withPermit {
                            val probe = Probe.probeMedia(context, file.uri)
                            VideoEntry(
                                treeUri = treeUri,
                                folderUri = folder.uri,
                                docUri = file.uri,
                                name = file.name ?: file.uri.toString(),
                                sizeBytes = if (file.length() > 0) file.length() else 0L,
                                probe = probe,
                            )
                        }
                    }
                }.awaitAll().sortedBy { it.name.lowercase() }
            }
            ScanResult(entries, orphans.sorted())
        }

    private fun collectFiles(
        folder: DocumentFile,
        out: ArrayList<Pair<DocumentFile, DocumentFile>>,
        orphans: ArrayList<String>,
    ) {
        val children = folder.listFiles()
        for (child in children) {
            if (child.isFile) {
                val name = child.name ?: continue
                // 上次闪退/失败遗留的中间文件：提示用户可手动恢复，不参与扫描
                if (name.endsWith(".part") || name.contains(".trimbackup.") || name.endsWith(".oldtrim")) {
                    orphans.add(name)
                    continue
                }
                val ext = name.substringAfterLast('.', "").lowercase()
                val mime = child.type
                val isVideo = ext in VIDEO_EXTS ||
                        (mime != null && mime.startsWith("video/", ignoreCase = true))
                if (isVideo) {
                    out.add(child to folder)
                }
            }
        }
    }
}
