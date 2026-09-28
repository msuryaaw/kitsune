package com.kitsune.app.database.dao

import androidx.room.*
import com.kitsune.app.database.entity.ReadingProgressEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingProgressDao {
    /**
     * Mendapatkan progres terakhir dibaca untuk komik tertentu (chapter terbaru).
     */
    @Query("SELECT * FROM reading_progress WHERE comicRelativePath = :comicPath ORDER BY lastReadAt DESC LIMIT 1")
    fun getProgressByComic(comicPath: String): Flow<ReadingProgressEntity?>

    /**
     * Mendapatkan progres terakhir dibaca untuk komik tertentu secara sinkron.
     */
    @Query("SELECT * FROM reading_progress WHERE comicRelativePath = :comicPath ORDER BY lastReadAt DESC LIMIT 1")
    suspend fun getProgressByComicSync(comicPath: String): ReadingProgressEntity?

    /**
     * Mendapatkan progres spesifik untuk sebuah chapter.
     */
    @Query("SELECT * FROM reading_progress WHERE chapterRelativePath = :chapterPath LIMIT 1")
    suspend fun getProgressByChapterSync(chapterPath: String): ReadingProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveProgress(progress: ReadingProgressEntity)

    @Query("DELETE FROM reading_progress WHERE comicRelativePath = :comicPath")
    suspend fun deleteProgress(comicPath: String)

    /**
     * Menghapus seluruh riwayat membaca.
     */
    @Query("DELETE FROM reading_progress")
    suspend fun clearAllProgress()

    /**
     * Mendapatkan progres paling baru secara global (untuk Continue Reading di Home).
     */
    @Query("SELECT * FROM reading_progress ORDER BY lastReadAt DESC LIMIT 1")
    fun getLatestProgress(): Flow<ReadingProgressEntity?>

    /**
     * Mendapatkan seluruh riwayat membaca yang dikelompokkan per komik.
     * Mengambil entri terakhir untuk setiap komik.
     */
    @Query("""
        SELECT * FROM reading_progress p1 
        WHERE lastReadAt = (SELECT MAX(lastReadAt) FROM reading_progress p2 WHERE p2.comicRelativePath = p1.comicRelativePath) 
        ORDER BY lastReadAt DESC
    """)
    fun getAllReadHistory(): Flow<List<ReadingProgressEntity>>

    /**
     * Mendapatkan seluruh riwayat membaca beserta metadata komiknya dalam 1 query atomik (JOIN).
     * REVISION HIGH-02 Fix: Mengeliminasi N+1 query problem pada Read History.
     */
    @Query("""
        SELECT 
            p1.id AS id,
            p1.comicRelativePath AS comicRelativePath,
            p1.chapterRelativePath AS chapterRelativePath,
            p1.pageNumber AS pageNumber,
            p1.totalPages AS totalPages,
            p1.lastReadAt AS lastReadAt,
            comics.title AS comic_title,
            comics.displayTitle AS comic_displayTitle,
            comics.author AS comic_author,
            comics.language AS comic_language,
            comics.type AS comic_type,
            comics.relativePath AS comic_relativePath,
            comics.coverUri AS comic_coverUri,
            comics.lastModified AS comic_lastModified,
            comics.searchTags AS comic_searchTags,
            comics.chapterCount AS comic_chapterCount
        FROM reading_progress p1
        INNER JOIN comics ON p1.comicRelativePath = comics.relativePath
        WHERE p1.lastReadAt = (SELECT MAX(p2.lastReadAt) FROM reading_progress p2 WHERE p2.comicRelativePath = p1.comicRelativePath)
        ORDER BY p1.lastReadAt DESC
    """)
    fun getFullReadHistoryWithComic(): Flow<List<ReadHistoryWithComic>>
}

data class ReadHistoryWithComic(
    @Embedded val progress: ReadingProgressEntity,
    @Embedded(prefix = "comic_") val comic: com.kitsune.app.database.entity.ComicEntity
)
