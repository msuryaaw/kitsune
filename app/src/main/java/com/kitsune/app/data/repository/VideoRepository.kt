package com.kitsune.app.data.repository

import com.kitsune.app.database.dao.VideoDao
import com.kitsune.app.database.entity.VideoEntity
import com.kitsune.app.domain.model.Video
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Repository untuk mengelola fondasi data video.
 * Hanya menangani operasi database sesuai spesifikasi Phase 7.1.1.
 */
class VideoRepository(
    private val videoDao: VideoDao
) {
    /**
     * Aliran data seluruh video dari database.
     */
    val allVideos: Flow<List<Video>> = videoDao.getAllVideos().map { entities ->
        entities.map { it.toDomain() }
    }

    /**
     * Mendapatkan metadata video berdasarkan jalur relatif.
     */
    suspend fun getVideoByPath(path: String): Video? {
        return videoDao.getVideoByPath(path)?.toDomain()
    }

    private fun VideoEntity.toDomain() = Video(
        title = title,
        relativePath = relativePath,
        coverUri = coverUri,
        episodeCount = episodeCount,
        lastModified = lastModified
    )
}
