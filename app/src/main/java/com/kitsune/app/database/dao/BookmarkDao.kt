package com.kitsune.app.database.dao

import androidx.room.*
import com.kitsune.app.database.entity.BookmarkComicEntity
import com.kitsune.app.database.entity.BookmarkEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    fun getAllBookmarks(): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE id = :id")
    suspend fun getBookmarkById(id: Long): BookmarkEntity?

    @Query("SELECT COUNT(*) FROM bookmark_comics WHERE bookmarkId = :bookmarkId")
    fun getComicCountInBookmark(bookmarkId: Long): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: BookmarkEntity): Long

    @Query("UPDATE bookmarks SET name = :newName WHERE id = :bookmarkId")
    suspend fun renameBookmark(bookmarkId: Long, newName: String)

    @Query("DELETE FROM bookmarks WHERE id = :bookmarkId")
    suspend fun deleteBookmark(bookmarkId: Long)

    @Query("DELETE FROM bookmarks WHERE id IN (:bookmarkIds)")
    suspend fun deleteBookmarks(bookmarkIds: List<Long>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addComicToBookmark(bookmarkComic: BookmarkComicEntity)

    /**
     * Menambahkan banyak komik ke banyak bookmark sekaligus.
     * Menggunakan IGNORE untuk mencegah duplikasi.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addComicsToBookmarks(entities: List<BookmarkComicEntity>)

    @Query("DELETE FROM bookmark_comics WHERE bookmarkId = :bookmarkId AND comicRelativePath = :comicPath")
    suspend fun removeComicFromBookmark(bookmarkId: Long, comicPath: String)

    @Query("DELETE FROM bookmark_comics WHERE bookmarkId = :bookmarkId AND comicRelativePath IN (:comicPaths)")
    suspend fun removeComicsFromBookmark(bookmarkId: Long, comicPaths: List<String>)

    @Query("SELECT comicRelativePath FROM bookmark_comics WHERE bookmarkId = :bookmarkId ORDER BY createdAt DESC")
    fun getComicsInBookmark(bookmarkId: Long): Flow<List<String>>

    @Query("SELECT DISTINCT comicRelativePath FROM bookmark_comics ORDER BY createdAt DESC")
    fun getAllBookmarkedComics(): Flow<List<String>>

    @Query("SELECT EXISTS(SELECT 1 FROM bookmark_comics WHERE bookmarkId = :bookmarkId AND comicRelativePath = :comicPath)")
    fun isComicInBookmark(bookmarkId: Long, comicPath: String): Flow<Boolean>

    @Query("SELECT bookmarkId FROM bookmark_comics WHERE comicRelativePath = :comicPath")
    fun getBookmarkIdsForComic(comicPath: String): Flow<List<Long>>

    /**
     * Cleans up all bookmark relations for a specific comic.
     * REVISION Delete Feature: Destructive cleanup for all relational data.
     */
    @Query("DELETE FROM bookmark_comics WHERE comicRelativePath = :comicPath")
    suspend fun removeComicFromAllBookmarks(comicPath: String)
}
