package com.example.kitsune.data

import com.example.kitsune.data.dao.ChapterDao
import com.example.kitsune.data.dao.ComicDao
import com.example.kitsune.data.dao.ReadingProgressDao
import com.example.kitsune.data.entity.ChapterEntity
import com.example.kitsune.data.entity.ComicEntity
import com.example.kitsune.data.entity.ReadingProgressEntity
import com.example.kitsune.scanner.CbzCatalogResult
import com.example.kitsune.scanner.LibraryScanner
import com.example.kitsune.scanner.LibraryScanResult
import com.example.kitsune.scanner.ScannedChapter
import com.example.kitsune.scanner.ScannedComic
import kotlinx.coroutines.flow.Flow

/**
 * Default implementation of [LibraryRepository].
 *
 * Orchestrates the scan → map → persist pipeline:
 * 1. Delegates scanning to [LibraryScanner].
 * 2. On [LibraryScanResult.Success], maps each [ScannedComic] and its [ScannedChapter]s
 *    to Room entities and upserts them via the DAOs.
 * 3. Exposes reactive [Flow] queries from the DAOs to the UI layer.
 *
 * Key invariants:
 * - [ComicDao.upsertByRootAndPath] is used instead of plain insert to preserve
 *   [ComicEntity.isBookmarked] and [ComicEntity.lastReadAt] across scan cycles.
 * - [ChapterDao.upsertByComicAndPath] preserves chapter IDs to prevent dangling
 *   references held by [ReadingProgressEntity.lastReadChapterId].
 * - Cover is derived exclusively from [ScannedComic.coverEntryPath] (Chapter 1 only, K-07).
 * - Any non-Success scan result leaves existing database records untouched (K-03).
 */
class DefaultLibraryRepository(
    private val libraryScanner: LibraryScanner,
    private val comicDao: ComicDao,
    private val chapterDao: ChapterDao,
    private val readingProgressDao: ReadingProgressDao,
) : LibraryRepository {

    // ── Scan and sync ─────────────────────────────────────────────────────────

    override suspend fun syncLibrary(rootUri: String): LibraryScanResult {
        val scanResult = libraryScanner.scanLibrary()

        if (scanResult is LibraryScanResult.Success) {
            val nowMillis = System.currentTimeMillis()
            syncSuccess(rootUri, scanResult, nowMillis)
        }
        // For all other results (Error, PermissionDenied, RootNotConfigured,
        // ComicsDirectoryNotFound, EmptyComicsDirectory): do nothing — existing
        // database records must not be deleted due to a transient or unrecoverable error.

        return scanResult
    }

    /**
     * Persists each scanned comic and its chapters from a successful scan result.
     * Does NOT delete any comics that were absent from the scan — deletion reconciliation
     * is deferred to Task 3.5 (incremental scan).
     */
    private suspend fun syncSuccess(
        rootUri: String,
        result: LibraryScanResult.Success,
        nowMillis: Long
    ) {
        for (scannedComic in result.comics) {
            val comicEntity = mapScannedComicToEntity(rootUri, scannedComic, nowMillis)
            val comicId = comicDao.upsertByRootAndPath(comicEntity)

            for (scannedChapter in scannedComic.chapters) {
                val chapterEntity = mapScannedChapterToEntity(comicId, scannedChapter)
                chapterDao.upsertByComicAndPath(chapterEntity)
            }
        }
    }

    // ── Mapping ───────────────────────────────────────────────────────────────

    /**
     * Maps a [ScannedComic] to a [ComicEntity].
     *
     * Cover is sourced from [ScannedComic.coverEntryPath] which the scanner already
     * constrains to Chapter 1 only (K-07). No additional fallback logic is applied here.
     */
    private fun mapScannedComicToEntity(
        rootUri: String,
        scannedComic: ScannedComic,
        nowMillis: Long
    ): ComicEntity = ComicEntity(
        rootUriString = rootUri,
        relativePath = scannedComic.relativePath,
        title = scannedComic.title,
        coverEntryPath = scannedComic.coverEntryPath,
        lastScannedAt = nowMillis,
        // isBookmarked and lastReadAt are default-false/null here; upsertByRootAndPath
        // will preserve the existing values if the comic already exists in the database.
    )

    /**
     * Maps a [ScannedChapter] to a [ChapterEntity].
     *
     * [ChapterEntity.isValid] reflects whether the chapter archive was successfully
     * cataloged and contains at least one image page.
     */
    private fun mapScannedChapterToEntity(
        comicId: Long,
        scannedChapter: ScannedChapter
    ): ChapterEntity {
        val discovered = scannedChapter.discoveredChapter
        val catalog = scannedChapter.catalogResult
        val fileSize: Long? = discovered.entry.sizeBytes
        val lastModified: Long? = discovered.entry.lastModifiedMillis

        return ChapterEntity(
            comicId = comicId,
            relativePath = discovered.relativePath,
            chapterNumber = discovered.chapterNumber,
            displayName = discovered.fileName,
            pageCount = scannedChapter.pageCount,
            fileSize = fileSize,
            lastModifiedAt = lastModified,
            isValid = scannedChapter.isValid
        )
    }

    // ── Reactive queries ──────────────────────────────────────────────────────

    override fun observeComicsByRoot(rootUri: String): Flow<List<ComicEntity>> =
        comicDao.observeComicsByRoot(rootUri)

    override fun observeBookmarkedComicsByRoot(rootUri: String): Flow<List<ComicEntity>> =
        comicDao.observeBookmarkedComicsByRoot(rootUri)

    override fun observeRecentlyReadByRoot(rootUri: String): Flow<List<ComicEntity>> =
        comicDao.observeRecentlyReadByRoot(rootUri)

    override fun observeChaptersByComicId(comicId: Long): Flow<List<ChapterEntity>> =
        chapterDao.observeChaptersByComicId(comicId)

    // ── One-shot mutations ────────────────────────────────────────────────────

    override suspend fun getReadingProgress(comicId: Long): ReadingProgressEntity? =
        readingProgressDao.getByComicId(comicId)

    override suspend fun saveReadingProgress(progress: ReadingProgressEntity) {
        readingProgressDao.save(progress)
    }

    override suspend fun setBookmarked(comicId: Long, bookmarked: Boolean) {
        comicDao.setBookmarked(comicId, bookmarked)
    }
}
