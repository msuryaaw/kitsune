package com.kitsune.app.data.repository

import com.kitsune.app.database.dao.ComicDao
import com.kitsune.app.database.dao.ReadingProgressDao
import com.kitsune.app.database.entity.ReadingProgressEntity
import com.kitsune.app.domain.model.Comic
import com.kitsune.app.domain.model.LastReadComic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/**
 * Repository untuk mengelola data progres membaca.
 */
class ReadingProgressRepository(
    private val readingProgressDao: ReadingProgressDao,
    private val comicDao: ComicDao
) {

    /**
     * Mendapatkan aliran data progres untuk sebuah komik (chapter terakhir yang dibuka).
     */
    fun getProgressByComic(comicPath: String): Flow<ReadingProgressEntity?> {
        return readingProgressDao.getProgressByComic(comicPath)
    }

    /**
     * Mendapatkan progres komik (chapter terakhir) secara sinkron.
     */
    suspend fun getProgressByComicSync(comicPath: String): ReadingProgressEntity? {
        return readingProgressDao.getProgressByComicSync(comicPath)
    }

    /**
     * Mendapatkan progres spesifik untuk sebuah chapter secara sinkron.
     */
    suspend fun getProgressByChapterSync(chapterPath: String): ReadingProgressEntity? {
        return readingProgressDao.getProgressByChapterSync(chapterPath)
    }

    /**
     * Menyimpan atau memperbarui progres membaca.
     * Karena menggunakan composite unique index, ini akan meng-update progress chapter jika sudah ada,
     * atau membuat entri baru jika belum ada.
     */
    suspend fun saveProgress(
        comicRelativePath: String,
        chapterRelativePath: String,
        pageNumber: Int,
        totalPages: Int
    ) {
        val progress = ReadingProgressEntity(
            comicRelativePath = comicRelativePath,
            chapterRelativePath = chapterRelativePath,
            pageNumber = pageNumber,
            totalPages = totalPages,
            lastReadAt = System.currentTimeMillis()
        )
        readingProgressDao.saveProgress(progress)
    }

    /**
     * Menghapus progres membaca untuk komik tertentu.
     */
    suspend fun deleteProgress(comicPath: String) {
        readingProgressDao.deleteProgress(comicPath)
    }

    /**
     * Menghapus seluruh riwayat membaca.
     */
    suspend fun clearAllHistory() {
        readingProgressDao.clearAllProgress()
    }

    /**
     * Mendapatkan progres membaca terbaru secara global yang digabungkan dengan data komik.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun getLatestReadComic(): Flow<LastReadComic?> {
        return readingProgressDao.getLatestProgress().flatMapLatest { progress ->
            if (progress == null) {
                flowOf(null)
            } else {
                val comicEntity = comicDao.getComicByPath(progress.comicRelativePath)
                if (comicEntity != null) {
                    flowOf(
                        LastReadComic(
                            comic = Comic(
                                title = comicEntity.title,
                                displayTitle = comicEntity.displayTitle,
                                author = comicEntity.author,
                                language = comicEntity.language,
                                relativePath = comicEntity.relativePath,
                                coverUri = comicEntity.coverUri,
                                lastModified = comicEntity.lastModified,
                                searchTags = comicEntity.searchTags
                            ),
                            progress = progress
                        )
                    )
                } else {
                    flowOf(null)
                }
            }
        }
    }
}
