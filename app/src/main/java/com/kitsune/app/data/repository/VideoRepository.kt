package com.kitsune.app.data.repository

import android.net.Uri
import com.kitsune.app.database.dao.VideoDao
import com.kitsune.app.database.entity.VideoEntity
import com.kitsune.app.domain.model.Episode
import com.kitsune.app.domain.model.Video
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Repository untuk mengelola data video.
 * Menjadi single source of truth untuk UI (ViewModel) dalam mengakses data video.
 */
class VideoRepository(
    private val videoDao: VideoDao,
    private val scannerRepository: ScannerRepository
) {
    /**
     * Aliran data seluruh video dari database dengan optimasi emisi.
     */
    val allVideos: Flow<List<Video>> = videoDao.getAllVideos()
        .map { entities ->
            entities.map { it.toDomain() }
        }
        .distinctUntilChanged()

    /**
     * Mendapatkan metadata video berdasarkan jalur relatif.
     */
    suspend fun getVideo(path: String): Video? {
        return videoDao.getVideoByPath(path)?.toDomain()
    }

    /**
     * Bridge ke ScannerRepository untuk mendapatkan daftar episode dari filesystem.
     */
    suspend fun getEpisodes(rootUri: Uri, relativePath: String): List<Episode> {
        return scannerRepository.getEpisodes(rootUri, relativePath)
    }

    private fun VideoEntity.toDomain() = Video(
        title = title,
        relativePath = relativePath,
        coverUri = coverUri,
        episodeCount = episodeCount,
        lastModified = lastModified
    )
}
