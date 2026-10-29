package com.example.kitsune.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.kitsune.data.entity.ChapterEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for [ChapterEntity].
 *
 * Provides reactive and one-shot queries for chapters belonging to a comic.
 * Identity within a comic is determined by the unique constraint `(comicId, relativePath)`.
 */
@Dao
interface ChapterDao {

    // ── Reactive queries (Flow) ──

    /**
     * Observes all chapters for a given comic, ordered by chapter number ascending.
     */
    @Query("SELECT * FROM chapters WHERE comicId = :comicId ORDER BY chapterNumber ASC")
    fun observeChaptersByComicId(comicId: Long): Flow<List<ChapterEntity>>

    // ── One-shot queries (suspend) ──

    /**
     * Retrieves a chapter by its database ID.
     */
    @Query("SELECT * FROM chapters WHERE id = :chapterId")
    suspend fun getById(chapterId: Long): ChapterEntity?

    /**
     * Retrieves a chapter by its unique identity within a comic.
     */
    @Query("SELECT * FROM chapters WHERE comicId = :comicId AND relativePath = :relativePath")
    suspend fun getByComicAndPath(comicId: Long, relativePath: String): ChapterEntity?

    /**
     * Retrieves all chapters for a given comic, ordered by chapter number ascending (snapshot).
     */
    @Query("SELECT * FROM chapters WHERE comicId = :comicId ORDER BY chapterNumber ASC")
    suspend fun getAllByComicId(comicId: Long): List<ChapterEntity>

    /**
     * Returns the count of chapters for a given comic.
     */
    @Query("SELECT COUNT(*) FROM chapters WHERE comicId = :comicId")
    suspend fun countByComicId(comicId: Long): Int

    // ── Mutations ──

    /**
     * Inserts a new chapter. Returns the generated row ID.
     * Uses [OnConflictStrategy.ABORT] to fail on duplicate unique constraint
     * `(comicId, relativePath)`.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(chapter: ChapterEntity): Long

    /**
     * Inserts multiple chapters in a single transaction.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(chapters: List<ChapterEntity>): List<Long>

    /**
     * Updates an existing chapter identified by its primary key.
     */
    @Update
    suspend fun update(chapter: ChapterEntity)

    /**
     * Inserts a chapter or updates it if a matching `(comicId, relativePath)` already exists.
     * This avoids REPLACE strategy which would generate a new ID and could break
     * references in reading progress.
     */
    @Transaction
    suspend fun upsertByComicAndPath(chapter: ChapterEntity): Long {
        val existing = getByComicAndPath(chapter.comicId, chapter.relativePath)
        return if (existing != null) {
            update(chapter.copy(id = existing.id))
            existing.id
        } else {
            insert(chapter)
        }
    }

    /**
     * Deletes a chapter by ID.
     * Note: This does NOT cascade-delete reading progress because there is no
     * foreign key from ReadingProgressEntity to ChapterEntity.
     */
    @Query("DELETE FROM chapters WHERE id = :chapterId")
    suspend fun deleteById(chapterId: Long)

    /**
     * Deletes all chapters for a given comic.
     */
    @Query("DELETE FROM chapters WHERE comicId = :comicId")
    suspend fun deleteAllByComicId(comicId: Long)

    /**
     * Returns chapter IDs for a comic that are no longer present in the given set of relative paths.
     * Used during scan reconciliation.
     */
    @Query("SELECT id FROM chapters WHERE comicId = :comicId AND relativePath NOT IN (:currentPaths)")
    suspend fun findOrphanedIds(comicId: Long, currentPaths: List<String>): List<Long>

    /**
     * Deletes chapters by their IDs.
     */
    @Query("DELETE FROM chapters WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)
}

