package com.kitsune.app.database.dao

import androidx.room.*
import com.kitsune.app.database.entity.VideoEntity
import com.kitsune.app.database.entity.VideoProgressEntity
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

    // --- Video Progress Operations ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProgress(progress: VideoProgressEntity)

    @Query("SELECT * FROM video_progress WHERE videoRelativePath = :videoPath AND episodeRelativePath = :episodePath LIMIT 1")
    fun getProgress(videoPath: String, episodePath: String): Flow<VideoProgressEntity?>

    @Query("SELECT * FROM video_progress WHERE episodeRelativePath = :episodePath LIMIT 1")
    suspend fun getProgressByEpisodeSync(episodePath: String): VideoProgressEntity?

    @Query("DELETE FROM video_progress WHERE videoRelativePath = :videoPath")
    suspend fun deleteProgress(videoPath: String)

    @Query("DELETE FROM video_progress WHERE videoRelativePath IN (:videoPaths)")
    suspend fun deleteProgressList(videoPaths: List<String>)

    @Query("DELETE FROM video_progress WHERE episodeRelativePath = :episodePath")
    suspend fun deleteEpisodeProgress(episodePath: String)

    @Query("DELETE FROM video_progress WHERE episodeRelativePath IN (:episodePaths)")
    suspend fun deleteEpisodeProgressList(episodePaths: List<String>)

    @Query("SELECT * FROM video_progress ORDER BY lastWatchedAt DESC LIMIT 1")
    fun getLatestProgress(): Flow<VideoProgressEntity?>

    @Query("SELECT * FROM video_progress")
    fun getAllProgress(): Flow<List<VideoProgressEntity>>

    @Query("SELECT * FROM video_progress")
    suspend fun getAllProgressSync(): List<VideoProgressEntity>

    /**
     * Membersihkan progres yang tidak lagi memiliki VideoEntity terkait (Orphan).
     */
    @Query("DELETE FROM video_progress WHERE videoRelativePath NOT IN (SELECT relativePath FROM videos)")
    suspend fun deleteOrphanProgress()
}
