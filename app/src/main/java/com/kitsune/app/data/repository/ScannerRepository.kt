package com.kitsune.app.data.repository

import android.net.Uri
import androidx.room.withTransaction
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
    private val settingsRepository: SettingsRepository
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
     * Performs a full incremental scan for both Comics and Videos in parallel.
     * REVISION 12.2.1: Added request locking and timestamp update.
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
            // Populate searchTags and extra metadata from metadata.json during scan
            val metadata = metadataManager.readMetadata(rootUri, comic.relativePath)
            
            // Priority: JSON metadata takes precedence over parsed folder name (REVISION 11.2.7)
            val finalAuthor = metadata.author ?: comic.author
            val finalLanguage = metadata.language ?: comic.language
            val finalType = metadata.type ?: comic.type
            
            val searchTags = if (metadata.tags.isEmpty()) null else metadata.tags.joinToString(" ")
            
            comic.copy(
                author = finalAuthor,
                language = finalLanguage,
                type = finalType
            ).toEntity(searchTags)
        }.filter { entity ->
            val cached = cacheMap[entity.relativePath]
            // Compare including searchTags to ensure index is updated if JSON changed
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
            // Populate searchTags from metadata.json during scan
            val metadata = metadataManager.readMetadata(rootUri, entity.relativePath)
            val searchTags = if (metadata.tags.isEmpty()) null else metadata.tags.joinToString(" ")
            
            entity.copy(searchTags = searchTags)
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
