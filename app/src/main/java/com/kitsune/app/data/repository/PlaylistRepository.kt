package com.kitsune.app.data.repository

import com.kitsune.app.database.dao.PlaylistDao
import com.kitsune.app.database.entity.PlaylistComicEntity
import com.kitsune.app.database.entity.PlaylistEntity
import com.kitsune.app.domain.model.CollectionType
import com.kitsune.app.domain.model.MediaCollectionItem
import com.kitsune.app.domain.model.MediaType
import com.kitsune.app.domain.model.Video
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

/**
 * Repository untuk mengelola Playlist.
 * REVISION 10.1.2: Restrict Playlist to Video only. 
 * Comics are no longer supported in Playlists.
 */
class PlaylistRepository(private val playlistDao: PlaylistDao) {

    /**
     * Mendapatkan semua playlist beserta jumlah video di dalamnya.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun getAllPlaylistsWithCount(): Flow<List<PlaylistWithCount>> {
        return playlistDao.getAllPlaylists().flatMapLatest { playlists ->
            if (playlists.isEmpty()) {
                flowOf(emptyList())
            } else {
                val flows = playlists.map { playlist ->
                    // REVISION 10.1.2: Filter for videos to ensure consistency.
                    playlistDao.getComicsInPlaylist(playlist.id).map { paths ->
                        val videoCount = paths.count { it.startsWith("Videos/") }
                        PlaylistWithCount(playlist, videoCount)
                    }
                }
                combine(flows) { it.toList() }
            }
        }
    }

    suspend fun getPlaylistById(id: Long): PlaylistEntity? {
        return playlistDao.getPlaylistById(id)
    }

    /**
     * Mendapatkan daftar jalur video dalam playlist.
     * Mengabaikan komik untuk backward compatibility.
     */
    fun getVideosInPlaylist(playlistId: Long): Flow<List<String>> {
        return playlistDao.getComicsInPlaylist(playlistId).map { paths ->
            paths.filter { it.startsWith("Videos/") }
        }
    }

    /**
     * Mendapatkan seluruh jalur relatif media yang ada di kategori playlist manapun.
     * Hanya menyertakan Video.
     */
    fun getAllPlaylistMedia(): Flow<List<String>> {
        return playlistDao.getAllPlaylistComics().map { paths ->
            paths.filter { it.startsWith("Videos/") }
        }
    }

    /**
     * Mendapatkan jalur relatif berdasarkan tipe media.
     * Sekarang hanya mengembalikan data jika mediaType adalah VIDEO.
     */
    fun getPlaylistPaths(mediaType: MediaType): Flow<List<String>> {
        if (mediaType != MediaType.VIDEO) return flowOf(emptyList())
        
        return playlistDao.getAllPlaylistComics().map { paths ->
            paths.filter { it.startsWith("Videos/") }
        }
    }

    suspend fun createPlaylist(name: String): Long {
        return playlistDao.insertPlaylist(PlaylistEntity(name = name))
    }

    suspend fun renamePlaylist(id: Long, newName: String) {
        playlistDao.renamePlaylist(id, newName)
    }

    suspend fun deletePlaylist(id: Long) {
        playlistDao.deletePlaylist(id)
    }

    suspend fun deletePlaylists(ids: List<Long>) {
        playlistDao.deletePlaylists(ids)
    }

    /**
     * Menambahkan video ke playlist.
     * Validasi wajib: Hanya path yang dimulai dengan "Videos/" yang diperbolehkan.
     */
    suspend fun addVideoToPlaylist(playlistId: Long, videoPath: String) {
        if (!videoPath.startsWith("Videos/")) return

        val maxPos = playlistDao.getMaxPosition(playlistId) ?: -1
        playlistDao.addComicToPlaylist(
            PlaylistComicEntity(
                playlistId = playlistId,
                comicRelativePath = videoPath,
                position = maxPos + 1
            )
        )
    }

    /**
     * Batch add untuk video.
     */
    suspend fun addVideosToPlaylists(playlistIds: List<Long>, videoPaths: List<String>) {
        val validPaths = videoPaths.filter { it.startsWith("Videos/") }
        if (validPaths.isEmpty()) return

        val entities = mutableListOf<PlaylistComicEntity>()
        playlistIds.forEach { playlistId ->
            val maxPos = playlistDao.getMaxPosition(playlistId) ?: -1
            var currentPos = maxPos + 1
            validPaths.forEach { path ->
                entities.add(
                    PlaylistComicEntity(
                        playlistId = playlistId,
                        comicRelativePath = path,
                        position = currentPos++
                    )
                )
            }
        }
        if (entities.isNotEmpty()) {
            playlistDao.addComicsToPlaylists(entities)
        }
    }

    suspend fun removeVideoFromPlaylist(playlistId: Long, videoPath: String) {
        playlistDao.removeComicFromPlaylist(playlistId, videoPath)
    }

    suspend fun removeVideosFromPlaylist(playlistId: Long, videoPaths: List<String>) {
        playlistDao.removeComicsFromPlaylist(playlistId, videoPaths)
    }

    fun isVideoInPlaylist(playlistId: Long, videoPath: String): Flow<Boolean> {
        if (!videoPath.startsWith("Videos/")) return flowOf(false)
        return playlistDao.isComicInPlaylist(playlistId, videoPath)
    }

    /**
     * Memetakan Video menjadi MediaCollectionItem.
     */
    fun mapToMediaCollectionItem(video: Video): MediaCollectionItem {
        return MediaCollectionItem(
            relativePath = video.relativePath,
            title = video.title,
            thumbnailUri = video.coverUri,
            mediaType = MediaType.VIDEO,
            collectionType = CollectionType.PLAYLIST
        )
    }
}

data class PlaylistWithCount(
    val playlist: PlaylistEntity,
    val count: Int
)
