package com.example.kitsune.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.kitsune.data.entity.BookmarkEntity
import com.example.kitsune.data.entity.ComicBookmarkCrossRef
import com.example.kitsune.data.entity.ComicEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for [BookmarkEntity] and [ComicBookmarkCrossRef].
 * Manages user-defined named bookmark collections and comic memberships.
 */
@Dao
interface BookmarkDao {

    // ── Bookmark Collections ──

    @Query("SELECT * FROM bookmarks ORDER BY createdAt ASC")
    fun observeAllCollections(): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE id = :id")
    suspend fun getCollectionById(id: Long): BookmarkEntity?

    @Query("SELECT * FROM bookmarks WHERE name = :name")
    suspend fun getCollectionByName(name: String): BookmarkEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCollection(collection: BookmarkEntity): Long

    @Update
    suspend fun updateCollection(collection: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteCollectionById(id: Long)

    // ── CrossRef & Memberships ──

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addComicToCollection(crossRef: ComicBookmarkCrossRef)

    @Query("DELETE FROM comic_bookmark_cross_ref WHERE bookmarkId = :bookmarkId AND comicId = :comicId")
    suspend fun removeComicFromCollection(bookmarkId: Long, comicId: Long)

    @Query("""
        SELECT c.* FROM comics c
        INNER JOIN comic_bookmark_cross_ref ref ON c.id = ref.comicId
        WHERE ref.bookmarkId = :bookmarkId AND c.rootUriString = :rootUri
        ORDER BY c.title COLLATE NOCASE ASC
    """)
    fun observeComicsInCollection(bookmarkId: Long, rootUri: String): Flow<List<ComicEntity>>

    @Query("""
        SELECT b.* FROM bookmarks b
        INNER JOIN comic_bookmark_cross_ref ref ON b.id = ref.bookmarkId
        WHERE ref.comicId = :comicId
        ORDER BY b.name COLLATE NOCASE ASC
    """)
    fun observeCollectionsForComic(comicId: Long): Flow<List<BookmarkEntity>>

    @Query("""
        SELECT b.* FROM bookmarks b
        INNER JOIN comic_bookmark_cross_ref ref ON b.id = ref.bookmarkId
        WHERE ref.comicId = :comicId
    """)
    suspend fun getCollectionsForComic(comicId: Long): List<BookmarkEntity>

    @Query("""
        SELECT COUNT(*) FROM comic_bookmark_cross_ref
        WHERE comicId = :comicId
    """)
    fun observeBookmarkCountForComic(comicId: Long): Flow<Int>

    @Query("""
        SELECT COUNT(*) FROM comic_bookmark_cross_ref
        WHERE comicId = :comicId
    """)
    suspend fun getBookmarkCountForComic(comicId: Long): Int

    @Query("""
        SELECT c.* FROM comics c
        WHERE c.rootUriString = :rootUri AND c.id IN (
            SELECT DISTINCT comicId FROM comic_bookmark_cross_ref
        )
        ORDER BY c.title COLLATE NOCASE ASC
    """)
    fun observeAllBookmarkedComicsByRoot(rootUri: String): Flow<List<ComicEntity>>
}
