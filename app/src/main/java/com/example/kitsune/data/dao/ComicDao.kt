package com.example.kitsune.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.kitsune.data.entity.ComicEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for [ComicEntity].
 *
 * Provides reactive [Flow]-based queries for UI observation and suspend functions
 * for data mutations. Identity is determined by the unique constraint
 * `(rootUriString, relativePath)`.
 */
@Dao
interface ComicDao {

    // ── Reactive queries (Flow) ──

    /**
     * Observes a comic by its database ID.
     */
    @Query("SELECT * FROM comics WHERE id = :comicId")
    fun observeComicById(comicId: Long): Flow<ComicEntity?>

    /**
     * Observes all comics under a specific storage root, ordered by title.
     */
    @Query("SELECT * FROM comics WHERE rootUriString = :rootUri ORDER BY title COLLATE NOCASE ASC")
    fun observeComicsByRoot(rootUri: String): Flow<List<ComicEntity>>

    /**
     * Observes bookmarked comics under a specific storage root, ordered by title.
     */
    @Query("SELECT * FROM comics WHERE rootUriString = :rootUri AND isBookmarked = 1 ORDER BY title COLLATE NOCASE ASC")
    fun observeBookmarkedComicsByRoot(rootUri: String): Flow<List<ComicEntity>>

    /**
     * Observes recently read comics under a specific storage root, ordered by most recent first.
     * Only includes comics that have been read at least once.
     */
    @Query("SELECT * FROM comics WHERE rootUriString = :rootUri AND lastReadAt IS NOT NULL ORDER BY lastReadAt DESC")
    fun observeRecentlyReadByRoot(rootUri: String): Flow<List<ComicEntity>>

    /**
     * Observes recently read comics along with their latest reading progress relation.
     * Primary order by [ComicEntity.lastReadAt] descending, secondary order by title ascending (case-insensitive) for determinism.
     */
    @Transaction
    @Query("SELECT * FROM comics WHERE rootUriString = :rootUri AND lastReadAt IS NOT NULL ORDER BY lastReadAt DESC, title COLLATE NOCASE ASC")
    fun observeRecentlyReadWithProgressByRoot(rootUri: String): Flow<List<com.example.kitsune.data.model.ComicWithProgress>>

    // ── One-shot queries (suspend) ──

    /**
     * Retrieves a comic by its database ID.
     */
    @Query("SELECT * FROM comics WHERE id = :comicId")
    suspend fun getById(comicId: Long): ComicEntity?

    /**
     * Retrieves a comic by its unique identity within a root.
     */
    @Query("SELECT * FROM comics WHERE rootUriString = :rootUri AND relativePath = :relativePath")
    suspend fun getByRootAndPath(rootUri: String, relativePath: String): ComicEntity?

    /**
     * Retrieves all comics under a specific storage root (non-reactive snapshot).
     */
    @Query("SELECT * FROM comics WHERE rootUriString = :rootUri")
    suspend fun getAllByRoot(rootUri: String): List<ComicEntity>

    // ── Mutations ──

    /**
     * Inserts a new comic. Returns the generated row ID.
     * Uses [OnConflictStrategy.ABORT] to fail on duplicate unique constraint
     * `(rootUriString, relativePath)`, preventing accidental data loss from REPLACE.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(comic: ComicEntity): Long

    /**
     * Updates an existing comic identified by its primary key.
     */
    @Update
    suspend fun update(comic: ComicEntity)

    /**
     * Inserts a comic or updates it if a matching `(rootUriString, relativePath)` already exists.
     * This avoids [OnConflictStrategy.REPLACE] which would delete-then-reinsert, triggering
     * CASCADE deletes on chapters and reading progress.
     */
    @Transaction
    suspend fun upsertByRootAndPath(comic: ComicEntity): Long {
        val existing = getByRootAndPath(comic.rootUriString, comic.relativePath)
        return if (existing != null) {
            update(
                comic.copy(
                    id = existing.id,
                    // Preserve user state during scan reconciliation
                    isBookmarked = existing.isBookmarked,
                    lastReadAt = existing.lastReadAt
                )
            )
            existing.id
        } else {
            insert(comic)
        }
    }

    /**
     * Toggles the bookmark state for a comic.
     */
    @Query("UPDATE comics SET isBookmarked = :bookmarked WHERE id = :comicId")
    suspend fun setBookmarked(comicId: Long, bookmarked: Boolean)

    /**
     * Updates the last read timestamp for a comic.
     */
    @Query("UPDATE comics SET lastReadAt = :timestamp WHERE id = :comicId")
    suspend fun updateLastReadAt(comicId: Long, timestamp: Long)

    /**
     * Deletes a comic by ID. CASCADE foreign keys will remove associated chapters
     * and reading progress.
     */
    @Query("DELETE FROM comics WHERE id = :comicId")
    suspend fun deleteById(comicId: Long)

    /**
     * Deletes all comics under a specific root. Used when a root is removed or changed.
     */
    @Query("DELETE FROM comics WHERE rootUriString = :rootUri")
    suspend fun deleteAllByRoot(rootUri: String)

    /**
     * Returns comic IDs for a root that are no longer present in the given set of relative paths.
     * Used during scan reconciliation to identify removed comics.
     */
    @Query("SELECT id FROM comics WHERE rootUriString = :rootUri AND relativePath NOT IN (:currentPaths)")
    suspend fun findOrphanedIds(rootUri: String, currentPaths: List<String>): List<Long>
}

