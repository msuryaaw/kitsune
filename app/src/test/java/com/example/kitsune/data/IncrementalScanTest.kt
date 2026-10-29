package com.example.kitsune.data

import com.example.kitsune.data.entity.ChapterEntity
import com.example.kitsune.data.entity.ComicEntity
import com.example.kitsune.scanner.CbzCatalogResult
import com.example.kitsune.scanner.CbzPageEntry
import com.example.kitsune.scanner.ChapterDiscoveryResult
import com.example.kitsune.scanner.ComicFolderDiscoveryResult
import com.example.kitsune.scanner.DefaultChapterScanner
import com.example.kitsune.scanner.DefaultComicFolderScanner
import com.example.kitsune.scanner.DefaultComicsDirectoryDetector
import com.example.kitsune.scanner.DefaultLibraryScanner
import com.example.kitsune.scanner.DiscoveredChapter
import com.example.kitsune.scanner.DiscoveredComicFolder
import com.example.kitsune.scanner.IncrementalCbzCataloger
import com.example.kitsune.scanner.LibraryScanResult
import com.example.kitsune.scanner.ScannedChapter
import com.example.kitsune.scanner.ScannedComic
import com.example.kitsune.scanner.ScanSummary
import com.example.kitsune.storage.FakeStorageGateway
import com.example.kitsune.storage.StorageEntry
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for the incremental scan behaviour of [DefaultLibraryRepository].
 *
 * Two complementary testing layers:
 *
 * **Layer A — [IncrementalCbzCataloger] unit tests (criteria 1–8 of the decorator contract):**
 * Tests call [IncrementalCbzCataloger.catalogArchive] directly with controlled inputs and assert
 * whether the delegate cataloger is invoked or bypassed. These tests are the canonical proof that
 * CBZ I/O is actually skipped, not merely that DB writes are skipped.
 *
 * **Layer B — Integration tests with real scanner pipeline + [FakeStorageGateway] (criteria 9–16):**
 * Tests wire [DefaultLibraryRepository] → [DefaultLibraryScanner] → [IncrementalCbzCataloger]
 * → [TrackingCbzCataloger] and assert end-to-end behavior: first-scan cataloging, re-scan
 * skipping, cover preservation, identity stability, root isolation, and scan failure safety.
 *
 * Test criteria:
 *  1. First call: cataloger always delegates to real I/O (nothing cached yet).
 *  2. Unchanged chapter (matching fingerprint, wasValid=true): delegate NOT called (cache hit).
 *  3. Changed fileSize: delegate called (fingerprint mismatch).
 *  4. Changed lastModifiedMillis: delegate called (fingerprint mismatch).
 *  5. Previously-invalid chapter (wasValid=false): delegate always called (must re-catalog).
 *  6. Null scanned fileSize: delegate called (unknown metadata → conservative).
 *  7. Null scanned lastModifiedMillis: delegate called.
 *  8. Null cached fileSize or lastModifiedMillis: excluded from index → delegate called.
 *  9. Full integration: initial scan catalogs all chapters.
 * 10. Full integration: re-scan with unchanged metadata triggers zero catalog calls.
 * 11. Full integration: new chapter on re-scan is cataloged; unchanged chapter is not.
 * 12. Full integration: changed metadata on re-scan triggers catalog call for that chapter.
 * 13. Full integration: previously-invalid chapter always re-cataloged.
 * 14. Full integration: cover entry path preserved on incremental re-scan of Chapter 1.
 * 15. Full integration: root isolation — comics from different roots do not interfere.
 * 16. Full integration: scan failure does not trigger mass deletion.
 */
class IncrementalScanTest {

    // ── Layer A fixtures ──────────────────────────────────────────────────────

    private lateinit var trackingDelegate: TrackingCbzCataloger
    private lateinit var incrementalCataloger: IncrementalCbzCataloger

    // ── Layer B fixtures ──────────────────────────────────────────────────────

    private lateinit var fakeComicDao: FakeComicDao
    private lateinit var fakeChapterDao: FakeChapterDao
    private lateinit var fakeReadingProgressDao: FakeReadingProgressDao
    private lateinit var fakeStorage: FakeStorageGateway
    private lateinit var trackingCatalogerForIntegration: TrackingCbzCataloger
    private lateinit var incrementalForIntegration: IncrementalCbzCataloger
    private lateinit var realScanner: DefaultLibraryScanner
    private lateinit var repository: DefaultLibraryRepository

    private val rootUri = "content://com.android.externalstorage.documents/tree/primary%3AKitsune"
    private val rootUri2 = "content://com.android.externalstorage.documents/tree/primary%3AOther"

    /** A minimal valid CBZ: a ZIP containing one JPEG entry. */
    private val validCbzBytes: ByteArray by lazy { buildMinimalCbz("cover.jpg") }

    @Before
    fun setUp() {
        // Layer A
        trackingDelegate = TrackingCbzCataloger(defaultResult = CbzCatalogResult.Success(
            cbzRelativePath = "test.cbz",
            pages = listOf(CbzPageEntry("cover.jpg", 1))
        ))
        incrementalCataloger = IncrementalCbzCataloger(trackingDelegate)

        // Layer B
        fakeComicDao = FakeComicDao()
        fakeChapterDao = FakeChapterDao()
        fakeReadingProgressDao = FakeReadingProgressDao()
        fakeStorage = FakeStorageGateway(rootConfigured = true, permissionGranted = true)
        trackingCatalogerForIntegration = TrackingCbzCataloger(defaultResult = CbzCatalogResult.Error(
            IllegalStateException("TrackingCbzCataloger: no result set for this path")
        ))
        incrementalForIntegration = IncrementalCbzCataloger(trackingCatalogerForIntegration)

        val detector = DefaultComicsDirectoryDetector(fakeStorage)
        val folderScanner = DefaultComicFolderScanner(detector, fakeStorage)
        val chapterScanner = DefaultChapterScanner(fakeStorage)
        realScanner = DefaultLibraryScanner(folderScanner, chapterScanner, incrementalForIntegration)

        val coverExtractor = CoverExtractor(fakeStorage, fakeComicDao, fakeChapterDao)
        val coverCacheManager = CoverCacheManager(java.io.File(System.getProperty("java.io.tmpdir"), "inc_covers"), coverExtractor, fakeComicDao, fakeChapterDao)

        repository = DefaultLibraryRepository(
            libraryScanner = realScanner,
            incrementalCataloger = incrementalForIntegration,
            comicDao = fakeComicDao,
            chapterDao = fakeChapterDao,
            readingProgressDao = fakeReadingProgressDao,
            coverExtractor = coverExtractor,
            coverCacheManager = coverCacheManager
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // LAYER A — IncrementalCbzCataloger unit tests (criteria 1–8)
    // ═══════════════════════════════════════════════════════════════════════

    // 1. First call: always delegates (nothing cached)
    @Test
    fun catalogArchive_emptyIndex_alwaysDelegates() {
        incrementalCataloger.updateIndex(emptyMap())
        val entry = StorageEntry("ch1.cbz", "Comics/OP/ch1.cbz", false, 1000L, 100L)

        incrementalCataloger.catalogArchive("Comics/OP/ch1.cbz", entry)

        assertEquals("First call must delegate to real cataloger", 1, trackingDelegate.callCount)
    }

    // 2. Matching fingerprint, wasValid=true → cache hit, delegate NOT called
    @Test
    fun catalogArchive_matchingFingerprint_doesNotDelegate() {
        val entry = StorageEntry("ch1.cbz", "Comics/OP/ch1.cbz", false, 1000L, 100L)
        val cached = IncrementalCbzCataloger.CachedEntry(
            fileSize = 1000L, lastModifiedMillis = 100L, wasValid = true,
            cachedResult = CbzCatalogResult.Success("Comics/OP/ch1.cbz", listOf(CbzPageEntry("cover.jpg", 1)))
        )
        incrementalCataloger.updateIndex(mapOf("Comics/OP/ch1.cbz" to cached))

        val result = incrementalCataloger.catalogArchive("Comics/OP/ch1.cbz", entry)

        assertEquals("Matching fingerprint: delegate must NOT be called", 0, trackingDelegate.callCount)
        assertTrue("Cached result must be returned", result is CbzCatalogResult.Success)
        assertEquals("Comics/OP/ch1.cbz", (result as CbzCatalogResult.Success).cbzRelativePath)
    }

    // 3. Changed fileSize → fingerprint mismatch → delegate called
    @Test
    fun catalogArchive_changedFileSize_delegates() {
        val entry = StorageEntry("ch1.cbz", "Comics/OP/ch1.cbz", false, 1500L, 100L) // size changed
        val cached = IncrementalCbzCataloger.CachedEntry(
            fileSize = 1000L, lastModifiedMillis = 100L, wasValid = true,
            cachedResult = CbzCatalogResult.Success("Comics/OP/ch1.cbz", emptyList())
        )
        incrementalCataloger.updateIndex(mapOf("Comics/OP/ch1.cbz" to cached))

        incrementalCataloger.catalogArchive("Comics/OP/ch1.cbz", entry)

        assertEquals("Changed fileSize: delegate must be called", 1, trackingDelegate.callCount)
    }

    // 4. Changed lastModifiedMillis → delegate called
    @Test
    fun catalogArchive_changedLastModified_delegates() {
        val entry = StorageEntry("ch1.cbz", "Comics/OP/ch1.cbz", false, 1000L, 999L) // ts changed
        val cached = IncrementalCbzCataloger.CachedEntry(
            fileSize = 1000L, lastModifiedMillis = 100L, wasValid = true,
            cachedResult = CbzCatalogResult.Success("Comics/OP/ch1.cbz", emptyList())
        )
        incrementalCataloger.updateIndex(mapOf("Comics/OP/ch1.cbz" to cached))

        incrementalCataloger.catalogArchive("Comics/OP/ch1.cbz", entry)

        assertEquals("Changed lastModified: delegate must be called", 1, trackingDelegate.callCount)
    }

    // 5. wasValid=false → always re-catalogs
    @Test
    fun catalogArchive_previouslyInvalid_alwaysDelegates() {
        val entry = StorageEntry("ch1.cbz", "Comics/OP/ch1.cbz", false, 1000L, 100L)
        val cached = IncrementalCbzCataloger.CachedEntry(
            fileSize = 1000L, lastModifiedMillis = 100L, wasValid = false,
            cachedResult = CbzCatalogResult.CorruptArchive("Comics/OP/ch1.cbz", "corrupt")
        )
        incrementalCataloger.updateIndex(mapOf("Comics/OP/ch1.cbz" to cached))

        incrementalCataloger.catalogArchive("Comics/OP/ch1.cbz", entry)

        assertEquals("wasValid=false: delegate must be called (repaired file must be detected)", 1, trackingDelegate.callCount)
    }

    // 6. Null scanned fileSize → conservative: delegate called
    @Test
    fun catalogArchive_nullScannedFileSize_delegates() {
        val entry = StorageEntry("ch1.cbz", "Comics/OP/ch1.cbz", false, null, 100L)
        val cached = IncrementalCbzCataloger.CachedEntry(
            fileSize = 1000L, lastModifiedMillis = 100L, wasValid = true,
            cachedResult = CbzCatalogResult.Success("Comics/OP/ch1.cbz", emptyList())
        )
        incrementalCataloger.updateIndex(mapOf("Comics/OP/ch1.cbz" to cached))

        incrementalCataloger.catalogArchive("Comics/OP/ch1.cbz", entry)

        assertEquals("Null scanned fileSize: delegate must be called", 1, trackingDelegate.callCount)
    }

    // 7. Null scanned lastModifiedMillis → delegate called
    @Test
    fun catalogArchive_nullScannedLastModified_delegates() {
        val entry = StorageEntry("ch1.cbz", "Comics/OP/ch1.cbz", false, 1000L, null)
        val cached = IncrementalCbzCataloger.CachedEntry(
            fileSize = 1000L, lastModifiedMillis = 100L, wasValid = true,
            cachedResult = CbzCatalogResult.Success("Comics/OP/ch1.cbz", emptyList())
        )
        incrementalCataloger.updateIndex(mapOf("Comics/OP/ch1.cbz" to cached))

        incrementalCataloger.catalogArchive("Comics/OP/ch1.cbz", entry)

        assertEquals("Null scanned lastModified: delegate must be called", 1, trackingDelegate.callCount)
    }

    // 8. Null fileMetadata → delegate called (no metadata = no fingerprint)
    @Test
    fun catalogArchive_nullFileMetadata_alwaysDelegates() {
        val cached = IncrementalCbzCataloger.CachedEntry(
            fileSize = 1000L, lastModifiedMillis = 100L, wasValid = true,
            cachedResult = CbzCatalogResult.Success("Comics/OP/ch1.cbz", emptyList())
        )
        incrementalCataloger.updateIndex(mapOf("Comics/OP/ch1.cbz" to cached))

        incrementalCataloger.catalogArchive("Comics/OP/ch1.cbz", fileMetadata = null)

        assertEquals("Null fileMetadata: delegate must be called", 1, trackingDelegate.callCount)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // LAYER B — Integration tests with real scanner pipeline (criteria 9–16)
    // ═══════════════════════════════════════════════════════════════════════

    // 9. Initial scan catalogs all chapters
    @Test
    fun integration_initialScan_catalogsAllChapters() = runTest {
        setupStorageWithComic("Comics/OP", mapOf(
            "Comics/OP/Chapter 1.cbz" to validCbzBytes,
            "Comics/OP/Chapter 2.cbz" to validCbzBytes
        ))
        trackingCatalogerForIntegration.setResult("Comics/OP/Chapter 1.cbz", successResult("Comics/OP/Chapter 1.cbz"))
        trackingCatalogerForIntegration.setResult("Comics/OP/Chapter 2.cbz", successResult("Comics/OP/Chapter 2.cbz"))

        repository.syncLibrary(rootUri)

        assertEquals("Initial scan: all 2 chapters must invoke the real cataloger", 2, trackingCatalogerForIntegration.callCount)
        assertEquals(2, fakeChapterDao.chapters.size)
    }

    // 10. Re-scan with unchanged metadata triggers zero catalog calls
    @Test
    fun integration_rescan_unchangedChapters_noCatalogCalls() = runTest {
        setupStorageWithComic("Comics/OP", mapOf(
            "Comics/OP/Chapter 1.cbz" to validCbzBytes
        ), sizeBytes = 512L, lastModifiedMillis = 42L)
        trackingCatalogerForIntegration.setResult("Comics/OP/Chapter 1.cbz", successResult("Comics/OP/Chapter 1.cbz", coverPath = "cover.jpg"))

        repository.syncLibrary(rootUri)
        val firstScanCalls = trackingCatalogerForIntegration.callCount
        assertEquals("First scan must catalog the chapter", 1, firstScanCalls)

        // Re-scan — same file metadata
        repository.syncLibrary(rootUri)

        assertEquals(
            "Re-scan with identical metadata: zero additional catalog calls expected",
            firstScanCalls,
            trackingCatalogerForIntegration.callCount
        )
        // Chapter still in DB
        assertEquals(1, fakeChapterDao.chapters.size)
    }

    // 11. New chapter on re-scan is cataloged; unchanged chapter is not
    @Test
    fun integration_rescan_newChapterCataloged_existingSkipped() = runTest {
        setupStorageWithComic("Comics/OP", mapOf(
            "Comics/OP/Chapter 1.cbz" to validCbzBytes
        ), sizeBytes = 512L, lastModifiedMillis = 10L)
        trackingCatalogerForIntegration.setResult("Comics/OP/Chapter 1.cbz", successResult("Comics/OP/Chapter 1.cbz"))

        repository.syncLibrary(rootUri)
        val firstScanCalls = trackingCatalogerForIntegration.callCount

        // Add Chapter 2 to storage
        fakeStorage.addFile("Comics/OP/Chapter 2.cbz", validCbzBytes, sizeBytes = 1024L, lastModifiedMillis = 20L)
        trackingCatalogerForIntegration.setResult("Comics/OP/Chapter 2.cbz", successResult("Comics/OP/Chapter 2.cbz"))

        repository.syncLibrary(rootUri)

        assertEquals(
            "Re-scan: only Chapter 2 (new) should be cataloged; Chapter 1 (unchanged) must be skipped",
            firstScanCalls + 1,
            trackingCatalogerForIntegration.callCount
        )
        assertEquals(2, fakeChapterDao.chapters.size)
    }

    // 12. Changed metadata triggers catalog call for that chapter
    @Test
    fun integration_rescan_changedMetadata_triggersCatalog() = runTest {
        setupStorageWithComic("Comics/OP", mapOf(
            "Comics/OP/Chapter 1.cbz" to validCbzBytes
        ), sizeBytes = 512L, lastModifiedMillis = 10L)
        trackingCatalogerForIntegration.setResult("Comics/OP/Chapter 1.cbz", successResult("Comics/OP/Chapter 1.cbz"))

        repository.syncLibrary(rootUri)
        val firstScanCalls = trackingCatalogerForIntegration.callCount

        // Simulate file modification: update storage entry metadata
        fakeStorage.updateFileMetadata("Comics/OP/Chapter 1.cbz", sizeBytes = 1024L, lastModifiedMillis = 99L)
        trackingCatalogerForIntegration.setResult("Comics/OP/Chapter 1.cbz", successResult("Comics/OP/Chapter 1.cbz", pageCount = 5))

        repository.syncLibrary(rootUri)

        assertEquals(
            "Changed metadata: chapter must be re-cataloged",
            firstScanCalls + 1,
            trackingCatalogerForIntegration.callCount
        )
        // Page count updated in DB
        val chapter = fakeChapterDao.chapters.values.first()
        assertEquals("Updated pageCount must be reflected in DB", 5, chapter.pageCount)
    }

    // 13. Previously-invalid chapter always re-cataloged
    @Test
    fun integration_rescan_previouslyInvalidChapter_alwaysRecataloged() = runTest {
        setupStorageWithComic("Comics/OP", mapOf(
            "Comics/OP/Chapter 1.cbz" to validCbzBytes
        ), sizeBytes = 512L, lastModifiedMillis = 10L)
        // First scan: corrupt result
        trackingCatalogerForIntegration.setResult("Comics/OP/Chapter 1.cbz",
            CbzCatalogResult.CorruptArchive("Comics/OP/Chapter 1.cbz", "bad zip"))

        repository.syncLibrary(rootUri)
        val firstScanCalls = trackingCatalogerForIntegration.callCount
        assertFalse("Chapter must be stored as invalid", fakeChapterDao.chapters.values.first().isValid)

        // Second scan: same metadata, but now file is repaired
        trackingCatalogerForIntegration.setResult("Comics/OP/Chapter 1.cbz",
            successResult("Comics/OP/Chapter 1.cbz", coverPath = "cover.jpg"))

        repository.syncLibrary(rootUri)

        assertEquals(
            "Previously-invalid chapter: must be re-cataloged even if metadata matches",
            firstScanCalls + 1,
            trackingCatalogerForIntegration.callCount
        )
        assertTrue("Repaired chapter must now be stored as valid", fakeChapterDao.chapters.values.first().isValid)
    }

    // 14. Cover entry path preserved on incremental re-scan of Chapter 1
    @Test
    fun integration_rescan_coverEntryPathPreserved() = runTest {
        setupStorageWithComic("Comics/OP", mapOf(
            "Comics/OP/Chapter 1.cbz" to validCbzBytes
        ), sizeBytes = 512L, lastModifiedMillis = 10L)
        val coverPath = "chapter_1_cover.jpg"
        trackingCatalogerForIntegration.setResult(
            "Comics/OP/Chapter 1.cbz",
            CbzCatalogResult.Success("Comics/OP/Chapter 1.cbz", listOf(CbzPageEntry(coverPath, 1)))
        )

        repository.syncLibrary(rootUri)
        val coverAfterFirst = fakeComicDao.comics.values.first().coverEntryPath
        assertEquals("Cover must be set after first scan", coverPath, coverAfterFirst)
        val callsAfterFirst = trackingCatalogerForIntegration.callCount

        // Re-scan: same metadata → Chapter 1 must be skipped (no CBZ I/O)
        repository.syncLibrary(rootUri)

        assertEquals("Re-scan: no additional catalog calls", callsAfterFirst, trackingCatalogerForIntegration.callCount)
        val coverAfterRescan = fakeComicDao.comics.values.first().coverEntryPath
        assertEquals("Cover entry path must be preserved on incremental re-scan", coverPath, coverAfterRescan)
    }

    // 15. Root isolation
    @Test
    fun integration_rootIsolation_comicsDoNotMix() = runTest {
        setupStorageWithComic("Comics/A", mapOf(
            "Comics/A/Chapter 1.cbz" to validCbzBytes
        ), sizeBytes = 100L, lastModifiedMillis = 1L)
        trackingCatalogerForIntegration.setResult("Comics/A/Chapter 1.cbz", successResult("Comics/A/Chapter 1.cbz"))

        repository.syncLibrary(rootUri)

        // A second root synced independently should not see root1 data
        val root1Comics = repository.observeComicsByRoot(rootUri).first()
        val root2Comics = repository.observeComicsByRoot(rootUri2).first()

        assertEquals(1, root1Comics.size)
        assertEquals(0, root2Comics.size)
        assertEquals("Comics/A", root1Comics[0].relativePath)
    }

    // 15b. Root isolation: identical relativePath, fileSize, and lastModified across two roots
    // Root 2 must NOT use cache populated from Root 1 — index priming must isolate roots
    @Test
    fun integration_rootIsolation_identicalFilesDoNotShareCache() = runTest {
        // Set up Comic A with Chapter 1 in storage
        setupStorageWithComic("Comics/A", mapOf(
            "Comics/A/Chapter 1.cbz" to validCbzBytes
        ), sizeBytes = 100L, lastModifiedMillis = 1L)
        trackingCatalogerForIntegration.setResult("Comics/A/Chapter 1.cbz", successResult("Comics/A/Chapter 1.cbz"))

        // Sync Root 1: this primes cache for Root 1 (initially empty) and catalogs Chapter 1
        repository.syncLibrary(rootUri)
        val callsAfterRoot1 = trackingCatalogerForIntegration.callCount
        assertEquals("Root 1 initial scan must catalog Chapter 1", 1, callsAfterRoot1)

        // Sync Root 2: Root 2 has identical relativePath, fileSize, and timestamp in storage,
        // but Root 2's DB has NO indexed chapters yet!
        // primeIncrementalIndex(rootUri2) must clear/replace index with Root 2's data (empty),
        // so Root 2 must NOT hit the cache from Root 1 and MUST invoke the cataloger delegate.
        repository.syncLibrary(rootUri2)
        assertEquals(
            "Root 2 must NOT use Root 1 cache even if path and metadata match identical files; delegate must be called",
            callsAfterRoot1 + 1,
            trackingCatalogerForIntegration.callCount
        )

        // Now re-sync Root 1: primeIncrementalIndex(rootUri) re-arms with Root 1's DB data,
        // and because files are unchanged, it must hit the cache with 0 additional catalog calls.
        repository.syncLibrary(rootUri)
        assertEquals(
            "Re-sync Root 1: index re-armed for Root 1, should skip cataloging",
            callsAfterRoot1 + 1,
            trackingCatalogerForIntegration.callCount
        )
    }

    // 16. Scan failure does not delete existing chapters
    @Test
    fun integration_scanFailure_doesNotDeleteChapters() = runTest {
        setupStorageWithComic("Comics/OP", mapOf(
            "Comics/OP/Chapter 1.cbz" to validCbzBytes
        ), sizeBytes = 512L, lastModifiedMillis = 10L)
        trackingCatalogerForIntegration.setResult("Comics/OP/Chapter 1.cbz", successResult("Comics/OP/Chapter 1.cbz"))

        repository.syncLibrary(rootUri)
        assertEquals(1, fakeChapterDao.chapters.size)

        // Simulate storage permission revoked
        fakeStorage.permissionGranted = false

        repository.syncLibrary(rootUri)

        assertEquals("Existing chapters must survive a failed scan", 1, fakeChapterDao.chapters.size)
        fakeStorage.permissionGranted = true
    }

    // 17. Chapter 1 without trusted cover in DB is NEVER served from cache; re-catalogs via delegate
    @Test
    fun integration_rescan_chapter1WithoutTrustedCover_reCatalogsViaDelegate() = runTest {
        setupStorageWithComic("Comics/OP", mapOf(
            "Comics/OP/Chapter 1.cbz" to validCbzBytes
        ), sizeBytes = 512L, lastModifiedMillis = 10L)
        trackingCatalogerForIntegration.setResult(
            "Comics/OP/Chapter 1.cbz",
            successResult("Comics/OP/Chapter 1.cbz", coverPath = "real_cover.jpg")
        )

        // Initial scan
        repository.syncLibrary(rootUri)
        val initialCalls = trackingCatalogerForIntegration.callCount
        assertEquals(1, initialCalls)
        val comic = fakeComicDao.comics.values.first()
        assertEquals("real_cover.jpg", comic.coverEntryPath)

        // Corrupt/erase the coverEntryPath in the DB to simulate missing/untrusted cover metadata
        fakeComicDao.comics[comic.id] = comic.copy(coverEntryPath = null)

        // Re-scan: Even though fileSize and lastModified match identical files,
        // Chapter 1 has no trusted cover in the DB, so it MUST NOT be served from cache.
        // It must invoke the delegate cataloger to recover the genuine cover entry path.
        repository.syncLibrary(rootUri)

        assertEquals(
            "Chapter 1 with missing cover metadata must be re-cataloged by the delegate",
            initialCalls + 1,
            trackingCatalogerForIntegration.callCount
        )
        // Genuine cover recovered
        val recoveredComic = fakeComicDao.comics.values.first()
        assertEquals("real_cover.jpg", recoveredComic.coverEntryPath)
    }

    // 18. Concurrent root scans: Mutex serializes syncLibrary so cache is never clobbered
    @Test
    fun integration_concurrentRootScans_areSerializedByMutex() = runTest {
        setupStorageWithComic("Comics/A", mapOf(
            "Comics/A/Chapter 1.cbz" to validCbzBytes
        ), sizeBytes = 100L, lastModifiedMillis = 1L)
        trackingCatalogerForIntegration.setResult("Comics/A/Chapter 1.cbz", successResult("Comics/A/Chapter 1.cbz"))

        // Launch concurrent scans for Root 1 and Root 2 simultaneously
        // Note: fakeStorage represents the currently configured storage root.
        // When syncLibrary is called with rootUri vs rootUri2, the repository maps
        // the comics to the respective rootUriString.
        val deferred1 = async { repository.syncLibrary(rootUri) }
        val deferred2 = async { repository.syncLibrary(rootUri2) }

        val result1 = deferred1.await()
        val result2 = deferred2.await()

        assertTrue(result1 is LibraryScanResult.Success)
        assertTrue(result2 is LibraryScanResult.Success)

        // Both operations completed cleanly through the serialized syncMutex without deadlock or crash.
        // Each root indexed Comic A independently under its own rootUri:
        val root1Comics = repository.observeComicsByRoot(rootUri).first()
        val root2Comics = repository.observeComicsByRoot(rootUri2).first()
        assertEquals(1, root1Comics.size)
        assertEquals(1, root2Comics.size)
        assertEquals("Comics/A", root1Comics[0].relativePath)
        assertEquals("Comics/A", root2Comics[0].relativePath)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // buildSynthesizedSuccess unit tests
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun buildSynthesizedSuccess_withCoverPath_setsFirstPageEntry() {
        val ext = CoverExtractor(FakeStorageGateway(), FakeComicDao(), FakeChapterDao())
        val cache = CoverCacheManager(java.io.File(System.getProperty("java.io.tmpdir"), "stub_covers"), ext, FakeComicDao(), FakeChapterDao())
        val repo = DefaultLibraryRepository(
            libraryScanner = FakeLibraryScanner(),
            incrementalCataloger = IncrementalCbzCataloger(NoopCbzCataloger),
            comicDao = FakeComicDao(),
            chapterDao = FakeChapterDao(),
            readingProgressDao = FakeReadingProgressDao(),
            coverExtractor = ext,
            coverCacheManager = cache
        )
        val result = repo.buildSynthesizedSuccess("path.cbz", pageCount = 3, coverEntryPath = "my_cover.jpg")

        assertEquals(3, result.pages.size)
        assertEquals("my_cover.jpg", result.pages[0].entryPath)
        assertEquals("stub_002.jpg", result.pages[1].entryPath)
    }

    @Test
    fun buildSynthesizedSuccess_noCoverPath_usesStubs() {
        val ext = CoverExtractor(FakeStorageGateway(), FakeComicDao(), FakeChapterDao())
        val cache = CoverCacheManager(java.io.File(System.getProperty("java.io.tmpdir"), "stub_covers"), ext, FakeComicDao(), FakeChapterDao())
        val repo = DefaultLibraryRepository(
            libraryScanner = FakeLibraryScanner(),
            incrementalCataloger = IncrementalCbzCataloger(NoopCbzCataloger),
            comicDao = FakeComicDao(),
            chapterDao = FakeChapterDao(),
            readingProgressDao = FakeReadingProgressDao(),
            coverExtractor = ext,
            coverCacheManager = cache
        )
        val result = repo.buildSynthesizedSuccess("path.cbz", pageCount = 2, coverEntryPath = null)

        assertEquals(2, result.pages.size)
        assertEquals("stub_001.jpg", result.pages[0].entryPath)
        assertEquals("stub_002.jpg", result.pages[1].entryPath)
    }

    @Test
    fun buildSynthesizedSuccess_zeroPageCount_returnsEmptyPages() {
        val ext = CoverExtractor(FakeStorageGateway(), FakeComicDao(), FakeChapterDao())
        val cache = CoverCacheManager(java.io.File(System.getProperty("java.io.tmpdir"), "stub_covers"), ext, FakeComicDao(), FakeChapterDao())
        val repo = DefaultLibraryRepository(
            libraryScanner = FakeLibraryScanner(),
            incrementalCataloger = IncrementalCbzCataloger(NoopCbzCataloger),
            comicDao = FakeComicDao(),
            chapterDao = FakeChapterDao(),
            readingProgressDao = FakeReadingProgressDao(),
            coverExtractor = ext,
            coverCacheManager = cache
        )
        val result = repo.buildSynthesizedSuccess("path.cbz", pageCount = 0, coverEntryPath = null)
        assertEquals(0, result.pages.size)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════

    private fun successResult(
        path: String,
        pageCount: Int = 1,
        coverPath: String = "cover.jpg"
    ): CbzCatalogResult.Success = CbzCatalogResult.Success(
        cbzRelativePath = path,
        pages = (1..pageCount).map { i ->
            CbzPageEntry(if (i == 1) coverPath else "page_${i.toString().padStart(3, '0')}.jpg", i)
        }
    )

    private fun setupStorageWithComic(
        comicRelativePath: String,
        files: Map<String, ByteArray>,
        sizeBytes: Long = 512L,
        lastModifiedMillis: Long = 1000L
    ) {
        fakeStorage.addDirectory("Comics")
        fakeStorage.addDirectory(comicRelativePath)
        for ((path, bytes) in files) {
            fakeStorage.addFile(path, bytes, sizeBytes = sizeBytes, lastModifiedMillis = lastModifiedMillis)
        }
    }

    /** Builds a minimal but structurally-valid ZIP containing one entry. */
    private fun buildMinimalCbz(entryName: String): ByteArray {
        val baos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry(entryName))
            zos.write("fake image bytes".toByteArray())
            zos.closeEntry()
        }
        return baos.toByteArray()
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// TrackingCbzCataloger — tracks catalogArchive() invocations
// ═══════════════════════════════════════════════════════════════════════════

/**
 * A [com.example.kitsune.scanner.CbzCataloger] that records every [catalogArchive] call.
 *
 * Configurable per-path results via [setResult]. The default result is supplied at construction.
 * Used to assert whether the [IncrementalCbzCataloger] actually bypasses CBZ I/O (callCount
 * stays the same) or delegates to this real cataloger (callCount increments).
 */
class TrackingCbzCataloger(
    private val defaultResult: CbzCatalogResult
) : com.example.kitsune.scanner.CbzCataloger {

    var callCount: Int = 0
        private set

    private val results = mutableMapOf<String, CbzCatalogResult>()

    fun setResult(path: String, result: CbzCatalogResult) {
        results[path] = result
    }

    override fun catalogArchive(
        cbzRelativePath: String,
        fileMetadata: StorageEntry?
    ): CbzCatalogResult {
        callCount++
        return results[cbzRelativePath] ?: defaultResult
    }
}
