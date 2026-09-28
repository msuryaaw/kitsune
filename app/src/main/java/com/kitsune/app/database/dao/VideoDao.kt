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

    /**
     * Updates the search tags index for a specific video.
     * REVISION 11.1.2: Added partial update for search indexing.
     */
    @Query("UPDATE videos SET searchTags = :tags WHERE relativePath = :path")
    suspend fun updateSearchTags(path: String, tags: String?)

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

    @Query("DELETE FROM video_progress")
    suspend fun deleteAllProgress()

    @Query("SELECT * FROM video_progress ORDER BY lastWatchedAt DESC LIMIT 1")
    fun getLatestProgress(): Flow<VideoProgressEntity?>

    @Query("SELECT * FROM video_progress")
    fun getAllProgress(): Flow<List<VideoProgressEntity>>

    @Query("SELECT * FROM video_progress")
    suspend fun getAllProgressSync(): List<VideoProgressEntity>

    /**
     * Mendapatkan seluruh riwayat menonton yang dikelompokkan per video/judul.
     * Mengambil entri terakhir (episode terbaru) untuk setiap judul.
     */
    @Query("""
        SELECT * FROM video_progress p1 
        WHERE lastWatchedAt = (SELECT MAX(lastWatchedAt) FROM video_progress p2 WHERE p2.videoRelativePath = p1.videoRelativePath) 
        ORDER BY lastWatchedAt DESC
    """)
    fun getAllWatchHistory(): Flow<List<VideoProgressEntity>>

    /**
     * Mendapatkan seluruh riwayat menonton beserta metadata videonya dalam 1 query atomik (JOIN).
     * REVISION HIGH-02 Fix: Mengeliminasi N+1 query problem pada Watch History.
     */
    @Query("""
        SELECT 
            p1.id AS id,
            p1.videoRelativePath AS videoRelativePath,
            p1.episodeRelativePath AS episodeRelativePath,
            p1.lastPositionMs AS lastPositionMs,
            p1.durationMs AS durationMs,
            p1.lastWatchedAt AS lastWatchedAt,
            videos.title AS video_title,
            videos.relativePath AS video_relativePath,
            videos.coverUri AS video_coverUri,
            videos.episodeCount AS video_episodeCount,
            videos.lastModified AS video_lastModified,
            videos.searchTags AS video_searchTags
        FROM video_progress p1
        INNER JOIN videos ON p1.videoRelativePath = videos.relativePath
        WHERE p1.lastWatchedAt = (SELECT MAX(p2.lastWatchedAt) FROM video_progress p2 WHERE p2.videoRelativePath = p1.videoRelativePath)
        ORDER BY p1.lastWatchedAt DESC
    """)
    fun getFullWatchHistoryWithVideo(): Flow<List<WatchHistoryWithVideo>>

    // --- Statistics Queries (Phase 8.3.5) ---

    @Query("SELECT COUNT(*) FROM videos")
    fun getTotalVideoCount(): Flow<Int>

    @Query("SELECT COUNT(DISTINCT videoRelativePath) FROM video_progress")
    fun getWatchedVideoCount(): Flow<Int>

    @Query("SELECT COUNT(DISTINCT videoRelativePath) FROM video_progress WHERE lastPositionMs >= (durationMs * 0.95)")
    fun getCompletedVideoCount(): Flow<Int>

    @Query("SELECT SUM(lastPositionMs) FROM video_progress")
    fun getTotalWatchTimeMs(): Flow<Long?>

    /**
     * Membersihkan progres yang tidak lagi memiliki VideoEntity terkait (Orphan).
     */
    @Query("DELETE FROM video_progress WHERE videoRelativePath NOT IN (SELECT relativePath FROM videos)")
    suspend fun deleteOrphanProgress()
}

data class WatchHistoryWithVideo(
    @Embedded val progress: VideoProgressEntity,
    @Embedded(prefix = "video_") val video: VideoEntity
)
