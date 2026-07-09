package com.kitsune.app.database.dao

import androidx.room.*
import com.kitsune.app.database.entity.VideoEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoDao {
    @Query("SELECT * FROM videos ORDER BY title ASC")
    fun getAllVideos(): Flow<List<VideoEntity>>

    @Query("SELECT * FROM videos")
    suspend fun getAllVideosSync(): List<VideoEntity>

    @Query("SELECT * FROM videos WHERE relativePath = :path LIMIT 1")
    suspend fun getVideoByPath(path: String): VideoEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVideos(videos: List<VideoEntity>)

    @Query("DELETE FROM videos WHERE relativePath IN (:paths)")
    suspend fun deleteByPaths(paths: List<String>)

    @Transaction
    suspend fun updateLibrary(toInsert: List<VideoEntity>, toDelete: List<String>) {
        if (toDelete.isNotEmpty()) {
            deleteByPaths(toDelete)
        }
        if (toInsert.isNotEmpty()) {
            insertVideos(toInsert)
        }
    }
}
