package com.kitsune.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.kitsune.app.database.entity.ComicEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ComicDao {
    @Query("SELECT * FROM comics ORDER BY title ASC")
    fun getAllComics(): Flow<List<ComicEntity>>

    @Query("SELECT * FROM comics")
    suspend fun getAllComicsSync(): List<ComicEntity>

    @Query("SELECT * FROM comics WHERE relativePath = :path LIMIT 1")
    suspend fun getComicByPath(path: String): ComicEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertComics(comics: List<ComicEntity>)

    @Query("DELETE FROM comics WHERE relativePath = :path")
    suspend fun deleteByPath(path: String)

    @Query("DELETE FROM comics WHERE relativePath IN (:paths)")
    suspend fun deleteByPaths(paths: List<String>)

    /**
     * Updates the search tags index for a specific comic.
     * REVISION 11.1.2: Added partial update for search indexing.
     */
    @Query("UPDATE comics SET searchTags = :tags WHERE relativePath = :path")
    suspend fun updateSearchTags(path: String, tags: String?)

    @Transaction
    suspend fun updateLibrary(toInsert: List<ComicEntity>, toDelete: List<String>) {
        if (toDelete.isNotEmpty()) {
            deleteByPaths(toDelete)
        }
        if (toInsert.isNotEmpty()) {
            insertComics(toInsert)
        }
    }

    /**
     * Cleans up all data related to a comic.
     * REVISION Delete Feature: Destructive cleanup for all relational data.
     */
    @Transaction
    suspend fun deleteComicAndRelatedData(
        relativePath: String,
        deleteProgress: suspend (String) -> Unit,
        deleteBookmarks: suspend (String) -> Unit
    ) {
        deleteByPath(relativePath)
        deleteProgress(relativePath)
        deleteBookmarks(relativePath)
    }
}
