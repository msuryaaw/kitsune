package com.kitsune.app.data.repository

import android.net.Uri
import androidx.room.withTransaction
import com.kitsune.app.database.AppDatabase
import com.kitsune.app.database.dao.ComicDao
import com.kitsune.app.database.dao.VideoDao
import com.kitsune.app.database.entity.ComicEntity
import com.kitsune.app.domain.model.Chapter
import com.kitsune.app.domain.model.Comic
import com.kitsune.app.domain.model.Episode
import com.kitsune.app.scanner.ComicScanner
import com.kitsune.app.scanner.ScannerCoordinator
import com.kitsune.app.scanner.VideoScanner
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.coroutines.coroutineContext

/**
 * Repository for managing media scanning operations (Comics & Videos).
 * Coordinates between scanners and the database using ScannerCoordinator.
 * 
 * REVISION 10.2.5: Refactored to use modular scanner architecture and parallel coordination.
 */
class ScannerRepository(
    private val comicScanner: ComicScanner,
    private val comicDao: ComicDao,
    private val videoScanner: VideoScanner,
    private val videoDao: VideoDao,
    private val database: AppDatabase,
    private val coordinator: ScannerCoordinator
) {
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
     * Get a single comic by its relative path.
     */
    suspend fun getComicByPath(relativePath: String): Comic? {
        return comicDao.getComicByPath(relativePath)?.toDomain()
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
     */
    suspend fun performIncrementalScan(rootUri: Uri) {
        onScanStarted?.invoke()

        coordinator.fullScan(
            rootUri = rootUri,
            comicAction = { uri -> scanComicsIncremental(uri) },
            videoAction = { uri -> scanVideosIncremental(uri) }
        )

        onScanFinished?.invoke(rootUri)
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

        val toInsert = scannedComics.map { it.toEntity() }.filter { entity ->
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
     */
    suspend fun scanVideosIncremental(rootUri: Uri) {
        if (!videoScanner.isCategoryFolderValid(rootUri, "Videos")) return

        val cachedVideos = videoDao.getAllVideosSync()
        val cacheMap = cachedVideos.associateBy { it.relativePath }

        val scannedVideos = videoScanner.scanVideos(rootUri) { path, lastMod ->
            val cached = cacheMap[path]
            if (cached != null && cached.lastModified == lastMod) {
                cached.coverUri
            } else {
                null
            }
        }

        val scannedPaths = scannedVideos.map { it.relativePath }.toSet()
        val toDelete = cachedVideos
            .filter { it.relativePath !in scannedPaths }
            .map { it.relativePath }

        val toInsert = scannedVideos.filter { entity ->
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
        relativePath = relativePath,
        coverUri = coverUri,
        lastModified = lastModified
    )

    private fun Comic.toEntity() = ComicEntity(
        title = title,
        relativePath = relativePath,
        coverUri = coverUri,
        lastModified = lastModified
    )
}
