package com.example.kitsune.data

import com.example.kitsune.data.entity.ChapterEntity
import com.example.kitsune.data.entity.ComicEntity
import com.example.kitsune.data.entity.ReadingProgressEntity
import com.example.kitsune.scanner.LibraryScanResult
import kotlinx.coroutines.flow.Flow

/**
 * Repository that orchestrates library scanning, database synchronization, and
 * exposes reactive data to the UI layer.
 *
 * The UI must not access DAOs or [com.example.kitsune.storage.StorageGateway] directly.
 *
 * Design constraints:
 * - Filesystem is the source of truth (K-03); Room is the index only.
 * - Comic identity is determined by `(rootUriString, relativePath)` — isolated per root (K-04).
 * - Cover comes exclusively from the first valid image in `Chapter 1.cbz` (K-07); no fallback.
 * - A failed or partial scan must NOT trigger mass deletion of existing database records.
 */
interface LibraryRepository {

    /**
     * Scans the library under [rootUri] and synchronises the results with the local database.
     *
     * On [LibraryScanResult.Success], upserts comic and chapter metadata while preserving
     * user state (bookmarks, reading progress, last-read timestamp).
     *
     * On any error result ([LibraryScanResult.Error], [LibraryScanResult.PermissionDenied],
     * [LibraryScanResult.RootNotConfigured], [LibraryScanResult.ComicsDirectoryNotFound],
     * [LibraryScanResult.EmptyComicsDirectory]) the existing database records are left intact;
     * no mass deletion is performed.
     *
     * @param rootUri URI string of the storage root to scan.
     * @return The raw [LibraryScanResult] produced by the scanner.
     */
    suspend fun syncLibrary(rootUri: String): LibraryScanResult

    /**
     * Observes all comics indexed for [rootUri], ordered by title ascending (case-insensitive).
     */
    fun observeComicsByRoot(rootUri: String): Flow<List<ComicEntity>>

    /**
     * Observes only bookmarked comics for [rootUri], ordered by title ascending.
     */
    fun observeBookmarkedComicsByRoot(rootUri: String): Flow<List<ComicEntity>>

    /**
     * Observes recently read comics for [rootUri], ordered by most-recently-read first.
     * Only comics with a non-null [ComicEntity.lastReadAt] are included.
     */
    fun observeRecentlyReadByRoot(rootUri: String): Flow<List<ComicEntity>>

    /**
     * Observes all chapters for [comicId], ordered by chapter number ascending.
     */
    fun observeChaptersByComicId(comicId: Long): Flow<List<ChapterEntity>>

    /**
     * Returns the reading progress for [comicId], or null if none is recorded.
     */
    suspend fun getReadingProgress(comicId: Long): ReadingProgressEntity?

    /**
     * Persists or replaces reading progress for a comic.
     * Safe to call repeatedly; does not trigger CASCADE deletes.
     */
    suspend fun saveReadingProgress(progress: ReadingProgressEntity)

    /**
     * Toggles the bookmark state for [comicId].
     */
    suspend fun setBookmarked(comicId: Long, bookmarked: Boolean)

    /**
     * Extracts the single cover image byte payload from Chapter 1.cbz for [comicId].
     */
    suspend fun fetchCover(comicId: Long): CoverResult

    /**
     * Retrieves or extracts the cached cover image file for [comicId].
     */
    suspend fun getCoverFile(comicId: Long): CoverCacheResult

    /**
     * Releases an active consumer reference for an ephemeral cover file when reading completes.
     */
    suspend fun releaseCoverFile(file: java.io.File)

    /**
     * Observes the latest scan result produced by [syncLibrary].
     */
    fun observeLastScanResult(): Flow<LibraryScanResult?>
}

