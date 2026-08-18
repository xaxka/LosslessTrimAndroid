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

/** 扫描 SAF 目录下的视频文件并逐个 ffprobe（仅当前目录，不递归子目录） */
object Scanner {

    private val VIDEO_EXTS = setOf(
        "mp4", "m4v", "m4a", "mov", "mkv", "webm", "avi", "flv",
        "ts", "m2ts", "mts", "mpeg", "mpg", "3gp", "3g2", "wmv", "ogv"
    )

    suspend fun scanFolder(context: Context, treeUri: Uri): List<VideoEntry> =
        withContext(Dispatchers.IO) {
            val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext emptyList()
            val files = ArrayList<Pair<DocumentFile, DocumentFile>>() // (file, folder)
            collectFiles(root, files)
            if (files.isEmpty()) return@withContext emptyList()

            val sem = Semaphore(4)
            coroutineScope {
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
        }

    private fun collectFiles(
        folder: DocumentFile,
        out: ArrayList<Pair<DocumentFile, DocumentFile>>,
    ) {
        val children = folder.listFiles()
        for (child in children) {
            if (child.isFile) {
                val name = child.name ?: continue
                val ext = name.substringAfterLast('.', "").lowercase()
                val mime = child.type
                val isVideo = ext in VIDEO_EXTS ||
                        (mime != null && mime.startsWith("video/", ignoreCase = true))
                if (isVideo && !name.endsWith(".part")) {
                    out.add(child to folder)
                }
            }
        }
    }
}
