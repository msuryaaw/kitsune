package com.kitsune.app.data.repository

import com.kitsune.app.domain.model.MediaType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Repository Facade untuk mengelola koleksi media secara terpadu.
 * Menjadi entry point tunggal untuk operasi Bookmark dan Playlist.
 * Membawahi BookmarkRepository dan PlaylistRepository untuk menyatukan akses data.
 * 
 * REVISION 10.1.2: Restrict Playlist access to Video only. 
 * Comics are restricted to Bookmarks only.
 */
class CollectionRepository(
    private val bookmarkRepository: BookmarkRepository,
    private val playlistRepository: PlaylistRepository
) {

    // --- Observer APIs ---

    /**
     * Mengamati seluruh daftar kategori bookmark beserta jumlah item di dalamnya.
     */
    fun observeBookmarks(): Flow<List<BookmarkWithCount>> = 
        bookmarkRepository.getAllBookmarksWithCount()

    /**
     * Mengamati seluruh daftar kategori playlist beserta jumlah item di dalamnya.
     */
    fun observePlaylists(): Flow<List<PlaylistWithCount>> = 
        playlistRepository.getAllPlaylistsWithCount()

    /**
     * Mengamati status bookmark untuk media tertentu di dalam kategori tertentu.
     */
    fun isBookmarked(mediaPath: String, bookmarkId: Long): Flow<Boolean> =
        bookmarkRepository.isComicInBookmark(bookmarkId, mediaPath)

    /**
     * Mengamati status playlist untuk media tertentu di dalam kategori tertentu.
     * REVISION 10.1.2: Returns false if mediaPath is not a Video.
     */
    fun isInPlaylist(mediaPath: String, playlistId: Long): Flow<Boolean> =
        if (mediaPath.startsWith("Videos/")) {
            playlistRepository.isVideoInPlaylist(playlistId, mediaPath)
        } else {
            flowOf(false)
        }

    /**
     * Mendapatkan daftar ID bookmark yang berisi media tertentu.
     */
    fun getBookmarkIdsForMedia(mediaPath: String): Flow<List<Long>> =
        bookmarkRepository.getBookmarkIdsForComic(mediaPath)

    /**
     * Mengamati seluruh jalur media yang ter-bookmark untuk tipe media tertentu.
     */
    fun getBookmarkedPaths(mediaType: MediaType): Flow<Set<String>> =
        bookmarkRepository.getBookmarkedPaths(mediaType).map { it.toSet() }

    /**
     * Mengamati seluruh jalur media yang ada di playlist manapun untuk tipe media tertentu.
     * REVISION 10.1.2: Only returns data for Video.
     */
    fun getPlaylistPaths(mediaType: MediaType): Flow<Set<String>> =
        if (mediaType == MediaType.VIDEO) {
            playlistRepository.getPlaylistPaths(mediaType).map { it.toSet() }
        } else {
            flowOf(emptySet())
        }

    // --- Write APIs ---

    /**
     * Menambahkan media (Komik/Video) ke dalam kategori bookmark.
     */
    suspend fun addBookmark(mediaPath: String, bookmarkId: Long) {
        bookmarkRepository.addMediaToBookmarks(listOf(bookmarkId), listOf(mediaPath))
    }

    /**
     * Menghapus media (Komik/Video) dari kategori bookmark.
     */
    suspend fun removeBookmark(mediaPath: String, bookmarkId: Long) {
        bookmarkRepository.removeComicFromBookmark(bookmarkId, mediaPath)
    }

    /**
     * Menambahkan media (Video) ke dalam kategori playlist.
     * REVISION 10.1.2: Restrict to Video paths.
     */
    suspend fun addPlaylist(mediaPath: String, playlistId: Long) {
        if (mediaPath.startsWith("Videos/")) {
            playlistRepository.addVideoToPlaylist(playlistId, mediaPath)
        }
    }

    /**
     * Menghapus media (Video) dari kategori playlist.
     */
    suspend fun removePlaylist(mediaPath: String, playlistId: Long) {
        if (mediaPath.startsWith("Videos/")) {
            playlistRepository.removeVideoFromPlaylist(playlistId, mediaPath)
        }
    }

    // --- Toggle APIs ---

    /**
     * Mengubah status bookmark media (Add jika belum ada, Remove jika sudah ada).
     */
    suspend fun toggleBookmark(mediaPath: String, bookmarkId: Long) {
        val currentlyBookmarked = isBookmarked(mediaPath, bookmarkId).first()
        if (currentlyBookmarked) {
            removeBookmark(mediaPath, bookmarkId)
        } else {
            addBookmark(mediaPath, bookmarkId)
        }
    }

    /**
     * Mengubah status playlist media (Add jika belum ada, Remove jika sudah ada).
     * REVISION 10.1.2: Only valid for Video paths.
     */
    suspend fun togglePlaylist(mediaPath: String, playlistId: Long) {
        if (!mediaPath.startsWith("Videos/")) return

        val currentlyInPlaylist = isInPlaylist(mediaPath, playlistId).first()
        if (currentlyInPlaylist) {
            removePlaylist(mediaPath, playlistId)
        } else {
            addPlaylist(mediaPath, playlistId)
        }
    }
}
