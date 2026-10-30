package com.example.kitsune.data

import com.example.kitsune.data.dao.ChapterDao
import com.example.kitsune.data.dao.ComicDao
import com.example.kitsune.data.dao.ReadingProgressDao
import com.example.kitsune.data.entity.ChapterEntity
import com.example.kitsune.data.entity.ComicEntity
import com.example.kitsune.data.entity.ReadingProgressEntity
import com.example.kitsune.scanner.CbzCatalogResult
import com.example.kitsune.scanner.CbzPageEntry
import com.example.kitsune.scanner.ComicScanIssue
import com.example.kitsune.scanner.IncrementalCbzCataloger
import com.example.kitsune.scanner.LibraryScanner
import com.example.kitsune.scanner.LibraryScanResult
import com.example.kitsune.scanner.ScannedComic
import com.example.kitsune.scanner.ScannedChapter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Default implementation of [LibraryRepository].
 *
 * Orchestrates the scan → map → persist pipeline with incremental CBZ cataloging:
 * 1. Before each scan, loads the current chapter index from the database and arms the
 *    [IncrementalCbzCataloger] so the scanner can skip re-reading unchanged CBZ archives.
 * 2. Delegates scanning to [LibraryScanner]. The [IncrementalCbzCataloger] intercepts each
 *    `catalogArchive()` call: unchanged chapters bypass actual ZIP I/O and return a synthesized
 *    cached result built from stored [ChapterEntity] metadata.
 * 3. On [LibraryScanResult.Success], maps every scanned comic and chapter to Room entities,
 *    upserts them, and then reconciles (deletes) stale records that are no longer present on the
 *    filesystem. Chapter reconciliation is skipped for comics whose folder listing failed.
 *    Comic reconciliation removes comics whose folder is absent from the root enumeration.
 * 4. Exposes reactive [Flow] queries from the DAOs to the UI layer.
 *
 * Incremental CBZ skip conditions (ALL must hold for a chapter to skip CBZ I/O):
 *  - The scanner supplies [com.example.kitsune.storage.StorageEntry] metadata for the chapter
 *    ([com.example.kitsune.scanner.DefaultLibraryScanner] always passes this).
 *  - Both [com.example.kitsune.storage.StorageEntry.sizeBytes] and
 *    [com.example.kitsune.storage.StorageEntry.lastModifiedMillis] are non-null.
 *  - A [ChapterEntity] for the same `relativePath` exists in the DB with matching size and timestamp.
 *  - [ChapterEntity.isValid] is `true` (previously-invalid chapters are always re-cataloged so
 *    a repaired archive on disk is detected).
 *
 * Cover preservation on incremental re-scan:
 *  - For Chapter 1, the synthesized [CbzCatalogResult.Success] uses the stored
 *    [ComicEntity.coverEntryPath] as the first page's entry path so that
 *    [ScannedComic.coverEntryPath] returns the correct value without re-opening the archive.
 *  - For other chapters, the synthesized success contains stub page entries matching [ChapterEntity.pageCount].
 *
 * Key invariants:
 * - [ComicDao.upsertByRootAndPath] preserves [ComicEntity.isBookmarked] and [ComicEntity.lastReadAt].
 * - [ChapterDao.upsertByComicAndPath] preserves chapter IDs, protecting [ReadingProgressEntity] refs.
 * - Cover is derived from [ScannedComic.coverEntryPath] (Chapter 1 only, K-07); cleared when
 *   Chapter 1 is deleted during reconciliation.
 * - Any non-Success scan result leaves existing database records untouched (K-03).
 * - Reconciliation never runs on failed or partial scans, preventing mass deletion on transient errors.
 */
class DefaultLibraryRepository(
    private val libraryScanner: LibraryScanner,
    private val incrementalCataloger: IncrementalCbzCataloger,
    private val comicDao: ComicDao,
    private val chapterDao: ChapterDao,
    private val readingProgressDao: ReadingProgressDao,
    private val coverExtractor: CoverExtractor,
    private val coverCacheManager: CoverCacheManager,
    private val storageGateway: com.example.kitsune.storage.StorageGateway
) : LibraryRepository {

    /**
     * Mutex protecting the entire sync cycle (priming incremental index, executing scan,
     * and persisting results) against concurrent scans across the same or different roots.
     * Serializing syncLibrary ensures that cache state within [incrementalCataloger] and
     * the underlying scanner pipeline cannot be clobbered or interleaved by simultaneous callers.
     */
    private val syncMutex = Mutex()
    private val _lastScanResult = MutableStateFlow<LibraryScanResult?>(null)

    override fun observeLastScanResult(): Flow<LibraryScanResult?> = _lastScanResult

    // ── Scan and sync ─────────────────────────────────────────────────────────

    override suspend fun syncLibrary(rootUri: String): LibraryScanResult = syncMutex.withLock {
        // Prime the incremental cataloger with the current DB snapshot for this root.
        // The scanner's CbzCataloger calls will then bypass ZIP I/O for unchanged chapters.
        primeIncrementalIndex(rootUri)

        val scanResult = libraryScanner.scanLibrary()
        _lastScanResult.value = scanResult

        if (scanResult is LibraryScanResult.Success) {
            val nowMillis = System.currentTimeMillis()
            syncSuccess(rootUri, scanResult, nowMillis)
        }
        // For all other results (Error, PermissionDenied, RootNotConfigured,
        // ComicsDirectoryNotFound, EmptyComicsDirectory): do nothing — existing
        // database records must not be deleted due to a transient or unrecoverable error.

        scanResult
    }

    /**
     * Loads the chapter index for [rootUri] from the DB and updates [incrementalCataloger].
     *
     * For each previously-valid chapter, a [IncrementalCbzCataloger.CachedEntry] is built:
     * - Fingerprint: stored `fileSize` and `lastModifiedAt`.
     * - Cached result: a synthesized [CbzCatalogResult.Success] whose page count matches
     *   the stored [ChapterEntity.pageCount].
     * - For Chapter 1: must have a trusted [ComicEntity.coverEntryPath] from the parent comic.
     *   If [ComicEntity.coverEntryPath] is null or blank, Chapter 1 is excluded from cache
     *   so it is re-cataloged by the delegate to recover the genuine cover entry path.
     *
     * Only chapters with [ChapterEntity.isValid] == true are indexed; previously-invalid chapters
     * are excluded so that repaired archives on disk are always re-cataloged by the real cataloger.
     */
    private suspend fun primeIncrementalIndex(rootUri: String) {
        val comics: List<ComicEntity> = comicDao.getAllByRoot(rootUri)
        val newIndex = mutableMapOf<String, IncrementalCbzCataloger.CachedEntry>()

        for (comic in comics) {
            val chapters: List<ChapterEntity> = chapterDao.getAllByComicId(comic.id)
            for (chapter in chapters) {
                if (!chapter.isValid) continue          // repaired files must be re-cataloged
                val fileSize = chapter.fileSize ?: continue      // null metadata → cannot match
                val lastModified = chapter.lastModifiedAt ?: continue

                // Chapter 1 safety check:
                // Never cache Chapter 1 without a trusted, non-blank coverEntryPath.
                // If cover is missing or untrusted, exclude it from cache so the delegate
                // opens the archive to discover the authentic first page.
                if (chapter.chapterNumber == 1 && comic.coverEntryPath.isNullOrBlank()) {
                    continue
                }

                val coverPath: String? = if (chapter.chapterNumber == 1) comic.coverEntryPath else null
                val cachedResult = buildSynthesizedSuccess(
                    cbzRelativePath = chapter.relativePath,
                    pageCount = chapter.pageCount,
                    coverEntryPath = coverPath
                )

                newIndex[chapter.relativePath] = IncrementalCbzCataloger.CachedEntry(
                    fileSize = fileSize,
                    lastModifiedMillis = lastModified,
                    wasValid = true,
                    cachedResult = cachedResult
                )
            }
        }

        incrementalCataloger.updateIndex(newIndex)
    }

    /**
     * Builds a synthesized [CbzCatalogResult.Success] from stored DB metadata, avoiding ZIP I/O.
     *
     * @param cbzRelativePath Relative path of the CBZ archive.
     * @param pageCount Number of pages to simulate (must equal [ChapterEntity.pageCount]).
     * @param coverEntryPath If non-null, used as the entry path of the first page (Chapter 1 only).
     */
    internal fun buildSynthesizedSuccess(
        cbzRelativePath: String,
        pageCount: Int,
        coverEntryPath: String?
    ): CbzCatalogResult.Success {
        val pages = if (pageCount <= 0) {
            emptyList()
        } else {
            (1..pageCount).map { i ->
                CbzPageEntry(
                    entryPath = if (i == 1 && coverEntryPath != null) {
                        coverEntryPath
                    } else {
                        "stub_${i.toString().padStart(3, '0')}.jpg"
                    },
                    pageNumber = i
                )
            }
        }
        return CbzCatalogResult.Success(cbzRelativePath = cbzRelativePath, pages = pages)
    }

    // ── Sync on success ───────────────────────────────────────────────────────

    /**
     * Persists each scanned comic and its chapters from a successful scan result, then
     * reconciles (deletes) stale records that are no longer present on the filesystem.
     *
     * All chapters are always upserted. The incremental optimization occurs at the I/O layer
     * ([IncrementalCbzCataloger] skips ZIP reads), not at the DB layer, so every chapter's
     * current metadata is always reflected in the database after a sync.
     *
     * Reconciliation rules:
     * - **Chapter reconciliation** — runs per comic only when its folder listing completed
     *   successfully (no [ComicScanIssue.ComicDirectoryMissing] or
     *   [ComicScanIssue.ChapterEnumerationError] in [ScannedComic.issues]).
     *   Chapters that are still present on disk but corrupt ([ScannedChapter.isValid] == false)
     *   are in the scanned list and are NOT deleted.
     * - **Comic reconciliation** — after all comics are processed, comics whose
     *   [ComicEntity.relativePath] is absent from the scan results are deleted.
     *   [ComicDao.deleteById] cascades to associated [ChapterEntity] rows via FK.
     * - **Cover clearing** — when Chapter 1 is among the deleted chapters, the parent comic's
     *   [ComicEntity.coverEntryPath] is set to null (no fallback to other chapters, K-07).
     * - **ReadingProgress safety** — [ReadingProgressEntity] has no FK to [ChapterEntity],
     *   so deleting chapters never cascade-deletes reading progress records.
     */
    private suspend fun syncSuccess(
        rootUri: String,
        result: LibraryScanResult.Success,
        nowMillis: Long
    ) {
        // ── Phase 1: upsert comics and chapters ──────────────────────────────
        for (scannedComic in result.comics) {
            val comicEntity = mapScannedComicToEntity(rootUri, scannedComic, nowMillis)
            val comicId = comicDao.upsertByRootAndPath(comicEntity)

            for (scannedChapter in scannedComic.chapters) {
                val chapterEntity = mapScannedChapterToEntity(comicId, scannedChapter)
                chapterDao.upsertByComicAndPath(chapterEntity)
            }

            // ── Phase 2: reconcile chapters for this comic ───────────────────
            // Only reconcile when the folder listing completed successfully.
            // ComicDirectoryMissing means the folder vanished during scanning — we cannot
            // treat the empty chapter list as authoritative; chapters must be preserved.
            // ChapterEnumerationError is a folder-level I/O failure (directory listing failed) —
            // chapter list is empty and not authoritative; preserved chapters must not be deleted.
            // ChapterCatalogError is a file-level failure (specific CBZ found but could not be
            // opened during cataloging); directory enumeration DID succeed, the chapter list
            // IS authoritative, and reconciliation should proceed for the other chapters.
            // Other issue types (CorruptChapter, EmptyChapter, etc.) are also file-level and
            // do NOT prevent the chapter list from being authoritative.
            val folderListingSucceeded = scannedComic.issues.none { issue ->
                issue is ComicScanIssue.ComicDirectoryMissing ||
                    issue is ComicScanIssue.ChapterEnumerationError
            }
            if (folderListingSucceeded) {
                reconcileChapters(comicId, scannedComic)
            }
        }

        // ── Phase 3: reconcile comics at root level ───────────────────────────
        // The LibraryScanResult.Success.comics list is the complete set of folders found
        // under the Comics directory for this root. Any DB record not in that set is stale.
        val scannedComicPaths = result.comics.map { it.relativePath }
        val orphanedComicIds = comicDao.findOrphanedIds(rootUri, scannedComicPaths)
        for (comicId in orphanedComicIds) {
            // FK CASCADE removes child chapters automatically; reading progress is
            // intentionally preserved (no FK from ReadingProgressEntity to ComicEntity).
            comicDao.deleteById(comicId)
        }
    }

    /**
     * Deletes chapters belonging to [comicId] that are absent from [scannedComic]'s chapter list.
     *
     * Before deleting, inspects whether any orphaned chapter is Chapter 1 and, if so,
     * clears [ComicEntity.coverEntryPath] (no fallback to other chapters, K-07).
     *
     * Chapters on disk that failed cataloging ([ScannedChapter.isValid] == false) are still
     * present in [ScannedComic.chapters] and are NOT deleted — they are found files.
     */
    private suspend fun reconcileChapters(comicId: Long, scannedComic: ScannedComic) {
        val scannedChapterPaths = scannedComic.chapters.map { it.discoveredChapter.relativePath }
        val orphanedIds = chapterDao.findOrphanedIds(comicId, scannedChapterPaths)
        if (orphanedIds.isEmpty()) return

        // Determine whether Chapter 1 is being removed; if so, cover must be cleared.
        val chapter1Deleted = orphanedIds.any { id ->
            chapterDao.getById(id)?.chapterNumber == 1
        }

        chapterDao.deleteByIds(orphanedIds)

        if (chapter1Deleted) {
            // Clear cover on the parent comic — no fallback to other chapters (K-07).
            comicDao.getById(comicId)?.let { comic ->
                comicDao.update(comic.copy(coverEntryPath = null))
            }
        }
    }

    // ── Mapping ───────────────────────────────────────────────────────────────

    /**
     * Maps a [ScannedComic] to a [ComicEntity].
     *
     * Cover is sourced from [ScannedComic.coverEntryPath] which the scanner constrains to
     * Chapter 1 only (K-07). For chapters that bypassed CBZ I/O via the incremental cataloger,
     * this value is the stored cover path supplied during index priming.
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
     * cataloged and contains at least one image page. For chapters that bypassed CBZ I/O,
     * this is always `true` (the synthesized result is a [CbzCatalogResult.Success]).
     */
    private fun mapScannedChapterToEntity(
        comicId: Long,
        scannedChapter: ScannedChapter
    ): ChapterEntity {
        val discovered = scannedChapter.discoveredChapter

        return ChapterEntity(
            comicId = comicId,
            relativePath = discovered.relativePath,
            chapterNumber = discovered.chapterNumber,
            displayName = discovered.fileName,
            pageCount = scannedChapter.pageCount,
            fileSize = discovered.entry.sizeBytes,
            lastModifiedAt = discovered.entry.lastModifiedMillis,
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

    override fun observeComicById(comicId: Long): Flow<ComicEntity?> =
        comicDao.observeComicById(comicId)

    override suspend fun verifyChapterAvailable(chapterId: Long): ChapterAvailabilityResult {
        val chapter = chapterDao.getById(chapterId) ?: return ChapterAvailabilityResult.Missing
        if (!chapter.isValid) return ChapterAvailabilityResult.Missing

        return when (val rootState = storageGateway.getRootState()) {
            is com.example.kitsune.storage.StorageResult.RootNotConfigured -> ChapterAvailabilityResult.Missing
            is com.example.kitsune.storage.StorageResult.PermissionDenied -> ChapterAvailabilityResult.PermissionDenied(rootState.message)
            is com.example.kitsune.storage.StorageResult.Error -> ChapterAvailabilityResult.Error(rootState.exception)
            is com.example.kitsune.storage.StorageResult.NotFound -> ChapterAvailabilityResult.Missing
            is com.example.kitsune.storage.StorageResult.Success -> {
                when (val entryResult = storageGateway.findEntry(chapter.relativePath)) {
                    is com.example.kitsune.storage.StorageResult.Success -> ChapterAvailabilityResult.Available
                    is com.example.kitsune.storage.StorageResult.NotFound -> ChapterAvailabilityResult.Missing
                    is com.example.kitsune.storage.StorageResult.PermissionDenied -> ChapterAvailabilityResult.PermissionDenied(entryResult.message)
                    is com.example.kitsune.storage.StorageResult.Error -> ChapterAvailabilityResult.Error(entryResult.exception)
                    is com.example.kitsune.storage.StorageResult.RootNotConfigured -> ChapterAvailabilityResult.Missing
                }
            }
        }
    }

    // ── One-shot mutations ────────────────────────────────────────────────────

    override suspend fun getReadingProgress(comicId: Long): ReadingProgressEntity? =
        readingProgressDao.getByComicId(comicId)

    override suspend fun saveReadingProgress(progress: ReadingProgressEntity) {
        readingProgressDao.save(progress)
    }

    override suspend fun setBookmarked(comicId: Long, bookmarked: Boolean) {
        comicDao.setBookmarked(comicId, bookmarked)
    }

    override suspend fun fetchCover(comicId: Long): CoverResult =
        coverExtractor.extractCover(comicId)

    override suspend fun getCoverFile(comicId: Long): CoverCacheResult =
        coverCacheManager.getCoverFile(comicId)

    override suspend fun releaseCoverFile(file: java.io.File) {
        coverCacheManager.releaseCoverFile(file)
    }
}
