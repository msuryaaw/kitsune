package com.kitsune.app.data.repository

import com.kitsune.app.database.dao.BookmarkDao
import com.kitsune.app.database.entity.BookmarkComicEntity
import com.kitsune.app.database.entity.BookmarkEntity
import com.kitsune.app.domain.model.CollectionType
import com.kitsune.app.domain.model.Comic
import com.kitsune.app.domain.model.MediaCollectionItem
import com.kitsune.app.domain.model.MediaType
import com.kitsune.app.domain.model.Video
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

/**
 * Repository untuk mengelola Bookmark.
 * REVISION 7.8.9: Penambahan API Video secara additive untuk Unified Collection Foundation.
 */
class BookmarkRepository(private val bookmarkDao: BookmarkDao) {

    /**
     * Mendapatkan semua bookmark beserta jumlah komiknya.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun getAllBookmarksWithCount(): Flow<List<BookmarkWithCount>> {
        return bookmarkDao.getAllBookmarks().flatMapLatest { bookmarks ->
            if (bookmarks.isEmpty()) {
                flowOf(emptyList())
            } else {
                val flows = bookmarks.map { bookmark ->
                    bookmarkDao.getComicCountInBookmark(bookmark.id).map { count ->
                        BookmarkWithCount(bookmark, count)
                    }
                }
                combine(flows) { it.toList() }
            }
        }
    }

    suspend fun getBookmarkById(id: Long): BookmarkEntity? {
        return bookmarkDao.getBookmarkById(id)
    }

    fun getComicsInBookmark(bookmarkId: Long): Flow<List<String>> {
        return bookmarkDao.getComicsInBookmark(bookmarkId)
    }

    /**
     * Mendapatkan seluruh jalur relatif komik yang ada di kategori bookmark manapun.
     * PERINGATAN: Mengembalikan seluruh isi tabel bookmark_comics.
     */
    fun getAllBookmarkedComics(): Flow<List<String>> {
        return bookmarkDao.getAllBookmarkedComics()
    }

    /**
     * ADDITIVE: Mendapatkan jalur relatif berdasarkan tipe media menggunakan filter prefix.
     */
    fun getBookmarkedPaths(mediaType: MediaType): Flow<List<String>> {
        val prefix = if (mediaType == MediaType.COMIC) "Comics/" else "Videos/"
        return bookmarkDao.getAllBookmarkedComics().map { paths ->
            paths.filter { it.startsWith(prefix) }
        }
    }

    suspend fun createBookmark(name: String): Long {
        return bookmarkDao.insertBookmark(BookmarkEntity(name = name))
    }

    suspend fun renameBookmark(id: Long, newName: String) {
        bookmarkDao.renameBookmark(id, newName)
    }

    suspend fun deleteBookmark(id: Long) {
        bookmarkDao.deleteBookmark(id)
    }

    /**
     * Menghapus banyak bookmark sekaligus.
     */
    suspend fun deleteBookmarks(ids: List<Long>) {
        bookmarkDao.deleteBookmarks(ids)
    }

    suspend fun addComicToBookmark(bookmarkId: Long, comicPath: String) {
        bookmarkDao.addComicToBookmark(
            BookmarkComicEntity(bookmarkId = bookmarkId, comicRelativePath = comicPath)
        )
    }

    /**
     * ADDITIVE: Menambahkan video ke bookmark.
     */
    suspend fun addVideoToBookmark(bookmarkId: Long, videoPath: String) {
        addComicToBookmark(bookmarkId, videoPath)
    }

    /**
     * REVISION 5.2: Menambahkan banyak komik ke banyak bookmark sekaligus secara batch.
     */
    suspend fun addComicsToBookmarks(bookmarkIds: List<Long>, comicPaths: List<String>) {
        val entities = bookmarkIds.flatMap { bookmarkId ->
            comicPaths.map { path ->
                BookmarkComicEntity(bookmarkId = bookmarkId, comicRelativePath = path)
            }
        }
        if (entities.isNotEmpty()) {
            bookmarkDao.addComicsToBookmarks(entities)
        }
    }

    /**
     * ADDITIVE: Batch add untuk media apapun (Video/Comic).
     */
    suspend fun addMediaToBookmarks(bookmarkIds: List<Long>, mediaPaths: List<String>) {
        addComicsToBookmarks(bookmarkIds, mediaPaths)
    }

    suspend fun removeComicFromBookmark(bookmarkId: Long, comicPath: String) {
        bookmarkDao.removeComicFromBookmark(bookmarkId, comicPath)
    }

    /**
     * ADDITIVE: Menghapus video dari bookmark.
     */
    suspend fun removeVideoFromBookmark(bookmarkId: Long, videoPath: String) {
        removeComicFromBookmark(bookmarkId, videoPath)
    }

    /**
     * Menghapus banyak komik dari bookmark tertentu sekaligus.
     */
    suspend fun removeComicsFromBookmark(bookmarkId: Long, comicPaths: List<String>) {
        bookmarkDao.removeComicsFromBookmark(bookmarkId, comicPaths)
    }

    fun isComicInBookmark(bookmarkId: Long, comicPath: String): Flow<Boolean> {
        return bookmarkDao.isComicInBookmark(bookmarkId, comicPath)
    }

    /**
     * Mendapatkan daftar ID bookmark yang berisi komik tertentu.
     */
    fun getBookmarkIdsForComic(comicPath: String): Flow<List<Long>> {
        return bookmarkDao.getBookmarkIdsForComic(comicPath)
    }

    /**
     * GENERIC API: Memetakan Comic menjadi MediaCollectionItem untuk abstraksi Unified Collections.
     */
    fun mapToMediaCollectionItem(comic: Comic): MediaCollectionItem {
        return MediaCollectionItem(
            relativePath = comic.relativePath,
            title = comic.title,
            thumbnailUri = comic.coverUri,
            mediaType = MediaType.COMIC,
            collectionType = CollectionType.BOOKMARK
        )
    }

    /**
     * ADDITIVE: Memetakan Video menjadi MediaCollectionItem.
     */
    fun mapToMediaCollectionItem(video: Video): MediaCollectionItem {
        return MediaCollectionItem(
            relativePath = video.relativePath,
            title = video.title,
            thumbnailUri = video.coverUri,
            mediaType = MediaType.VIDEO,
            collectionType = CollectionType.BOOKMARK
        )
    }
}

data class BookmarkWithCount(
    val bookmark: BookmarkEntity,
    val count: Int
)
