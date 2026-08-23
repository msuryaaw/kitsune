package com.kitsune.app.scanner

import android.content.Context
import android.net.Uri
import com.kitsune.app.core.StorageHelper
import com.kitsune.app.database.entity.VideoEntity
import com.kitsune.app.domain.model.Episode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Engine untuk memindai folder video secara offline.
 * Mewarisi BaseScanner untuk logika akses SAF dan pengurutan natural.
 * 
 * Didesain agar bersifat stateless untuk keamanan threading.
 */
class VideoScanner(
    context: Context,
    storageHelper: StorageHelper
) : BaseScanner(context, storageHelper) {

    private val allowedVideoExtensions = listOf("mp4", "mkv", "mov", "avi", "webm", "m4v", "ts", "3gp")

    /**
     * Memindai folder 'Videos' untuk menghasilkan daftar entitas video.
     * Hanya membaca metadata ringan untuk performa awal yang cepat.
     */
    suspend fun scanVideos(
        rootUri: Uri,
        getExistingCover: (relativePath: String, lastModified: Long) -> String? = { _, _ -> null }
    ): List<VideoEntity> = withContext(Dispatchers.IO) {
        val videosFolder = getCategoryFolder(rootUri, "Videos") ?: return@withContext emptyList()
        
        if (!videosFolder.isDirectory) return@withContext emptyList()

        val videoFolders = videosFolder.listFiles()
            .filter { it.isDirectory }
            .sortedWith { f1, f2 -> 
                naturalOrderComparator.compare(f1.name ?: "", f2.name ?: "") 
            }

        videoFolders.mapNotNull { folder ->
            val title = folder.name ?: return@mapNotNull null
            val relativePath = "Videos/$title"
            val currentLastModified = folder.lastModified()

            // Check if folder contains at least one valid video file
            val videoFiles = folder.listFiles().filter { file ->
                val ext = file.name?.lowercase()?.substringAfterLast('.', "") ?: ""
                file.isFile && ext in allowedVideoExtensions
            }

            if (videoFiles.isEmpty()) return@mapNotNull null

            val cachedCover = getExistingCover(relativePath, currentLastModified)
            val coverUri = cachedCover ?: findCover(folder)?.toString()

            VideoEntity(
                title = title,
                relativePath = relativePath,
                coverUri = coverUri,
                episodeCount = videoFiles.size,
                lastModified = currentLastModified
            )
        }
    }

    /**
     * Scans for episodes (video files) within a specific video folder lazily.
     */
    suspend fun scanEpisodes(rootUri: Uri, videoRelativePath: String): List<Episode> = withContext(Dispatchers.IO) {
        val videosFolder = getCategoryFolder(rootUri, "Videos") ?: return@withContext emptyList()
        val title = videoRelativePath.substringAfter("Videos/").removeSuffix("/")
        val videoFolder = videosFolder.findFile(title) ?: return@withContext emptyList()

        videoFolder.listFiles()
            .filter { file ->
                val ext = file.name?.lowercase()?.substringAfterLast('.', "") ?: ""
                file.isFile && ext in allowedVideoExtensions
            }
            .sortedWith { f1, f2 -> 
                naturalOrderComparator.compare(f1.name ?: "", f2.name ?: "") 
            }
            .map { file ->
                val nameWithExt = file.name ?: "Unknown Episode"
                Episode(
                    name = nameWithExt.substringBeforeLast('.'),
                    relativePath = "$videoRelativePath/$nameWithExt",
                    lastModified = file.lastModified()
                )
            }
    }
}
