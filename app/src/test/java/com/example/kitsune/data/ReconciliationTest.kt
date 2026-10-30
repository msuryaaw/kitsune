package com.example.kitsune.data

import com.example.kitsune.scanner.CbzCatalogResult
import com.example.kitsune.scanner.CbzPageEntry
import com.example.kitsune.scanner.ChapterDiscoveryResult
import com.example.kitsune.scanner.ComicScanIssue
import com.example.kitsune.scanner.DiscoveredChapter
import com.example.kitsune.scanner.DiscoveredComicFolder
import com.example.kitsune.scanner.IncrementalCbzCataloger
import com.example.kitsune.scanner.LibraryScanResult
import com.example.kitsune.scanner.RejectedChapterFile
import com.example.kitsune.scanner.ScannedChapter
import com.example.kitsune.scanner.ScannedComic
import com.example.kitsune.scanner.ScanSummary
import com.example.kitsune.data.entity.ReadingProgressEntity
import com.example.kitsune.storage.StorageEntry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression tests for Task 3.5 — Scan Reconciliation.
 *
 * Tests verify that:
 * 1. Missing chapters are deleted after a successful folder listing.
 * 2. Missing comics are deleted after a successful root enumeration.
 * 3. Corrupt/invalid chapters on filesystem are NOT deleted (they are found files).
 * 4. Failed scans (non-Success result) do NOT trigger any deletion.
 * 5. Folder scan failure (ComicDirectoryMissing) skips chapter reconciliation for that comic.
 * 6. Root isolation: reconciliation only affects the scanned root.
 * 7. Chapter deletion preserves ReadingProgress (no cascade).
 * 8. Chapter 1 deletion clears coverEntryPath — no fallback to other chapters.
 * 9. Empty successful chapter listing vs failed listing: empty listing deletes stale chapters.
 * 10. Bookmark and reading-progress state is preserved for surviving comics.
 */
class ReconciliationTest {

    private lateinit var fakeComicDao: FakeComicDao
    private lateinit var fakeChapterDao: FakeChapterDao
    private lateinit var fakeReadingProgressDao: FakeReadingProgressDao
    private lateinit var fakeScanner: FakeLibraryScanner
    private lateinit var repository: DefaultLibraryRepository

    private val rootUri = "content://com.android.externalstorage.documents/tree/primary%3AKitsune"
    private val rootUri2 = "content://com.android.externalstorage.documents/tree/primary%3AOther"

    @Before
    fun setUp() {
        fakeComicDao = FakeComicDao()
        fakeChapterDao = FakeChapterDao()
        fakeReadingProgressDao = FakeReadingProgressDao()
        fakeScanner = FakeLibraryScanner()
        val noopCataloger = IncrementalCbzCataloger(NoopCbzCataloger)
        val fakeStorage = com.example.kitsune.storage.FakeStorageGateway()
        val coverExtractor = CoverExtractor(fakeStorage, fakeComicDao, fakeChapterDao)
        val cacheDir = java.io.File(System.getProperty("java.io.tmpdir"), "test_recon_covers")
        val coverCacheManager = CoverCacheManager(cacheDir, coverExtractor, fakeComicDao, fakeChapterDao)
        val cbzProvider = com.example.kitsune.reader.DefaultCbzArchiveProvider(
            spoolDir = java.io.File(System.getProperty("java.io.tmpdir"), "test_recon_spool"),
            storageGateway = fakeStorage,
            chapterDao = fakeChapterDao
        )
        repository = DefaultLibraryRepository(
            fakeScanner, noopCataloger, fakeComicDao, fakeChapterDao, fakeReadingProgressDao, coverExtractor, coverCacheManager, fakeStorage, cbzProvider
        )
    }

    // ── Test 1: Missing chapter deleted after successful listing ─────────────

    @Test
    fun reconciliation_missingChapter_isDeletedAfterSuccessfulListing() = runTest {
        // Seed: comic with two chapters
        fakeScanner.result = makeSuccessResult(
            listOf(
                makeScannedComic(
                    relativePath = "Comics/OP",
                    chapters = listOf(
                        makeScannedChapter(1, "Comics/OP/Chapter 1.cbz"),
                        makeScannedChapter(2, "Comics/OP/Chapter 2.cbz")
                    )
                )
            )
        )
        repository.syncLibrary(rootUri)
        assertEquals(2, fakeChapterDao.chapters.size)

        // Second scan: Chapter 2 is gone from the filesystem
        fakeScanner.result = makeSuccessResult(
            listOf(
                makeScannedComic(
                    relativePath = "Comics/OP",
                    chapters = listOf(makeScannedChapter(1, "Comics/OP/Chapter 1.cbz"))
                )
            )
        )
        repository.syncLibrary(rootUri)

        val remaining = fakeChapterDao.chapters.values.toList()
        assertEquals("Stale chapter must be removed", 1, remaining.size)
        assertEquals(1, remaining.first().chapterNumber)
    }

    // ── Test 2: Missing comic deleted after successful enumeration ───────────

    @Test
    fun reconciliation_missingComic_isDeletedAfterSuccessfulEnumeration() = runTest {
        // Seed: two comics
        fakeScanner.result = makeSuccessResult(
            listOf(
                makeScannedComic("Comics/OP"),
                makeScannedComic("Comics/Naruto")
            )
        )
        repository.syncLibrary(rootUri)
        assertEquals(2, fakeComicDao.comics.size)

        // Second scan: Naruto folder is gone
        fakeScanner.result = makeSuccessResult(
            listOf(makeScannedComic("Comics/OP"))
        )
        repository.syncLibrary(rootUri)

        assertEquals("Stale comic must be removed", 1, fakeComicDao.comics.size)
        assertEquals("Comics/OP", fakeComicDao.comics.values.first().relativePath)
    }

    // ── Test 3: Corrupt/invalid chapters on filesystem are NOT deleted ────────

    @Test
    fun reconciliation_corruptChapter_onFilesystem_isNotDeleted() = runTest {
        // Seed: one valid chapter
        fakeScanner.result = makeSuccessResult(
            listOf(
                makeScannedComic(
                    "Comics/OP",
                    chapters = listOf(makeScannedChapter(1, "Comics/OP/Chapter 1.cbz"))
                )
            )
        )
        repository.syncLibrary(rootUri)

        // Second scan: Chapter 1 is corrupt (still present on disk, but invalid catalog result)
        // Chapter 2 is a new corrupt file — also still present
        val corruptChapter1 = ScannedChapter(
            discoveredChapter = makeDiscoveredChapter(1, "Comics/OP/Chapter 1.cbz"),
            catalogResult = CbzCatalogResult.CorruptArchive(
                cbzRelativePath = "Comics/OP/Chapter 1.cbz",
                reason = "Bad zip header"
            )
        )
        val corruptChapter2 = ScannedChapter(
            discoveredChapter = makeDiscoveredChapter(2, "Comics/OP/Chapter 2.cbz"),
            catalogResult = CbzCatalogResult.CorruptArchive(
                cbzRelativePath = "Comics/OP/Chapter 2.cbz",
                reason = "Truncated archive"
            )
        )
        fakeScanner.result = makeSuccessResult(
            listOf(
                makeScannedComicWithIssues(
                    relativePath = "Comics/OP",
                    chapters = listOf(corruptChapter1, corruptChapter2),
                    issues = listOf(
                        ComicScanIssue.CorruptFirstChapter("Comics/OP/Chapter 1.cbz", "Bad zip header"),
                        ComicScanIssue.CorruptChapter("Comics/OP/Chapter 2.cbz", "Truncated archive")
                    )
                )
            )
        )
        repository.syncLibrary(rootUri)

        // Both corrupt chapters are in the scan result (found on disk, just invalid)
        // so neither should be deleted
        assertEquals("Corrupt chapters on filesystem must be preserved", 2, fakeChapterDao.chapters.size)
    }

    // ── Test 4: Failed scan does NOT trigger any deletion ────────────────────

    @Test
    fun reconciliation_errorScan_doesNotDeleteAnything() = runTest {
        // Seed: one comic
        fakeScanner.result = makeSuccessResult(listOf(makeScannedComic("Comics/OP")))
        repository.syncLibrary(rootUri)
        assertEquals(1, fakeComicDao.comics.size)

        // Scan fails with I/O error
        fakeScanner.result = LibraryScanResult.Error(RuntimeException("I/O failure"))
        repository.syncLibrary(rootUri)

        assertEquals("Comics must survive a failed scan", 1, fakeComicDao.comics.size)
    }

    @Test
    fun reconciliation_permissionDenied_doesNotDeleteAnything() = runTest {
        fakeScanner.result = makeSuccessResult(
            listOf(
                makeScannedComic(
                    "Comics/OP",
                    chapters = listOf(makeScannedChapter(1, "Comics/OP/Chapter 1.cbz"))
                )
            )
        )
        repository.syncLibrary(rootUri)
        val chapterCountBefore = fakeChapterDao.chapters.size

        fakeScanner.result = LibraryScanResult.PermissionDenied("Permission revoked")
        repository.syncLibrary(rootUri)

        assertEquals("Chapters must survive permission denied", chapterCountBefore, fakeChapterDao.chapters.size)
        assertEquals("Comics must survive permission denied", 1, fakeComicDao.comics.size)
    }

    // ── Test 5: Folder scan failure skips chapter reconciliation ─────────────

    @Test
    fun reconciliation_comicDirectoryMissing_skipsChapterReconciliation() = runTest {
        // Seed: comic with two chapters
        fakeScanner.result = makeSuccessResult(
            listOf(
                makeScannedComic(
                    "Comics/OP",
                    chapters = listOf(
                        makeScannedChapter(1, "Comics/OP/Chapter 1.cbz"),
                        makeScannedChapter(2, "Comics/OP/Chapter 2.cbz")
                    )
                )
            )
        )
        repository.syncLibrary(rootUri)
        assertEquals(2, fakeChapterDao.chapters.size)

        // Second scan: comic folder vanished during scan (ComicDirectoryMissing issue)
        // scannedChapters is empty, but folder listing FAILED — must NOT reconcile
        fakeScanner.result = makeSuccessResult(
            listOf(
                makeScannedComicWithIssues(
                    relativePath = "Comics/OP",
                    chapters = emptyList(),
                    issues = listOf(ComicScanIssue.ComicDirectoryMissing("Comics/OP"))
                )
            )
        )
        repository.syncLibrary(rootUri)

        assertEquals(
            "Chapters must NOT be deleted when folder listing failed (ComicDirectoryMissing)",
            2,
            fakeChapterDao.chapters.size
        )
    }

    @Test
    fun reconciliation_chapterEnumerationErrorOnFolder_skipsChapterReconciliation() = runTest {
        // Seed: comic with one chapter
        fakeScanner.result = makeSuccessResult(
            listOf(
                makeScannedComic(
                    "Comics/OP",
                    chapters = listOf(makeScannedChapter(1, "Comics/OP/Chapter 1.cbz"))
                )
            )
        )
        repository.syncLibrary(rootUri)
        assertEquals(1, fakeChapterDao.chapters.size)

        // Second scan: folder-level I/O error on the comic folder.
        // ChapterDiscoveryResult.Error now produces ChapterEnumerationError (folder path).
        // The chapter list is empty but NOT authoritative — chapters must be preserved.
        fakeScanner.result = makeSuccessResult(
            listOf(
                makeScannedComicWithIssues(
                    relativePath = "Comics/OP",
                    chapters = emptyList(),
                    issues = listOf(
                        ComicScanIssue.ChapterEnumerationError(
                            comicRelativePath = "Comics/OP",
                            exception = RuntimeException("I/O error on folder")
                        )
                    )
                )
            )
        )
        repository.syncLibrary(rootUri)

        assertEquals(
            "Chapters must NOT be deleted when folder-level ChapterEnumerationError occurred",
            1,
            fakeChapterDao.chapters.size
        )
    }

    // Test 5b: File-level ChapterCatalogError allows reconciliation (regression for 3.5)

    @Test
    fun reconciliation_chapterCatalogErrorOnFile_allowsReconciliationForOtherChapters() = runTest {
        // Seed: comic with three chapters
        fakeScanner.result = makeSuccessResult(
            listOf(
                makeScannedComic(
                    relativePath = "Comics/OP",
                    chapters = listOf(
                        makeScannedChapter(1, "Comics/OP/Chapter 1.cbz"),
                        makeScannedChapter(2, "Comics/OP/Chapter 2.cbz"),
                        makeScannedChapter(3, "Comics/OP/Chapter 3.cbz")
                    )
                )
            )
        )
        repository.syncLibrary(rootUri)
        assertEquals(3, fakeChapterDao.chapters.size)

        // Second scan: folder enumeration SUCCEEDED (ChapterDiscoveryResult.Success).
        // Chapter 2 was found on disk but failed during CBZ cataloging (file-level error).
        // This produces ChapterCatalogError (file path), NOT ChapterEnumerationError.
        // Chapter 3 is completely absent from the filesystem.
        // Expected: Chapter 2 is preserved (found file, just failed catalog).
        //           Chapter 3 IS deleted (absent from the authoritative enumeration).
        val ch2InvalidCatalog = ScannedChapter(
            discoveredChapter = makeDiscoveredChapter(2, "Comics/OP/Chapter 2.cbz"),
            catalogResult = CbzCatalogResult.Error(
                exception = RuntimeException("Corrupt zip")
            )
        )
        fakeScanner.result = makeSuccessResult(
            listOf(
                makeScannedComicWithIssues(
                    relativePath = "Comics/OP",
                    // Chapter 3 is absent - enumeration found only Ch1 and Ch2.
                    chapters = listOf(
                        makeScannedChapter(1, "Comics/OP/Chapter 1.cbz"),
                        ch2InvalidCatalog
                    ),
                    // File-level error: CBZ was found but could not be opened.
                    issues = listOf(
                        ComicScanIssue.ChapterCatalogError(
                            cbzRelativePath = "Comics/OP/Chapter 2.cbz",
                            exception = RuntimeException("Corrupt zip")
                        )
                    )
                )
            )
        )
        repository.syncLibrary(rootUri)

        val remaining = fakeChapterDao.chapters.values.toList()
        assertEquals(
            "File-level ChapterCatalogError must not block reconciliation: Ch3 must be deleted",
            2,
            remaining.size
        )
        val remainingNumbers = remaining.map { it.chapterNumber }.toSet()
        assertTrue(
            "Chapter 1 must survive",
            1 in remainingNumbers
        )
        assertTrue(
            "Chapter 2 must survive (found file, even though catalog failed)",
            2 in remainingNumbers
        )
        assertTrue(
            "Chapter 3 must be deleted (absent from authoritative enumeration)",
            3 !in remainingNumbers
        )
    }

    // ── Test 6: Root isolation during reconciliation ─────────────────────────

    @Test
    fun reconciliation_differentRoots_doNotAffectEachOther() = runTest {
        // Seed root1 with comic A, root2 with comic B
        fakeScanner.result = makeSuccessResult(listOf(makeScannedComic("Comics/A")))
        repository.syncLibrary(rootUri)

        fakeScanner.result = makeSuccessResult(listOf(makeScannedComic("Comics/B")))
        repository.syncLibrary(rootUri2)

        assertEquals(2, fakeComicDao.comics.size)

        // Rescan root1 with only comic A (same as before) — no change
        fakeScanner.result = makeSuccessResult(listOf(makeScannedComic("Comics/A")))
        repository.syncLibrary(rootUri)

        // root2's comic B should still be intact
        val root2Comics = fakeComicDao.comics.values.filter { it.rootUriString == rootUri2 }
        assertEquals("Root2 comic must not be affected by root1 reconciliation", 1, root2Comics.size)
        assertEquals("Comics/B", root2Comics.first().relativePath)
    }

    @Test
    fun reconciliation_comicDeletedFromRoot1_doesNotAffectRoot2() = runTest {
        // Seed root1 with comics A and C, root2 with comic A (same relative path)
        fakeScanner.result = makeSuccessResult(
            listOf(makeScannedComic("Comics/A"), makeScannedComic("Comics/C"))
        )
        repository.syncLibrary(rootUri)

        fakeScanner.result = makeSuccessResult(listOf(makeScannedComic("Comics/A")))
        repository.syncLibrary(rootUri2)

        assertEquals(3, fakeComicDao.comics.size)

        // Rescan root1: Comics/C is gone
        fakeScanner.result = makeSuccessResult(listOf(makeScannedComic("Comics/A")))
        repository.syncLibrary(rootUri)

        // root1 should have only Comics/A; root2 should still have Comics/A
        val root1Comics = fakeComicDao.comics.values.filter { it.rootUriString == rootUri }
        val root2Comics = fakeComicDao.comics.values.filter { it.rootUriString == rootUri2 }
        assertEquals(1, root1Comics.size)
        assertEquals("Comics/A", root1Comics.first().relativePath)
        assertEquals("Root2 must still have Comics/A", 1, root2Comics.size)
    }

    // ── Test 7: Chapter deletion preserves ReadingProgress ──────────────────

    @Test
    fun reconciliation_chapterDeleted_preservesReadingProgress() = runTest {
        // Seed: comic with two chapters
        fakeScanner.result = makeSuccessResult(
            listOf(
                makeScannedComic(
                    "Comics/OP",
                    chapters = listOf(
                        makeScannedChapter(1, "Comics/OP/Chapter 1.cbz"),
                        makeScannedChapter(2, "Comics/OP/Chapter 2.cbz")
                    )
                )
            )
        )
        repository.syncLibrary(rootUri)

        val comicId = fakeComicDao.comics.values.first().id
        val chapter2 = fakeChapterDao.chapters.values.first { it.chapterNumber == 2 }

        // Save reading progress pointing to chapter 2
        val progress = ReadingProgressEntity(
            comicId = comicId,
            lastReadChapterId = chapter2.id,
            lastReadChapterNumber = chapter2.chapterNumber,
            chapterRelativePath = chapter2.relativePath,
            pageIndex = 5
        )
        repository.saveReadingProgress(progress)
        assertNotNull(repository.getReadingProgress(comicId))

        // Rescan without Chapter 2 — it gets reconciled away
        fakeScanner.result = makeSuccessResult(
            listOf(
                makeScannedComic(
                    "Comics/OP",
                    chapters = listOf(makeScannedChapter(1, "Comics/OP/Chapter 1.cbz"))
                )
            )
        )
        repository.syncLibrary(rootUri)

        assertEquals("Chapter 2 must be deleted", 1, fakeChapterDao.chapters.size)

        // ReadingProgress must survive (no FK cascade from chapters to reading_progress)
        val survivingProgress = repository.getReadingProgress(comicId)
        assertNotNull("ReadingProgress must be preserved after chapter deletion", survivingProgress)
        assertEquals(chapter2.id, survivingProgress!!.lastReadChapterId)
        assertEquals(5, survivingProgress.pageIndex)
    }

    // ── Test 8: Chapter 1 deletion clears coverEntryPath (no fallback) ────────

    @Test
    fun reconciliation_chapter1Deleted_clearsCoverEntryPath_noFallback() = runTest {
        // Seed: comic with Chapter 1 (has cover) and Chapter 2
        val chapter1 = ScannedChapter(
            discoveredChapter = makeDiscoveredChapter(1, "Comics/OP/Chapter 1.cbz"),
            catalogResult = CbzCatalogResult.Success(
                cbzRelativePath = "Comics/OP/Chapter 1.cbz",
                pages = listOf(CbzPageEntry("cover.jpg", 1))
            )
        )
        val chapter2 = ScannedChapter(
            discoveredChapter = makeDiscoveredChapter(2, "Comics/OP/Chapter 2.cbz"),
            catalogResult = CbzCatalogResult.Success(
                cbzRelativePath = "Comics/OP/Chapter 2.cbz",
                pages = listOf(CbzPageEntry("page.jpg", 1))
            )
        )
        fakeScanner.result = makeSuccessResult(
            listOf(makeScannedComicWithIssues("Comics/OP", listOf(chapter1, chapter2), emptyList()))
        )
        repository.syncLibrary(rootUri)

        val comic = fakeComicDao.comics.values.first()
        assertEquals("cover.jpg", comic.coverEntryPath)

        // Second scan: Chapter 1 is gone; only Chapter 2 remains
        fakeScanner.result = makeSuccessResult(
            listOf(makeScannedComic("Comics/OP", chapters = listOf(
                ScannedChapter(
                    discoveredChapter = makeDiscoveredChapter(2, "Comics/OP/Chapter 2.cbz"),
                    catalogResult = CbzCatalogResult.Success(
                        cbzRelativePath = "Comics/OP/Chapter 2.cbz",
                        pages = listOf(CbzPageEntry("page.jpg", 1))
                    )
                )
            )))
        )
        repository.syncLibrary(rootUri)

        val updatedComic = fakeComicDao.comics.values.first()
        assertNull(
            "coverEntryPath must be null when Chapter 1 is deleted (no fallback to Chapter 2)",
            updatedComic.coverEntryPath
        )
    }

    @Test
    fun reconciliation_nonChapter1Deleted_doesNotClearCover() = runTest {
        // Seed: comic with Chapter 1 (has cover) and Chapter 2
        val chapter1 = ScannedChapter(
            discoveredChapter = makeDiscoveredChapter(1, "Comics/OP/Chapter 1.cbz"),
            catalogResult = CbzCatalogResult.Success(
                cbzRelativePath = "Comics/OP/Chapter 1.cbz",
                pages = listOf(CbzPageEntry("cover.jpg", 1))
            )
        )
        val chapter2 = ScannedChapter(
            discoveredChapter = makeDiscoveredChapter(2, "Comics/OP/Chapter 2.cbz"),
            catalogResult = CbzCatalogResult.Success(
                cbzRelativePath = "Comics/OP/Chapter 2.cbz",
                pages = listOf(CbzPageEntry("page.jpg", 1))
            )
        )
        fakeScanner.result = makeSuccessResult(
            listOf(makeScannedComicWithIssues("Comics/OP", listOf(chapter1, chapter2), emptyList()))
        )
        repository.syncLibrary(rootUri)

        val coverBefore = fakeComicDao.comics.values.first().coverEntryPath
        assertEquals("cover.jpg", coverBefore)

        // Second scan: Chapter 2 is gone, Chapter 1 survives
        fakeScanner.result = makeSuccessResult(
            listOf(makeScannedComicWithIssues("Comics/OP", listOf(chapter1), emptyList()))
        )
        repository.syncLibrary(rootUri)

        val updatedComic = fakeComicDao.comics.values.first()
        assertEquals(
            "coverEntryPath must remain when only non-Chapter-1 is deleted",
            "cover.jpg",
            updatedComic.coverEntryPath
        )
    }

    // ── Test 9: Empty successful listing vs failed listing ───────────────────

    @Test
    fun reconciliation_emptySuccessfulChapterListing_deletesAllStaleChapters() = runTest {
        // Seed: comic with two chapters
        fakeScanner.result = makeSuccessResult(
            listOf(
                makeScannedComic(
                    "Comics/OP",
                    chapters = listOf(
                        makeScannedChapter(1, "Comics/OP/Chapter 1.cbz"),
                        makeScannedChapter(2, "Comics/OP/Chapter 2.cbz")
                    )
                )
            )
        )
        repository.syncLibrary(rootUri)
        assertEquals(2, fakeChapterDao.chapters.size)

        // Second scan: folder scan succeeded but found zero chapters (all files renamed/removed)
        // This is a SUCCESSFUL listing with empty result — must delete stale chapters
        fakeScanner.result = makeSuccessResult(
            listOf(
                makeScannedComicWithIssues(
                    relativePath = "Comics/OP",
                    chapters = emptyList(),
                    issues = listOf(ComicScanIssue.NoValidChapters("Comics/OP"))
                )
            )
        )
        repository.syncLibrary(rootUri)

        assertEquals(
            "All stale chapters must be deleted after an empty but successful listing",
            0,
            fakeChapterDao.chapters.size
        )
    }

    @Test
    fun reconciliation_failedChapterListing_preservesStaleChapters() = runTest {
        // Seed: comic with two chapters
        fakeScanner.result = makeSuccessResult(
            listOf(
                makeScannedComic(
                    "Comics/OP",
                    chapters = listOf(
                        makeScannedChapter(1, "Comics/OP/Chapter 1.cbz"),
                        makeScannedChapter(2, "Comics/OP/Chapter 2.cbz")
                    )
                )
            )
        )
        repository.syncLibrary(rootUri)
        assertEquals(2, fakeChapterDao.chapters.size)

        // Second scan: folder listing FAILED (ComicDirectoryMissing)
        // chapters list is empty, but the listing itself did not succeed
        fakeScanner.result = makeSuccessResult(
            listOf(
                makeScannedComicWithIssues(
                    relativePath = "Comics/OP",
                    chapters = emptyList(),
                    issues = listOf(ComicScanIssue.ComicDirectoryMissing("Comics/OP"))
                )
            )
        )
        repository.syncLibrary(rootUri)

        assertEquals(
            "Stale chapters must NOT be deleted when chapter listing failed",
            2,
            fakeChapterDao.chapters.size
        )
    }

    // ── Test 10: Bookmark and reading progress preserved for surviving comics ─

    @Test
    fun reconciliation_survivingComic_preservesBookmarkAndLastReadAt() = runTest {
        // Seed: two comics
        fakeScanner.result = makeSuccessResult(
            listOf(
                makeScannedComic("Comics/OP", chapters = listOf(makeScannedChapter(1, "Comics/OP/Chapter 1.cbz"))),
                makeScannedComic("Comics/Naruto", chapters = listOf(makeScannedChapter(1, "Comics/Naruto/Chapter 1.cbz")))
            )
        )
        repository.syncLibrary(rootUri)

        val opId = fakeComicDao.comics.values.first { it.relativePath == "Comics/OP" }.id
        fakeComicDao.setBookmarked(opId, true)
        fakeComicDao.updateLastReadAt(opId, 99999L)

        // Rescan: Naruto is gone, OP survives
        fakeScanner.result = makeSuccessResult(
            listOf(makeScannedComic("Comics/OP", chapters = listOf(makeScannedChapter(1, "Comics/OP/Chapter 1.cbz"))))
        )
        repository.syncLibrary(rootUri)

        assertEquals(1, fakeComicDao.comics.size)
        val survivingOp = fakeComicDao.comics.values.first()
        assertEquals("Comics/OP", survivingOp.relativePath)
        assertTrue("Bookmark must be preserved for surviving comic", survivingOp.isBookmarked)
        assertEquals("lastReadAt must be preserved for surviving comic", 99999L, survivingOp.lastReadAt)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeDiscoveredChapter(
        chapterNumber: Int,
        relativePath: String,
        sizeBytes: Long? = 1024L,
        lastModifiedMillis: Long? = 0L
    ): DiscoveredChapter = DiscoveredChapter(
        fileName = "Chapter $chapterNumber.cbz",
        relativePath = relativePath,
        chapterNumber = chapterNumber,
        entry = StorageEntry(
            name = "Chapter $chapterNumber.cbz",
            relativePath = relativePath,
            isDirectory = false,
            sizeBytes = sizeBytes,
            lastModifiedMillis = lastModifiedMillis
        )
    )

    private fun makeScannedChapter(
        chapterNumber: Int,
        relativePath: String,
        pageCount: Int = 10
    ): ScannedChapter = ScannedChapter(
        discoveredChapter = makeDiscoveredChapter(chapterNumber, relativePath),
        catalogResult = CbzCatalogResult.Success(
            cbzRelativePath = relativePath,
            pages = (1..pageCount).map { CbzPageEntry("page_$it.jpg", it) }
        )
    )

    private fun makeScannedComic(
        relativePath: String,
        title: String = relativePath.substringAfterLast("/"),
        chapters: List<ScannedChapter> = listOf(makeScannedChapter(1, "$relativePath/Chapter 1.cbz"))
    ): ScannedComic = ScannedComic(
        folder = DiscoveredComicFolder(
            folderName = title,
            relativePath = relativePath,
            defaultTitle = title,
            entry = StorageEntry(
                name = title,
                relativePath = relativePath,
                isDirectory = true,
                sizeBytes = null,
                lastModifiedMillis = null
            )
        ),
        chapters = chapters,
        rejectedFiles = emptyList(),
        issues = emptyList()
    )

    private fun makeScannedComicWithIssues(
        relativePath: String,
        chapters: List<ScannedChapter>,
        issues: List<ComicScanIssue>,
        title: String = relativePath.substringAfterLast("/")
    ): ScannedComic = ScannedComic(
        folder = DiscoveredComicFolder(
            folderName = title,
            relativePath = relativePath,
            defaultTitle = title,
            entry = StorageEntry(
                name = title,
                relativePath = relativePath,
                isDirectory = true,
                sizeBytes = null,
                lastModifiedMillis = null
            )
        ),
        chapters = chapters,
        rejectedFiles = emptyList(),
        issues = issues
    )

    private fun makeSuccessResult(comics: List<ScannedComic>): LibraryScanResult.Success =
        LibraryScanResult.Success(
            comics = comics,
            summary = ScanSummary(
                totalComicFoldersDiscovered = comics.size,
                validComicsCount = comics.count { it.hasValidChapters },
                totalChaptersDiscovered = comics.sumOf { it.chapters.size },
                totalValidChaptersCount = comics.sumOf { c -> c.chapters.count { it.isValid } },
                totalCorruptArchivesCount = 0,
                totalEmptyArchivesCount = 0,
                totalRejectedFilesCount = 0,
                comicsMissingFirstChapterCount = 0
            ),
            issues = comics.flatMap { it.issues }
        )
}
