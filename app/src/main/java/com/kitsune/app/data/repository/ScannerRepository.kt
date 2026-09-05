package com.kitsune.app.data.repository

import android.net.Uri
import androidx.room.withTransaction
import com.kitsune.app.core.StorageHelper
import com.kitsune.app.data.metadata.MediaMetadata
import com.kitsune.app.data.metadata.MetadataManager
import com.kitsune.app.database.AppDatabase
import com.kitsune.app.database.dao.ComicDao
import com.kitsune.app.database.dao.VideoDao
import com.kitsune.app.database.entity.ComicEntity
import com.kitsune.app.database.entity.VideoEntity
import com.kitsune.app.domain.model.Chapter
import com.kitsune.app.domain.model.Comic
import com.kitsune.app.domain.model.Episode
import com.kitsune.app.scanner.ComicScanner
import com.kitsune.app.scanner.ScannerCoordinator
import com.kitsune.app.scanner.VideoScanner
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext

/**
 * Repository for managing media scanning operations (Comics & Videos).
 * Coordinates between scanners and the database using ScannerCoordinator.
 * 
 * REVISION 10.2.5: Refactored to use modular scanner architecture and parallel coordination.
 * REVISION 11.1.4: Integrated MetadataManager for search tag indexing during scans.
 */
class ScannerRepository(
    private val comicScanner: ComicScanner,
    private val comicDao: ComicDao,
    private val videoScanner: VideoScanner,
    private val videoDao: VideoDao,
    private val database: AppDatabase,
    private val coordinator: ScannerCoordinator,
    private val metadataManager: MetadataManager,
    private val settingsRepository: SettingsRepository,
    private val storageHelper: StorageHelper
) {
    /**
     * Active request lock to prevent overlapping calls from ViewModel.
     */
    private val scanMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * Listener to notify other components that scanning is about to start.
     */
    var onScanStarted: (() -> Unit)? = null

    /**
     * Listener to notify other components that scanning has finished.
     */
    var onScanFinished: (suspend (Uri) -> Unit)? = null

    /**
     * Physically deletes a comic and cleans up all related database data.
     * REVISION Delete Feature: Secure destructive deletion through SAF and Room.
     */
    suspend fun deleteComic(rootUri: Uri, comic: Comic): Result<Unit> {
        if (scanMutex.isLocked) return Result.failure(Exception("Scanner is busy"))
        
        return scanMutex.withLock {
            // 1. Delete physical folder via SAF
            val fsResult = storageHelper.deleteFileByRelativePath(rootUri, comic.relativePath)
            if (fsResult.isFailure) return@withLock fsResult
            
            // 2. Cleanup Database (Transactional)
            try {
                database.withTransaction {
                    comicDao.deleteComicAndRelatedData(
                        relativePath = comic.relativePath,
                        deleteProgress = { path -> database.readingProgressDao().deleteProgress(path) },
                        deleteBookmarks = { path -> database.bookmarkDao().removeComicFromAllBookmarks(path) }
                    )
                }
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * Data stream for comics.
     */
    val allComics: Flow<List<Comic>> = comicDao.getAllComics().map { entities ->
        entities.map { it.toDomain() }
    }

    /**
     * Observable scanning states from Coordinator.
     * REVISION 11.2.6: Exposed for UI synchronization.
     */
    val isScanningComics: StateFlow<Boolean> = coordinator.isScanningComics
    val isScanningVideos: StateFlow<Boolean> = coordinator.isScanningVideos

    val isScanning: Flow<Boolean> = combine(
        isScanningComics,
        isScanningVideos
    ) { comics, videos -> comics || videos }

    /**
     * Get a single comic by its relative path.
     */
    suspend fun getComicByPath(relativePath: String): Comic? {
        return comicDao.getComicByPath(relativePath)?.toDomain()
    }

    /**
     * Updates the search index for a specific comic.
     * REVISION 11.1.5: Direct update for tag synchronization.
     */
    suspend fun updateComicSearchTags(path: String, tags: List<String>) {
        val searchTags = if (tags.isEmpty()) null else tags.joinToString(" ")
        comicDao.updateSearchTags(path, searchTags)
    }

    /**
     * Updates the search index for a specific video.
     */
    suspend fun updateVideoSearchTags(path: String, tags: List<String>) {
        val searchTags = if (tags.isEmpty()) null else tags.joinToString(" ")
        videoDao.updateSearchTags(path, searchTags)
    }

    /**
     * Scans for chapters of a specific comic.
     */
    suspend fun getChapters(rootUri: Uri, comicRelativePath: String): List<Chapter> {
        return comicScanner.scanChapters(rootUri, comicRelativePath)
    }

    /**
     * Scans for episodes of a specific video (Lazy Scan).
     */
    suspend fun getEpisodes(rootUri: Uri, videoRelativePath: String): List<Episode> {
        return videoScanner.scanEpisodes(rootUri, videoRelativePath)
    }

    /**
     * Performs a full incremental scan for both Comics and Videos.
     * REVISION Masalah 1: Removed cooldown. Manual scan is always allowed when requested.
     */
    suspend fun performIncrementalScan(rootUri: Uri) {
        if (scanMutex.isLocked) return
        
        scanMutex.withLock {
            onScanStarted?.invoke()
            try {
                coordinator.fullScan(
                    rootUri = rootUri,
                    comicAction = { uri -> scanComicsIncremental(uri) },
                    videoAction = { uri -> scanVideosIncremental(uri) }
                )

                // Update success timestamp
                settingsRepository.updateLastScanTime(System.currentTimeMillis())
            } finally {
                onScanFinished?.invoke(rootUri)
            }
        }
    }

    /**
     * Specific incremental scan for Comics.
     * REVISION Masalah 1: Optimized metadata sync. 
     * Only reads metadata.json if the folder has been modified since last scan.
     */
    suspend fun scanComicsIncremental(rootUri: Uri) {
        if (!comicScanner.isCategoryFolderValid(rootUri, "Comics")) return

        val cachedComics = comicDao.getAllComicsSync()
        val cacheMap = cachedComics.associateBy { it.relativePath }

        val scannedComics = comicScanner.scanComics(rootUri) { path, lastMod ->
            val cached = cacheMap[path]
            if (cached != null && cached.lastModified == lastMod) {
                cached.coverUri
            } else {
                null
            }
        }

        val scannedPaths = scannedComics.map { it.relativePath }.toSet()
        val toDelete = cachedComics
            .filter { it.relativePath !in scannedPaths }
            .map { it.relativePath }

        val toInsert = scannedComics.map { comic ->
            val cached = cacheMap[comic.relativePath]
            
            // Optimization: Only read metadata.json if folder is new or changed
            if (cached == null || cached.lastModified != comic.lastModified) {
                // Auto-generate metadata.json if missing
                if (!metadataManager.exists(rootUri, comic.relativePath)) {
                    val autoMetadata = MediaMetadata(
                        title = comic.displayTitle,
                        author = comic.author,
                        language = comic.language,
                        type = comic.type
                    )
                    metadataManager.writeMetadata(rootUri, comic.relativePath, autoMetadata)
                }

                // Populate searchTags and extra metadata from metadata.json during scan
                val metadata = metadataManager.readMetadata(rootUri, comic.relativePath)
                
                val finalTitle = metadata.title ?: comic.displayTitle
                val finalAuthor = metadata.author ?: comic.author
                val finalLanguage = metadata.language ?: comic.language
                val finalType = metadata.type ?: comic.type
                
                val searchTags = if (metadata.tags.isEmpty()) null else metadata.tags.joinToString(" ")
                
                comic.copy(
                    displayTitle = finalTitle,
                    author = finalAuthor,
                    language = finalLanguage,
                    type = finalType
                ).toEntity(searchTags)
            } else {
                // Use cached entity if nothing changed
                cached
            }
        }.filter { entity ->
            val cached = cacheMap[entity.relativePath]
            cached == null || cached != entity
        }

        if (toInsert.isNotEmpty() || toDelete.isNotEmpty()) {
            database.withTransaction {
                coroutineContext.ensureActive() // Cancellation safety
                comicDao.updateLibrary(toInsert, toDelete)
            }
        }
    }

    /**
     * Specific incremental scan for Videos.
     * REVISION Masalah 1: Optimized to skip metadata.json if folder timestamp is unchanged.
     */
    suspend fun scanVideosIncremental(rootUri: Uri) {
        if (!videoScanner.isCategoryFolderValid(rootUri, "Videos")) return

        val cachedVideos = videoDao.getAllVideosSync()
        val cacheMap = cachedVideos.associateBy { it.relativePath }

        val scannedEntities = videoScanner.scanVideos(rootUri) { path, lastMod ->
            val cached = cacheMap[path]
            if (cached != null && cached.lastModified == lastMod) {
                cached.coverUri
            } else {
                null
            }
        }

        val scannedPaths = scannedEntities.map { it.relativePath }.toSet()
        val toDelete = cachedVideos
            .filter { it.relativePath !in scannedPaths }
            .map { it.relativePath }

        val toInsert = scannedEntities.map { entity ->
            val cached = cacheMap[entity.relativePath]
            
            if (cached == null || cached.lastModified != entity.lastModified) {
                // Populate searchTags from metadata.json during scan
                val metadata = metadataManager.readMetadata(rootUri, entity.relativePath)
                val searchTags = if (metadata.tags.isEmpty()) null else metadata.tags.joinToString(" ")
                entity.copy(searchTags = searchTags)
            } else {
                cached
            }
        }.filter { entity ->
            val cached = cacheMap[entity.relativePath]
            cached == null || cached != entity
        }

        if (toInsert.isNotEmpty() || toDelete.isNotEmpty()) {
            database.withTransaction {
                coroutineContext.ensureActive() // Cancellation safety
                videoDao.updateLibrary(toInsert, toDelete)
            }
        }
    }

    private fun ComicEntity.toDomain() = Comic(
        title = title,
        displayTitle = displayTitle,
        author = author,
        language = language,
        type = type,
        relativePath = relativePath,
        coverUri = coverUri,
        lastModified = lastModified,
        searchTags = searchTags,
        chapterCount = chapterCount
    )

    private fun Comic.toEntity(searchTags: String?) = ComicEntity(
        title = title,
        displayTitle = displayTitle,
        author = author,
        language = language,
        type = type,
        relativePath = relativePath,
        coverUri = coverUri,
        lastModified = lastModified,
        searchTags = searchTags,
        chapterCount = chapterCount
    )
}
