package com.example.kitsune.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.kitsune.data.entity.ReadingProgressEntity

/**
 * Data Access Object for [ReadingProgressEntity].
 *
 * Provides operations to save and retrieve reading progress.
 * Each comic has at most one progress record (PK = comicId).
 *
 * Reading progress intentionally has no foreign key to [com.example.kitsune.data.entity.ChapterEntity],
 * so deleting or re-scanning chapters does not destroy user progress.
 */
@Dao
interface ReadingProgressDao {

    /**
     * Retrieves the reading progress for a specific comic.
     */
    @Query("SELECT * FROM reading_progress WHERE comicId = :comicId")
    suspend fun getByComicId(comicId: Long): ReadingProgressEntity?

    /**
     * Saves reading progress for a comic.
     * If progress already exists for this comic, it is replaced atomically.
     *
     * Uses [OnConflictStrategy.REPLACE] which is safe here because:
     * - The PK is `comicId` (not auto-generated), so the row identity is stable.
     * - No other table has a foreign key pointing TO `reading_progress`.
     * - Replacing the row does not trigger any CASCADE deletes elsewhere.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(progress: ReadingProgressEntity)

    /**
     * Deletes reading progress for a specific comic.
     */
    @Query("DELETE FROM reading_progress WHERE comicId = :comicId")
    suspend fun deleteByComicId(comicId: Long)

    /**
     * Retrieves all reading progress records (useful for history display).
     */
    @Query("SELECT * FROM reading_progress ORDER BY updatedAt DESC")
    suspend fun getAll(): List<ReadingProgressEntity>
}

