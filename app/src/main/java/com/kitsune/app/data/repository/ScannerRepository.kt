package com.kitsune.app.data.repository

import android.net.Uri
import com.kitsune.app.database.dao.ComicDao
import com.kitsune.app.database.entity.ComicEntity
import com.kitsune.app.domain.model.Chapter
import com.kitsune.app.domain.model.Comic
import com.kitsune.app.scanner.ComicScanner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Repository untuk mengelola operasi pemindaian media.
 * Menghubungkan Scanner Engine dengan domain layer dan database cache.
 */
class ScannerRepository(
    private val comicScanner: ComicScanner,
    private val comicDao: ComicDao
) {
    companion object {
        /**
         * REVISION 6.7.6: Mutex static untuk menjamin hanya satu proses scan yang berjalan pada satu waktu 
         * di seluruh aplikasi, bahkan jika Activity/Repository direkonstruksi (config change).
         */
        private val scanMutex = Mutex()
    }

    /**
     * Aliran data komik yang tersimpan di database.
     */
    val allComics: Flow<List<Comic>> = comicDao.getAllComics().map { entities ->
        entities.map { it.toDomain() }
    }

    /**
     * Mengambil satu komik berdasarkan relative path.
     */
    suspend fun getComicByPath(relativePath: String): Comic? {
        // Cek cache database terlebih dahulu
        return comicDao.getAllComicsSync().find { it.relativePath == relativePath }?.toDomain()
    }

    /**
     * Memindai daftar chapter untuk komik tertentu.
     */
    suspend fun getChapters(rootUri: Uri, comicRelativePath: String): List<Chapter> {
        return comicScanner.scanChapters(rootUri, comicRelativePath)
    }

    /**
     * Melakukan pemindaian inkremental pada folder komik.
     * REVISION 6.7.6: Sinkronisasi mutlak via Mutex untuk mencegah Race Condition pada filesystem.
     */
    suspend fun performIncrementalScan(rootUri: Uri) = scanMutex.withLock {
        // STABILITY FIX: Proteksi agar tidak menghapus database jika folder kategori tidak ditemukan/error
        if (!comicScanner.isCategoryFolderValid(rootUri, "Comics")) {
            return@withLock
        }

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

        // OPTIMIZATION: Hanya masukkan komik yang baru atau yang datanya berubah
        val toInsert = scannedComics.map { it.toEntity() }.filter { entity ->
            val cached = cacheMap[entity.relativePath]
            // Jika tidak ada di cache (baru) atau data berbeda (update), masukkan ke list
            cached == null || cached != entity
        }

        if (toInsert.isNotEmpty() || toDelete.isNotEmpty()) {
            comicDao.updateLibrary(toInsert, toDelete)
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
