package com.kitsune.app.data.repository

import android.net.Uri
import com.kitsune.app.database.dao.ComicDao
import com.kitsune.app.database.dao.VideoDao
import com.kitsune.app.database.entity.ComicEntity
import com.kitsune.app.database.entity.VideoEntity
import com.kitsune.app.domain.model.Chapter
import com.kitsune.app.domain.model.Comic
import com.kitsune.app.domain.model.Episode
import com.kitsune.app.scanner.ComicScanner
import com.kitsune.app.scanner.VideoScanner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Repository untuk mengelola operasi pemindaian media (Komik & Video).
 * Bertanggung jawab atas koordinasi antara filesystem dan database melalui Mutex global.
 */
class ScannerRepository(
    private val comicScanner: ComicScanner,
    private val comicDao: ComicDao,
    private val videoScanner: VideoScanner,
    private val videoDao: VideoDao
) {
    companion object {
        /**
         * Mutex static untuk menjamin hanya satu proses scan yang berjalan pada satu waktu 
         * di seluruh aplikasi guna mencegah race condition pada SAF I/O.
         */
        private val scanMutex = Mutex()
    }

    /**
     * Aliran data komik (Tetap di sini karena Comic Engine belum direfactor ke ComicRepository).
     */
    val allComics: Flow<List<Comic>> = comicDao.getAllComics().map { entities ->
        entities.map { it.toDomain() }
    }

    /**
     * Mengambil satu komik berdasarkan relative path.
     */
    suspend fun getComicByPath(relativePath: String): Comic? {
        return comicDao.getComicByPath(relativePath)?.toDomain()
    }

    /**
     * Memindai daftar chapter untuk komik tertentu.
     */
    suspend fun getChapters(rootUri: Uri, comicRelativePath: String): List<Chapter> {
        return comicScanner.scanChapters(rootUri, comicRelativePath)
    }

    /**
     * Memindai daftar episode untuk video tertentu (Lazy Scan).
     * Fungsi ini diakses oleh VideoRepository sebagai bridge.
     */
    suspend fun getEpisodes(rootUri: Uri, videoRelativePath: String): List<Episode> {
        return videoScanner.scanEpisodes(rootUri, videoRelativePath)
    }

    /**
     * Melakukan pemindaian inkremental pada seluruh library (Komik & Video).
     * Menggunakan perlindungan Mutex.
     */
    suspend fun performIncrementalScan(rootUri: Uri) = scanMutex.withLock {
        scanComicsIncremental(rootUri)
        scanVideosIncremental(rootUri)
    }

    private suspend fun scanComicsIncremental(rootUri: Uri) {
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
            comicDao.updateLibrary(toInsert, toDelete)
        }
    }

    private suspend fun scanVideosIncremental(rootUri: Uri) {
        if (!videoScanner.isVideosFolderValid(rootUri)) return

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
            videoDao.updateLibrary(toInsert, toDelete)
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
