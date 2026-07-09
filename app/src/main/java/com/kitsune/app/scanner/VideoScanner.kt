package com.kitsune.app.scanner

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.kitsune.app.core.NaturalOrderComparator
import com.kitsune.app.database.entity.VideoEntity
import com.kitsune.app.domain.model.Episode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Engine untuk melakukan scanning folder video secara offline.
 * Mengikuti filosofi Filesystem First dan Lazy Metadata.
 */
class VideoScanner(private val context: Context) {

    private val naturalOrderComparator = NaturalOrderComparator()
    private val allowedVideoExtensions = listOf("mp4", "mkv", "mov", "avi", "webm", "m4v", "ts", "3gp")
    private val allowedImageExtensions = listOf("jpg", "jpeg", "png", "webp")

    // Folder Cache (Mirip dengan ComicScanner untuk performa)
    private var cachedRootUri: Uri? = null
    private var cachedRootDoc: DocumentFile? = null
    private var cachedVideosDoc: DocumentFile? = null

    private fun getRootFolder(rootUri: Uri): DocumentFile? {
        if (cachedRootUri != rootUri || cachedRootDoc == null || !cachedRootDoc!!.exists()) {
            cachedRootUri = rootUri
            cachedRootDoc = DocumentFile.fromTreeUri(context, rootUri)
            cachedVideosDoc = null
        }
        return cachedRootDoc
    }

    private fun getVideosFolder(rootUri: Uri): DocumentFile? {
        val root = getRootFolder(rootUri) ?: return null
        if (cachedVideosDoc == null || !cachedVideosDoc!!.exists()) {
            cachedVideosDoc = root.findFile("Videos")
        }
        return cachedVideosDoc
    }

    /**
     * Verifikasi apakah folder Videos valid.
     */
    fun isVideosFolderValid(rootUri: Uri): Boolean {
        return try {
            val folder = getVideosFolder(rootUri)
            folder != null && folder.exists() && folder.isDirectory
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Memindai folder 'Videos' untuk menghasilkan daftar VideoEntity.
     * Hanya membaca metadata ringan sesuai spesifikasi Phase 7.1.2.
     */
    suspend fun scanVideos(
        rootUri: Uri,
        getExistingCover: (relativePath: String, lastModified: Long) -> String? = { _, _ -> null }
    ): List<VideoEntity> = withContext(Dispatchers.IO) {
        val videosFolder = getVideosFolder(rootUri) ?: return@withContext emptyList()
        
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

            // Cek apakah folder memiliki minimal satu file video valid
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
     * Memindai daftar episode (file video) di dalam folder video tertentu secara lazy.
     */
    suspend fun scanEpisodes(rootUri: Uri, videoRelativePath: String): List<Episode> = withContext(Dispatchers.IO) {
        val videosFolder = getVideosFolder(rootUri) ?: return@withContext emptyList()
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

    private fun findCover(folder: DocumentFile): Uri? {
        return folder.listFiles().find { file ->
            val fileName = file.name?.lowercase() ?: ""
            allowedImageExtensions.any { ext -> fileName == "cover.$ext" }
        }?.uri
    }
}
