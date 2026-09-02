package com.kitsune.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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
}
