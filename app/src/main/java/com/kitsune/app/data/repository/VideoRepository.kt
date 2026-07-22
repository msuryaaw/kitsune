package com.kitsune.app.data.repository

import android.net.Uri
import android.util.Log
import android.util.LruCache
import com.kitsune.app.core.StorageHelper
import com.kitsune.app.database.dao.VideoDao
import com.kitsune.app.database.entity.VideoEntity
import com.kitsune.app.database.entity.VideoProgressEntity
import com.kitsune.app.domain.model.Episode
import com.kitsune.app.domain.model.LastWatchedVideo
import com.kitsune.app.domain.model.Video
import com.kitsune.app.domain.model.VideoStatistics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext

/**
 * Repository untuk mengelola data video.
 * REVISION 7.7.5: Implementasi History Cleanup & Database Maintenance.
 * REVISION 8.3.4: Added Clear Watching History support.
 * REVISION 8.3.5: Added Video Statistics support.
 */
class VideoRepository(
    private val videoDao: VideoDao,
    private val scannerRepository: ScannerRepository,
    private val storageHelper: StorageHelper
) {
    // CACHE FOUNDATION (Phase 7.6.1)
    // Menyimpan daftar episode untuk 10 video terakhir di memori.
    private val episodeCache = LruCache<String, List<Episode>>(10)

    init {
        // Otomatis bersihkan cache saat pemindaian filesystem dimulai
        scannerRepository.onScanStarted = {
            clearCache()
        }

        // REVISION 7.7.5: Jalankan pembersihan riwayat otomatis setelah scanning selesai
        scannerRepository.onScanFinished = { rootUri ->
            cleanupInvalidHistory(rootUri)
        }
    }

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
     * Mendapatkan aliran data metadata video tunggal.
     */
    fun getVideoFlow(path: String): Flow<Video?> {
        return videoDao.getAllVideos().map { list ->
            list.find { it.relativePath == path }?.toDomain()
        }.distinctUntilChanged()
    }

    /**
     * Mengambil daftar episode dengan dukungan cache (Phase 7.6.1).
     * Mengecek memori terlebih dahulu sebelum melakukan scan filesystem.
     */
    suspend fun getEpisodes(rootUri: Uri, relativePath: String): List<Episode> {
        // 1. Cek cache memori
        episodeCache.get(relativePath)?.let {
            return it
        }

        // 2. Jika miss, lakukan scan filesystem
        val episodes = scannerRepository.getEpisodes(rootUri, relativePath)

        // 3. Simpan ke cache untuk akses berikutnya
        if (episodes.isNotEmpty()) {
            episodeCache.put(relativePath, episodes)
        }

        return episodes
    }

    /**
     * Resolusi Relative Path menjadi URI SAF untuk pemutaran video.
     */
    fun getEpisodeUri(rootUri: Uri, relativePath: String): Uri? {
        return storageHelper.findFileByRelativePath(rootUri, relativePath)?.uri
    }

    /**
     * Memicu pemindaian library dan membersihkan cache (Phase 7.6.1).
     * Invalidation krusial untuk mencegah data stale setelah perubahan filesystem.
     */
    suspend fun refreshLibrary(rootUri: Uri) {
        clearCache()
        scannerRepository.performIncrementalScan(rootUri)
        // Note: cleanupInvalidHistory akan dipicu secara otomatis oleh onScanFinished listener
    }

    /**
     * Membersihkan seluruh cache episode.
     */
    fun clearCache() {
        episodeCache.evictAll()
    }

    // --- Video Progress Operations ---

    /**
     * Menyimpan progres menonton video ke database.
     */
    suspend fun saveVideoProgress(
        videoPath: String,
        episodePath: String,
        positionMs: Long,
        durationMs: Long
    ) {
        val progress = VideoProgressEntity(
            videoRelativePath = videoPath,
            episodeRelativePath = episodePath,
            lastPositionMs = positionMs,
            durationMs = durationMs,
            lastWatchedAt = System.currentTimeMillis()
        )
        videoDao.upsertProgress(progress)
    }

    /**
     * Mendapatkan progres menonton untuk episode tertentu.
     */
    suspend fun getVideoProgressSync(episodePath: String): VideoProgressEntity? {
        return videoDao.getProgressByEpisodeSync(episodePath)
    }

    /**
     * Mendapatkan aliran data progres menonton terbaru secara global.
     */
    fun getLatestProgress(): Flow<VideoProgressEntity?> {
        return videoDao.getLatestProgress()
    }

    /**
     * Menghapus seluruh riwayat menonton untuk video tertentu.
     */
    suspend fun deleteVideoProgress(videoPath: String) {
        videoDao.deleteProgress(videoPath)
    }

    /**
     * Menghapus progres menonton untuk episode tertentu (Phase 7.7.2).
     */
    suspend fun deleteEpisodeProgress(episodePath: String) {
        videoDao.deleteEpisodeProgress(episodePath)
    }

    /**
     * Menghapus seluruh riwayat menonton video (Phase 8.3.4).
     */
    suspend fun clearWatchingHistory() {
        videoDao.deleteAllProgress()
    }

    // --- Statistics (Phase 8.3.5) ---

    /**
     * Mengamati statistik penggunaan Video Engine secara terpadu.
     */
    fun getVideoStatistics(): Flow<VideoStatistics> {
        return combine(
            videoDao.getTotalVideoCount(),
            videoDao.getWatchedVideoCount(),
            videoDao.getCompletedVideoCount(),
            videoDao.getTotalWatchTimeMs()
        ) { total, watched, completed, watchTime ->
            VideoStatistics(
                totalVideos = total,
                watchedVideos = watched,
                completedVideos = completed,
                totalWatchTimeMs = watchTime ?: 0L
            )
        }.distinctUntilChanged()
    }

    // --- Continue Watching Foundation (Phase 7.7.3.1) ---

    /**
     * Aliran data video terakhir yang ditonton untuk fitur Continue Watching di Home.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun getLatestWatchedVideo(): Flow<LastWatchedVideo?> {
        return videoDao.getLatestProgress().flatMapLatest { progress ->
            if (progress == null) {
                flowOf(null)
            } else {
                val videoEntity = videoDao.getVideoByPath(progress.videoRelativePath)
                if (videoEntity != null) {
                    val percentage = if (progress.durationMs > 0) {
                        progress.lastPositionMs.toFloat() / progress.durationMs.toFloat()
                    } else 0f

                    flowOf(
                        LastWatchedVideo(
                            video = videoEntity.toDomain(),
                            episodeRelativePath = progress.episodeRelativePath,
                            progressPositionMs = progress.lastPositionMs,
                            durationMs = progress.durationMs,
                            watchedPercentage = percentage,
                            lastWatchedAt = progress.lastWatchedAt
                        )
                    )
                } else {
                    flowOf(null)
                }
            }
        }
    }

    /**
     * Mendapatkan Map seluruh progres untuk indikator di Library.
     */
    fun getAllProgressMap(): Flow<Map<String, VideoProgressEntity>> {
        return videoDao.getAllProgress().map { list ->
            list.associateBy { it.episodeRelativePath }
        }.distinctUntilChanged()
    }

    // --- History Maintenance (Phase 7.7.5) ---

    /**
     * Melakukan pembersihan data progres yang sudah tidak valid berdasarkan kondisi filesystem.
     * Strategi: Hapus orphan di DB, lalu validasi file fisik untuk entri yang tersisa.
     */
    suspend fun cleanupInvalidHistory(rootUri: Uri) = withContext(Dispatchers.IO) {
        try {
            // 1. Bersihkan Orphan Progress (yang tidak punya VideoEntity di DB)
            // Ini menangani kasus di mana folder video dihapus dan scanner sudah menghapus VideoEntity-nya.
            videoDao.deleteOrphanProgress()

            // 2. Ambil semua progres yang tersisa untuk validasi file fisik
            // Ini menangani kasus di mana file video individu dihapus tetapi folder serinya tetap ada.
            val allProgress = videoDao.getAllProgressSync()
            if (allProgress.isEmpty()) return@withContext

            val invalidEpisodePaths = mutableListOf<String>()
            
            // OPTIMIZATION: Cache pengecekan folder video untuk menghindari I/O berlebih pada episode dalam folder yang sama
            val folderValidityCache = mutableMapOf<String, Boolean>()

            allProgress.forEach { progress ->
                // A. Cek Validitas Folder Video
                val isFolderValid = folderValidityCache.getOrPut(progress.videoRelativePath) {
                    storageHelper.findFileByRelativePath(rootUri, progress.videoRelativePath)?.let { 
                        it.exists() && it.isDirectory 
                    } ?: false
                }

                if (!isFolderValid) {
                    invalidEpisodePaths.add(progress.episodeRelativePath)
                } else {
                    // B. Cek Validitas File Episode
                    val file = storageHelper.findFileByRelativePath(rootUri, progress.episodeRelativePath)
                    if (file == null || !file.exists() || !file.isFile) {
                        invalidEpisodePaths.add(progress.episodeRelativePath)
                    }
                }
            }

            if (invalidEpisodePaths.isNotEmpty()) {
                videoDao.deleteEpisodeProgressList(invalidEpisodePaths)
                Log.d("KitsuneDB", "Removed invalid history: ${invalidEpisodePaths.size} entries")
            }

        } catch (e: Exception) {
            Log.e("KitsuneDB", "Cleanup failed: ${e.message}")
        }
    }

    private fun VideoEntity.toDomain() = Video(
        title = title,
        relativePath = relativePath,
        coverUri = coverUri,
        episodeCount = episodeCount,
        lastModified = lastModified
    )
}
