package com.kitsune.app.data.repository

import com.kitsune.app.domain.model.MediaType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Repository Facade untuk mengelola koleksi media secara terpadu.
 * Menjadi entry point tunggal untuk operasi Bookmark dan Playlist.
 * Membawahi BookmarkRepository dan PlaylistRepository untuk menyatukan akses data.
 * 
 * REVISION 7.8.9: Unified Collection Repository Foundation.
 * REVISION 7.8.10: Added membership query for Detail Screen integration.
 * REVISION 7.8.11: Added path set observation for Library integration.
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
     * Dapat digunakan baik untuk Komik maupun Video berkat Relative Path Identification.
     */
    fun isBookmarked(mediaPath: String, bookmarkId: Long): Flow<Boolean> =
        bookmarkRepository.isComicInBookmark(bookmarkId, mediaPath)

    /**
     * Mengamati status playlist untuk media tertentu di dalam kategori tertentu.
     */
    fun isInPlaylist(mediaPath: String, playlistId: Long): Flow<Boolean> =
        playlistRepository.isComicInPlaylist(playlistId, mediaPath)

    /**
     * Mendapatkan daftar ID bookmark yang berisi media tertentu.
     */
    fun getBookmarkIdsForMedia(mediaPath: String): Flow<List<Long>> =
        bookmarkRepository.getBookmarkIdsForComic(mediaPath)

    /**
     * Mengamati seluruh jalur media yang ter-bookmark untuk tipe media tertentu.
     * Mengembalikan Set untuk lookup O(1) di ViewModel.
     */
    fun getBookmarkedPaths(mediaType: MediaType): Flow<Set<String>> =
        bookmarkRepository.getBookmarkedPaths(mediaType).map { it.toSet() }

    /**
     * Mengamati seluruh jalur media yang ada di playlist manapun untuk tipe media tertentu.
     */
    fun getPlaylistPaths(mediaType: MediaType): Flow<Set<String>> =
        playlistRepository.getPlaylistPaths(mediaType).map { it.toSet() }

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
     * Menambahkan media (Komik/Video) ke dalam kategori playlist.
     */
    suspend fun addPlaylist(mediaPath: String, playlistId: Long) {
        playlistRepository.addMediaToPlaylists(listOf(playlistId), listOf(mediaPath))
    }

    /**
     * Menghapus media (Komik/Video) dari kategori playlist.
     */
    suspend fun removePlaylist(mediaPath: String, playlistId: Long) {
        playlistRepository.removeComicFromPlaylist(playlistId, mediaPath)
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
     */
    suspend fun togglePlaylist(mediaPath: String, playlistId: Long) {
        val currentlyInPlaylist = isInPlaylist(mediaPath, playlistId).first()
        if (currentlyInPlaylist) {
            removePlaylist(mediaPath, playlistId)
        } else {
            addPlaylist(mediaPath, playlistId)
        }
    }
}
