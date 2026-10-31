package com.example.kitsune.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.kitsune.data.dao.ChapterDao
import com.example.kitsune.data.dao.ComicDao
import com.example.kitsune.data.dao.ReadingProgressDao
import com.example.kitsune.data.entity.ChapterEntity
import com.example.kitsune.data.entity.ComicEntity
import com.example.kitsune.data.entity.ReadingProgressEntity
import com.example.kitsune.scanner.CbzCatalogResult
import com.example.kitsune.scanner.CbzPageEntry
import com.example.kitsune.scanner.ChapterDiscoveryResult
import com.example.kitsune.scanner.DiscoveredChapter
import com.example.kitsune.scanner.DiscoveredComicFolder
import com.example.kitsune.scanner.IncrementalCbzCataloger
import com.example.kitsune.scanner.LibraryScanner
import com.example.kitsune.scanner.LibraryScanResult
import com.example.kitsune.scanner.RejectedChapterFile
import com.example.kitsune.scanner.ScannedChapter
import com.example.kitsune.scanner.ScannedComic
import com.example.kitsune.scanner.ScanSummary
import com.example.kitsune.storage.FakeStorageGateway
import com.example.kitsune.storage.StorageEntry
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LibraryRepositoryTest {

    private lateinit var db: KitsuneDatabase
    private lateinit var fakeComicDao: FakeComicDao
    private lateinit var fakeChapterDao: FakeChapterDao
    private lateinit var fakeReadingProgressDao: FakeReadingProgressDao
    private lateinit var fakeScanner: FakeLibraryScanner
    private lateinit var repository: DefaultLibraryRepository

    private val rootUri = "content://com.android.externalstorage.documents/tree/primary%3AKitsune"
    private val rootUri2 = "content://com.android.externalstorage.documents/tree/primary%3AOther"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, KitsuneDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        fakeComicDao = FakeComicDao()
        fakeChapterDao = FakeChapterDao()
        fakeReadingProgressDao = FakeReadingProgressDao()
        fakeScanner = FakeLibraryScanner()
        // IncrementalCbzCataloger wraps a no-op cataloger: LibraryRepositoryTest uses
        // FakeLibraryScanner which returns pre-built ScannedChapters, so the cataloger
        // is never invoked. Passing a real IncrementalCbzCataloger satisfies the constructor.
        val noopCataloger = IncrementalCbzCataloger(NoopCbzCataloger)
        val fakeStorage = FakeStorageGateway()
        val coverExtractor = CoverExtractor(fakeStorage, fakeComicDao, fakeChapterDao)
        val coverCacheManager = CoverCacheManager(
            File(System.getProperty("java.io.tmpdir"), "test_repo_covers"),
            coverExtractor, fakeComicDao, fakeChapterDao
        )
        val cbzProvider = com.example.kitsune.reader.DefaultCbzArchiveProvider(
            spoolDir = File(System.getProperty("java.io.tmpdir"), "test_repo_spool"),
            storageGateway = fakeStorage,
            chapterDao = fakeChapterDao
        )
        repository = DefaultLibraryRepository(
            fakeScanner, noopCataloger, fakeComicDao, fakeChapterDao, fakeReadingProgressDao, coverExtractor, coverCacheManager, fakeStorage, cbzProvider, db
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 1. Valid scan — comic and chapters are mapped and saved correctly
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun syncLibrary_successResult_insertsComicAndChapters() = runTest {
        fakeScanner.result = makeSuccessResult(
            comics = listOf(
                makeScannedComic(
                    relativePath = "Comics/One Piece",
                    title = "One Piece",
                    chapters = listOf(
                        makeScannedChapter(chapterNumber = 1, pageCount = 20),
                        makeScannedChapter(chapterNumber = 2, pageCount = 15)
                    )
                )
            )
        )

        val result = repository.syncLibrary(rootUri)

        assertTrue(result is LibraryScanResult.Success)
        assertEquals(1, fakeComicDao.comics.size)
        val comic = fakeComicDao.comics.values.first()
        assertEquals("One Piece", comic.title)
        assertEquals("Comics/One Piece", comic.relativePath)
        assertEquals(rootUri, comic.rootUriString)

        assertEquals(2, fakeChapterDao.chapters.size)
        val chapters = fakeChapterDao.chapters.values.sortedBy { it.chapterNumber }
        assertEquals(1, chapters[0].chapterNumber)
        assertEquals(2, chapters[1].chapterNumber)
        assertEquals(20, chapters[0].pageCount)
        assertEquals(15, chapters[1].pageCount)
    }

    @Test
    fun syncLibrary_successResult_chaptersLinkedToCorrectComic() = runTest {
        fakeScanner.result = makeSuccessResult(
            comics = listOf(
                makeScannedComic(
                    relativePath = "Comics/OP",
                    chapters = listOf(makeScannedChapter(1), makeScannedChapter(2))
                ),
                makeScannedComic(
                    relativePath = "Comics/Naruto",
                    chapters = listOf(makeScannedChapter(1))
                )
            )
        )

        repository.syncLibrary(rootUri)

        assertEquals(2, fakeComicDao.comics.size)
        // All chapters must reference an existing comic
        for (chapter in fakeChapterDao.chapters.values) {
            assertTrue(fakeComicDao.comics.containsKey(chapter.comicId))
        }
        // 3 total chapters
        assertEquals(3, fakeChapterDao.chapters.size)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 2. Re-scan preserves user state
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun syncLibrary_rescan_preservesBookmarkAndLastReadAt() = runTest {
        // First scan — insert comic
        fakeScanner.result = makeSuccessResult(
            listOf(makeScannedComic("Comics/OP", chapters = listOf(makeScannedChapter(1))))
        )
        repository.syncLibrary(rootUri)

        val comicId = fakeComicDao.comics.values.first().id
        // Simulate user bookmark and read
        fakeComicDao.setBookmarked(comicId, true)
        fakeComicDao.updateLastReadAt(comicId, 12345L)

        // Second scan — same comic, new scan data
        fakeScanner.result = makeSuccessResult(
            listOf(makeScannedComic("Comics/OP", title = "OP Rescanned", chapters = listOf(makeScannedChapter(1))))
        )
        repository.syncLibrary(rootUri)

        val updated = fakeComicDao.comics[comicId]!!
        assertEquals("OP Rescanned", updated.title)
        assertTrue("isBookmarked must be preserved", updated.isBookmarked)
        assertEquals("lastReadAt must be preserved", 12345L, updated.lastReadAt)
    }

    @Test
    fun syncLibrary_rescan_preservesChapterId_forReadingProgressStability() = runTest {
        fakeScanner.result = makeSuccessResult(
            listOf(makeScannedComic("Comics/OP", chapters = listOf(makeScannedChapter(1))))
        )
        repository.syncLibrary(rootUri)
        val firstChapterId = fakeChapterDao.chapters.values.first().id

        // Re-scan same chapter with changed metadata (lastModifiedMillis = 1L) to trigger re-sync.
        // The incremental logic skips the upsert only when metadata is identical; a changed
        // lastModifiedMillis signals that the file was modified, so the updated pageCount must be
        // persisted while the chapter ID must remain stable for ReadingProgress FK integrity.
        fakeScanner.result = makeSuccessResult(
            listOf(makeScannedComic("Comics/OP", chapters = listOf(makeScannedChapter(1, pageCount = 99, lastModifiedMillis = 1L))))
        )
        repository.syncLibrary(rootUri)

        val updatedChapterId = fakeChapterDao.chapters.values.first().id
        assertEquals("Chapter ID must not change on re-scan", firstChapterId, updatedChapterId)

        val updatedChapter = fakeChapterDao.chapters.values.first()
        assertEquals("Page count must be updated", 99, updatedChapter.pageCount)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 3. Root isolation
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun syncLibrary_differentRoots_doNotMix() = runTest {
        fakeScanner.result = makeSuccessResult(listOf(makeScannedComic("Comics/A")))
        repository.syncLibrary(rootUri)

        fakeScanner.result = makeSuccessResult(listOf(makeScannedComic("Comics/B")))
        repository.syncLibrary(rootUri2)

        val root1Comics = repository.observeComicsByRoot(rootUri).first()
        val root2Comics = repository.observeComicsByRoot(rootUri2).first()

        assertEquals(1, root1Comics.size)
        assertEquals(1, root2Comics.size)
        assertEquals("Comics/A", root1Comics[0].relativePath)
        assertEquals("Comics/B", root2Comics[0].relativePath)
        assertTrue("Comics from different roots must not overlap",
            root1Comics.none { it.rootUriString == rootUri2 })
        assertTrue(root2Comics.none { it.rootUriString == rootUri })
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 4. Chapter ordering
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun observeChaptersByComicId_returnsChaptersOrderedByChapterNumber() = runTest {
        fakeScanner.result = makeSuccessResult(
            listOf(
                makeScannedComic(
                    "Comics/OP",
                    chapters = listOf(
                        makeScannedChapter(10),
                        makeScannedChapter(2),
                        makeScannedChapter(1)
                    )
                )
            )
        )
        repository.syncLibrary(rootUri)

        val comicId = fakeComicDao.comics.values.first().id
        val chapters = repository.observeChaptersByComicId(comicId).first()
        assertEquals(listOf(1, 2, 10), chapters.map { it.chapterNumber })
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 5. Failed/partial scan does NOT delete existing data
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun syncLibrary_errorResult_doesNotDeleteExistingComics() = runTest {
        // Populate database via a successful scan
        fakeScanner.result = makeSuccessResult(listOf(makeScannedComic("Comics/A")))
        repository.syncLibrary(rootUri)
        assertEquals(1, fakeComicDao.comics.size)

        // Subsequent scan fails with I/O error
        fakeScanner.result = LibraryScanResult.Error(RuntimeException("I/O error"))
        val result = repository.syncLibrary(rootUri)

        assertTrue(result is LibraryScanResult.Error)
        assertEquals("Existing comics must survive a failed scan", 1, fakeComicDao.comics.size)
    }

    @Test
    fun syncLibrary_permissionDenied_doesNotDeleteExistingComics() = runTest {
        fakeScanner.result = makeSuccessResult(listOf(makeScannedComic("Comics/A")))
        repository.syncLibrary(rootUri)

        fakeScanner.result = LibraryScanResult.PermissionDenied("Permission revoked")
        repository.syncLibrary(rootUri)

        assertEquals(1, fakeComicDao.comics.size)
    }

    @Test
    fun syncLibrary_rootNotConfigured_doesNotDeleteExistingComics() = runTest {
        fakeScanner.result = makeSuccessResult(listOf(makeScannedComic("Comics/A")))
        repository.syncLibrary(rootUri)

        fakeScanner.result = LibraryScanResult.RootNotConfigured
        repository.syncLibrary(rootUri)

        assertEquals(1, fakeComicDao.comics.size)
    }

    @Test
    fun syncLibrary_comicsDirectoryNotFound_doesNotDeleteExistingComics() = runTest {
        fakeScanner.result = makeSuccessResult(listOf(makeScannedComic("Comics/A")))
        repository.syncLibrary(rootUri)

        fakeScanner.result = LibraryScanResult.ComicsDirectoryNotFound
        repository.syncLibrary(rootUri)

        assertEquals(1, fakeComicDao.comics.size)
    }

    @Test
    fun syncLibrary_emptyComicsDirectory_doesNotDeleteExistingComics() = runTest {
        fakeScanner.result = makeSuccessResult(listOf(makeScannedComic("Comics/A")))
        repository.syncLibrary(rootUri)

        fakeScanner.result = LibraryScanResult.EmptyComicsDirectory
        repository.syncLibrary(rootUri)

        assertEquals(1, fakeComicDao.comics.size)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 6. Cover sourced from Chapter 1 only (K-07)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun syncLibrary_coverEntryPath_fromChapter1Only() = runTest {
        val chapter1 = makeScannedChapter(
            chapterNumber = 1,
            catalogResult = CbzCatalogResult.Success(
                cbzRelativePath = "Comics/OP/Chapter 1.cbz",
                pages = listOf(CbzPageEntry(entryPath = "page_001.jpg", pageNumber = 1))
            )
        )
        val chapter2 = makeScannedChapter(
            chapterNumber = 2,
            catalogResult = CbzCatalogResult.Success(
                cbzRelativePath = "Comics/OP/Chapter 2.cbz",
                pages = listOf(CbzPageEntry(entryPath = "ch2_page_001.jpg", pageNumber = 1))
            )
        )
        fakeScanner.result = makeSuccessResult(
            listOf(makeScannedComic("Comics/OP", chapters = listOf(chapter1, chapter2)))
        )

        repository.syncLibrary(rootUri)

        val comic = fakeComicDao.comics.values.first()
        assertEquals("page_001.jpg", comic.coverEntryPath)
    }

    @Test
    fun syncLibrary_noChapter1_coverIsNull() = runTest {
        val chapter2 = makeScannedChapter(
            chapterNumber = 2,
            catalogResult = CbzCatalogResult.Success(
                cbzRelativePath = "Comics/OP/Chapter 2.cbz",
                pages = listOf(CbzPageEntry(entryPath = "page_001.jpg", pageNumber = 1))
            )
        )
        fakeScanner.result = makeSuccessResult(
            listOf(makeScannedComic("Comics/OP", chapters = listOf(chapter2)))
        )

        repository.syncLibrary(rootUri)

        val comic = fakeComicDao.comics.values.first()
        assertNull("Cover must be null when Chapter 1 is absent", comic.coverEntryPath)
    }

    @Test
    fun syncLibrary_chapter1Corrupt_coverIsNull() = runTest {
        val chapter1 = makeScannedChapter(
            chapterNumber = 1,
            catalogResult = CbzCatalogResult.CorruptArchive(
                cbzRelativePath = "Comics/OP/Chapter 1.cbz",
                reason = "Not a ZIP file"
            )
        )
        fakeScanner.result = makeSuccessResult(
            listOf(makeScannedComic("Comics/OP", chapters = listOf(chapter1)))
        )

        repository.syncLibrary(rootUri)

        val comic = fakeComicDao.comics.values.first()
        assertNull("Cover must be null when Chapter 1 is corrupt", comic.coverEntryPath)
    }

    @Test
    fun syncLibrary_chapter1Empty_coverIsNull() = runTest {
        val chapter1 = makeScannedChapter(
            chapterNumber = 1,
            catalogResult = CbzCatalogResult.Empty("Comics/OP/Chapter 1.cbz")
        )
        fakeScanner.result = makeSuccessResult(
            listOf(makeScannedComic("Comics/OP", chapters = listOf(chapter1)))
        )

        repository.syncLibrary(rootUri)

        val comic = fakeComicDao.comics.values.first()
        assertNull("Cover must be null when Chapter 1 has no pages", comic.coverEntryPath)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 7. Flow reactivity
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun observeComicsByRoot_emitsEmptyInitially_thenUpdatesAfterSync() = runTest {
        val initial = repository.observeComicsByRoot(rootUri).first()
        assertTrue(initial.isEmpty())

        fakeScanner.result = makeSuccessResult(listOf(makeScannedComic("Comics/A", title = "A")))
        repository.syncLibrary(rootUri)

        val afterSync = repository.observeComicsByRoot(rootUri).first()
        assertEquals(1, afterSync.size)
        assertEquals("A", afterSync[0].title)
    }

    @Test
    fun observeBookmarkedComicsByRoot_emitsOnlyBookmarked() = runTest {
        fakeScanner.result = makeSuccessResult(
            listOf(
                makeScannedComic("Comics/A", title = "A"),
                makeScannedComic("Comics/B", title = "B")
            )
        )
        repository.syncLibrary(rootUri)

        val comicA = fakeComicDao.comics.values.first { it.title == "A" }
        repository.setBookmarked(comicA.id, true)

        val bookmarked = repository.observeBookmarkedComicsByRoot(rootUri).first()
        assertEquals(1, bookmarked.size)
        assertEquals("A", bookmarked[0].title)
    }

    @Test
    fun observeRecentlyReadByRoot_emitsOnlyReadComics_mostRecentFirst() = runTest {
        fakeScanner.result = makeSuccessResult(
            listOf(
                makeScannedComic("Comics/A", title = "A"),
                makeScannedComic("Comics/B", title = "B"),
                makeScannedComic("Comics/C", title = "C")
            )
        )
        repository.syncLibrary(rootUri)

        val comicA = fakeComicDao.comics.values.first { it.title == "A" }
        val comicB = fakeComicDao.comics.values.first { it.title == "B" }
        fakeComicDao.updateLastReadAt(comicA.id, 1000L)
        fakeComicDao.updateLastReadAt(comicB.id, 2000L)

        val recent = repository.observeRecentlyReadByRoot(rootUri).first()
        assertEquals(2, recent.size)
        assertEquals("B", recent[0].title)  // most recent first
        assertEquals("A", recent[1].title)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 8. Error results are returned as-is
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun syncLibrary_errorResult_returnsError() = runTest {
        val error = RuntimeException("Unexpected I/O failure")
        fakeScanner.result = LibraryScanResult.Error(error)

        val result = repository.syncLibrary(rootUri)

        assertTrue(result is LibraryScanResult.Error)
        assertEquals(error, (result as LibraryScanResult.Error).exception)
    }

    @Test
    fun syncLibrary_rootNotConfigured_returnsRootNotConfigured() = runTest {
        fakeScanner.result = LibraryScanResult.RootNotConfigured

        val result = repository.syncLibrary(rootUri)

        assertTrue(result is LibraryScanResult.RootNotConfigured)
    }

    @Test
    fun syncLibrary_permissionDenied_returnsPermissionDenied() = runTest {
        fakeScanner.result = LibraryScanResult.PermissionDenied("No permission")

        val result = repository.syncLibrary(rootUri)

        assertTrue(result is LibraryScanResult.PermissionDenied)
        assertEquals("No permission", (result as LibraryScanResult.PermissionDenied).message)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Reading progress
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun saveAndGetReadingProgress_roundtrip() = runTest {
        fakeScanner.result = makeSuccessResult(listOf(makeScannedComic("Comics/OP")))
        repository.syncLibrary(rootUri)
        val comicId = fakeComicDao.comics.values.first().id

        val progress = ReadingProgressEntity(
            comicId = comicId,
            lastReadChapterNumber = 3,
            chapterRelativePath = "Comics/OP/Chapter 3.cbz",
            pageIndex = 5,
            scrollOffset = 120,
            updatedAt = 9999L
        )
        repository.saveReadingProgress(progress)

        val retrieved = repository.getReadingProgress(comicId)
        assertNotNull(retrieved)
        assertEquals(3, retrieved!!.lastReadChapterNumber)
        assertEquals(5, retrieved.pageIndex)
        assertEquals(120, retrieved.scrollOffset)
    }

    @Test
    fun saveReadingProgress_newerTimestamp_updatesProgressAndLastReadAt() = runTest {
        val chapter = makeScannedChapter(chapterNumber = 1, pageCount = 10)
        fakeScanner.result = makeSuccessResult(listOf(makeScannedComic("Comics/OP", chapters = listOf(chapter))))
        repository.syncLibrary(rootUri)

        val comicId = fakeComicDao.comics.values.first().id

        val progress1 = ReadingProgressEntity(
            comicId = comicId,
            lastReadChapterId = 1L,
            lastReadChapterNumber = 1,
            chapterRelativePath = "Comics/OP/Chapter 1.cbz",
            pageIndex = 2,
            updatedAt = 1000L
        )
        repository.saveReadingProgress(progress1)

        val comicAfter1 = fakeComicDao.getById(comicId)!!
        assertEquals(1000L, comicAfter1.lastReadAt)
        assertEquals(2, repository.getReadingProgress(comicId)!!.pageIndex)

        val progress2 = ReadingProgressEntity(
            comicId = comicId,
            lastReadChapterId = 1L,
            lastReadChapterNumber = 1,
            chapterRelativePath = "Comics/OP/Chapter 1.cbz",
            pageIndex = 5,
            updatedAt = 2000L
        )
        repository.saveReadingProgress(progress2)

        val comicAfter2 = fakeComicDao.getById(comicId)!!
        assertEquals(2000L, comicAfter2.lastReadAt)
        assertEquals(5, repository.getReadingProgress(comicId)!!.pageIndex)
    }

    @Test
    fun saveReadingProgress_staleTimestamp_rejectsAndPreservesOriginalProgressAndLastReadAt() = runTest {
        val chapter = makeScannedChapter(chapterNumber = 1, pageCount = 10)
        fakeScanner.result = makeSuccessResult(listOf(makeScannedComic("Comics/OP", chapters = listOf(chapter))))
        repository.syncLibrary(rootUri)

        val comicId = fakeComicDao.comics.values.first().id

        // Newer progress B saved first at t=2000L
        val progressB = ReadingProgressEntity(
            comicId = comicId,
            lastReadChapterId = 1L,
            lastReadChapterNumber = 1,
            chapterRelativePath = "Comics/OP/Chapter 1.cbz",
            pageIndex = 5,
            updatedAt = 2000L
        )
        repository.saveReadingProgress(progressB)

        // Stale progress A arrives late at t=1000L
        val progressA = ReadingProgressEntity(
            comicId = comicId,
            lastReadChapterId = 1L,
            lastReadChapterNumber = 1,
            chapterRelativePath = "Comics/OP/Chapter 1.cbz",
            pageIndex = 1,
            updatedAt = 1000L
        )
        repository.saveReadingProgress(progressA)

        // Stale progress A must be REJECTED; progress B remains preserved
        val retrieved = repository.getReadingProgress(comicId)!!
        assertEquals(5, retrieved.pageIndex)
        assertEquals(2000L, retrieved.updatedAt)

        val comic = fakeComicDao.getById(comicId)!!
        assertEquals(2000L, comic.lastReadAt)
    }

    @Test
    fun setBookmarked_togglesIsBookmarked_andPreservesReadingProgressAndLastReadAt() = runTest {
        val chapter = makeScannedChapter(chapterNumber = 1, pageCount = 10)
        fakeScanner.result = makeSuccessResult(listOf(makeScannedComic("Comics/OP", chapters = listOf(chapter))))
        repository.syncLibrary(rootUri)

        val comicId = fakeComicDao.comics.values.first().id
        fakeComicDao.updateLastReadAt(comicId, 5000L)

        val initialProgress = ReadingProgressEntity(
            comicId = comicId,
            lastReadChapterId = 1L,
            lastReadChapterNumber = 1,
            chapterRelativePath = "Comics/OP/Chapter 1.cbz",
            pageIndex = 3,
            updatedAt = 5000L
        )
        repository.saveReadingProgress(initialProgress)

        // Initial bookmark state false
        assertFalse(fakeComicDao.getById(comicId)!!.isBookmarked)
        assertEquals(0, repository.observeBookmarkedComicsByRoot(rootUri).first().size)

        // Set bookmarked to true
        repository.setBookmarked(comicId, true)

        // Verify bookmark state true and observable in observeBookmarkedComicsByRoot
        assertTrue(fakeComicDao.getById(comicId)!!.isBookmarked)
        val bookmarkedList = repository.observeBookmarkedComicsByRoot(rootUri).first()
        assertEquals(1, bookmarkedList.size)
        assertEquals(comicId, bookmarkedList.first().id)

        // Verify user state (lastReadAt and reading progress) is 100% PRESERVED
        assertEquals(5000L, fakeComicDao.getById(comicId)!!.lastReadAt)
        val currentProgress = repository.getReadingProgress(comicId)!!
        assertEquals(3, currentProgress.pageIndex)
        assertEquals(5000L, currentProgress.updatedAt)
    }

    @Test
    fun flushReadingProgress_launchesOnApplicationScopeAndSavesProgress() = runTest {
        val testAppScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler))

        val realComicDao = db.comicDao()
        val realChapterDao = db.chapterDao()
        val realProgressDao = db.readingProgressDao()

        val testRepo = DefaultLibraryRepository(
            fakeScanner, IncrementalCbzCataloger(NoopCbzCataloger), realComicDao, realChapterDao, realProgressDao,
            CoverExtractor(FakeStorageGateway(), realComicDao, realChapterDao),
            CoverCacheManager(File(System.getProperty("java.io.tmpdir"), "test_repo_covers"), CoverExtractor(FakeStorageGateway(), realComicDao, realChapterDao), realComicDao, realChapterDao),
            FakeStorageGateway(),
            com.example.kitsune.reader.DefaultCbzArchiveProvider(File(System.getProperty("java.io.tmpdir"), "test_repo_spool"), FakeStorageGateway(), realChapterDao),
            db,
            applicationScope = testAppScope
        )

        val comicId = realComicDao.insert(ComicEntity(rootUriString = rootUri, relativePath = "Comics/OP", title = "OP"))
        val chapterId = realChapterDao.insert(ChapterEntity(comicId = comicId, relativePath = "Comics/OP/Ch1.cbz", chapterNumber = 1, displayName = "Ch1.cbz", pageCount = 10))

        val progress = ReadingProgressEntity(
            comicId = comicId,
            lastReadChapterId = chapterId,
            lastReadChapterNumber = 1,
            chapterRelativePath = "Comics/OP/Ch1.cbz",
            pageIndex = 7,
            updatedAt = 3000L
        )

        // Asynchronous flush
        testRepo.flushReadingProgress(progress)

        val retrieved = realProgressDao.getByComicId(comicId)
        assertNotNull(retrieved)
        assertEquals(7, retrieved!!.pageIndex)
        assertEquals(3000L, retrieved.updatedAt)
    }

    @Test
    fun saveReadingProgress_sqliteTriggerFailureOnComicUpdate_rollsBackReadingProgressAndComic() = runTest {
        val realComicDao = db.comicDao()
        val realChapterDao = db.chapterDao()
        val realProgressDao = db.readingProgressDao()

        val testRepo = DefaultLibraryRepository(
            fakeScanner, IncrementalCbzCataloger(NoopCbzCataloger), realComicDao, realChapterDao, realProgressDao,
            CoverExtractor(FakeStorageGateway(), realComicDao, realChapterDao),
            CoverCacheManager(File(System.getProperty("java.io.tmpdir"), "test_repo_covers"), CoverExtractor(FakeStorageGateway(), realComicDao, realChapterDao), realComicDao, realChapterDao),
            FakeStorageGateway(),
            com.example.kitsune.reader.DefaultCbzArchiveProvider(File(System.getProperty("java.io.tmpdir"), "test_repo_spool"), FakeStorageGateway(), realChapterDao),
            db
        )

        val comicId = realComicDao.insert(ComicEntity(rootUriString = rootUri, relativePath = "Comics/OP", title = "OP"))
        val chapterId = realChapterDao.insert(ChapterEntity(comicId = comicId, relativePath = "Comics/OP/Ch1.cbz", chapterNumber = 1, displayName = "Ch1.cbz", pageCount = 10))

        // Initial progress at t=1000L
        val initialProgress = ReadingProgressEntity(
            comicId = comicId,
            lastReadChapterId = chapterId,
            lastReadChapterNumber = 1,
            chapterRelativePath = "Comics/OP/Ch1.cbz",
            pageIndex = 2,
            scrollOffset = 50,
            updatedAt = 1000L
        )
        testRepo.saveReadingProgress(initialProgress)

        // Verify initial state
        assertEquals(2, realProgressDao.getByComicId(comicId)!!.pageIndex)
        assertEquals(1000L, realComicDao.getById(comicId)!!.lastReadAt)

        // Install SQLite trigger on Room in-memory database to fail ANY update of lastReadAt > 1500L
        db.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_comic_last_read_update
            BEFORE UPDATE OF lastReadAt ON comics
            FOR EACH ROW
            WHEN NEW.lastReadAt > 1500
            BEGIN
                SELECT RAISE(FAIL, 'Simulated SQLite trigger failure on comic lastReadAt update');
            END;
            """.trimIndent()
        )

        // Attempt save with new progress pageIndex = 8, updatedAt = 2000L
        val newProgress = ReadingProgressEntity(
            comicId = comicId,
            lastReadChapterId = chapterId,
            lastReadChapterNumber = 1,
            chapterRelativePath = "Comics/OP/Ch1.cbz",
            pageIndex = 8,
            scrollOffset = 200,
            updatedAt = 2000L
        )

        try {
            testRepo.saveReadingProgress(newProgress)
            fail("Expected SQLiteConstraintException from trigger failure")
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            assertTrue(e.message!!.contains("Simulated SQLite trigger failure"))
        }

        // PROOF OF ATOMIC ROLLBACK:
        // Even though readingProgressDao.save() was executed FIRST inside withTransaction,
        // the subsequent failure in comicDao.updateLastReadAt() triggered a SQLite transaction ROLLBACK!
        // ReadingProgressEntity pageIndex remains 2, updatedAt remains 1000L, and lastReadAt remains 1000L!
        val currentProgress = realProgressDao.getByComicId(comicId)!!
        assertEquals("ReadingProgress pageIndex MUST rollback to 2", 2, currentProgress.pageIndex)
        assertEquals("ReadingProgress updatedAt MUST rollback to 1000L", 1000L, currentProgress.updatedAt)

        val currentComic = realComicDao.getById(comicId)!!
        assertEquals("ComicEntity lastReadAt MUST rollback to 1000L", 1000L, currentComic.lastReadAt)
    }

    @Test
    fun saveReadingProgress_missingComic_throwsException() = runTest {
        val progress = ReadingProgressEntity(
            comicId = 99999L,
            lastReadChapterNumber = 1,
            chapterRelativePath = "Comics/Missing/Ch1.cbz",
            pageIndex = 0,
            updatedAt = 1000L
        )

        try {
            repository.saveReadingProgress(progress)
            fail("Expected IllegalArgumentException for missing comic")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("not found"))
        }
    }

    @Test
    fun getReadingProgress_noProgress_returnsNull() = runTest {
        val result = repository.getReadingProgress(99999L)
        assertNull(result)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // chapter isValid mapping
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun syncLibrary_corruptChapter_isValid_isFalse() = runTest {
        val chapter = makeScannedChapter(
            chapterNumber = 2,
            catalogResult = CbzCatalogResult.CorruptArchive("Comics/OP/Chapter 2.cbz", "bad zip")
        )
        fakeScanner.result = makeSuccessResult(
            listOf(makeScannedComic("Comics/OP", chapters = listOf(chapter)))
        )

        repository.syncLibrary(rootUri)

        val chapterEntity = fakeChapterDao.chapters.values.first()
        assertTrue("Corrupt chapter must have isValid=false", !chapterEntity.isValid)
    }

    @Test
    fun syncLibrary_validChapter_isValid_isTrue() = runTest {
        val chapter = makeScannedChapter(chapterNumber = 1, pageCount = 10)
        fakeScanner.result = makeSuccessResult(
            listOf(makeScannedComic("Comics/OP", chapters = listOf(chapter)))
        )

        repository.syncLibrary(rootUri)

        val chapterEntity = fakeChapterDao.chapters.values.first()
        assertTrue("Valid chapter must have isValid=true", chapterEntity.isValid)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Helpers — factory methods
    // ═══════════════════════════════════════════════════════════════════════

    private fun makeSuccessResult(comics: List<ScannedComic>): LibraryScanResult.Success {
        return LibraryScanResult.Success(
            comics = comics,
            summary = ScanSummary(
                totalComicFoldersDiscovered = comics.size,
                validComicsCount = comics.count { it.hasValidChapters },
                totalChaptersDiscovered = comics.sumOf { it.chapters.size },
                totalValidChaptersCount = comics.sumOf { c -> c.chapters.count { it.isValid } },
                totalCorruptArchivesCount = 0,
                totalEmptyArchivesCount = 0,
                totalRejectedFilesCount = 0,
                comicsMissingFirstChapterCount = comics.count { it.firstChapter == null }
            ),
            issues = emptyList()
        )
    }

    private fun makeScannedComic(
        relativePath: String,
        title: String = relativePath.substringAfterLast('/'),
        chapters: List<ScannedChapter> = emptyList()
    ): ScannedComic {
        val folder = DiscoveredComicFolder(
            folderName = relativePath.substringAfterLast('/'),
            relativePath = relativePath,
            defaultTitle = title,
            entry = StorageEntry(
                name = relativePath.substringAfterLast('/'),
                relativePath = relativePath,
                isDirectory = true
            )
        )
        return ScannedComic(
            folder = folder,
            chapters = chapters,
            rejectedFiles = emptyList(),
            issues = emptyList()
        )
    }

    private fun makeScannedChapter(
        chapterNumber: Int,
        pageCount: Int = 10,
        lastModifiedMillis: Long = 0L,
        catalogResult: CbzCatalogResult? = null
    ): ScannedChapter {
        val relativePath = "Comics/Test/Chapter $chapterNumber.cbz"
        val discovered = DiscoveredChapter(
            fileName = "Chapter $chapterNumber.cbz",
            relativePath = relativePath,
            chapterNumber = chapterNumber,
            entry = StorageEntry(
                name = "Chapter $chapterNumber.cbz",
                relativePath = relativePath,
                isDirectory = false,
                sizeBytes = 1024L * chapterNumber,
                lastModifiedMillis = lastModifiedMillis
            )
        )
        val resolvedCatalog = catalogResult ?: CbzCatalogResult.Success(
            cbzRelativePath = relativePath,
            pages = (1..pageCount).map { i -> CbzPageEntry("page_${i.toString().padStart(3, '0')}.jpg", i) }
        )
        return ScannedChapter(discoveredChapter = discovered, catalogResult = resolvedCatalog)
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Fake implementations
// ═══════════════════════════════════════════════════════════════════════════

/**
 * In-memory fake [ComicDao] backed by a [MutableMap].
 * Provides correct upsert semantics (preserves isBookmarked/lastReadAt).
 */
class FakeComicDao : ComicDao {
    val comics = mutableMapOf<Long, ComicEntity>()
    private var nextId = 1L
    private val _flow = MutableStateFlow<Map<Long, ComicEntity>>(emptyMap())

    private fun notifyChange() { _flow.value = comics.toMap() }

    override fun observeComicsByRoot(rootUri: String): Flow<List<ComicEntity>> =
        _flow.map { it.values.filter { c -> c.rootUriString == rootUri }.sortedBy { c -> c.title.lowercase() } }

    override fun observeComicById(comicId: Long): Flow<ComicEntity?> =
        _flow.map { it[comicId] }

    override fun observeBookmarkedComicsByRoot(rootUri: String): Flow<List<ComicEntity>> =
        _flow.map { it.values.filter { c -> c.rootUriString == rootUri && c.isBookmarked }.sortedBy { c -> c.title.lowercase() } }

    override fun observeRecentlyReadByRoot(rootUri: String): Flow<List<ComicEntity>> =
        _flow.map {
            it.values
                .filter { c -> c.rootUriString == rootUri && c.lastReadAt != null }
                .sortedByDescending { c -> c.lastReadAt }
        }

    override fun observeRecentlyReadWithProgressByRoot(rootUri: String): Flow<List<com.example.kitsune.data.model.ComicWithProgress>> =
        flowOf(emptyList())

    override fun searchComicsByRoot(rootUri: String, query: String): Flow<List<ComicEntity>> =
        _flow.map { map ->
            map.values.filter { c ->
                c.rootUriString == rootUri && c.title.contains(query.trim(), ignoreCase = true)
            }.sortedBy { c -> c.title.lowercase() }
        }

    override fun searchComicsByRootEscaped(rootUri: String, rawQuery: String): Flow<List<ComicEntity>> =
        searchComicsByRoot(rootUri, rawQuery)

    override suspend fun getById(comicId: Long): ComicEntity? = comics[comicId]

    override suspend fun getByRootAndPath(rootUri: String, relativePath: String): ComicEntity? =
        comics.values.find { it.rootUriString == rootUri && it.relativePath == relativePath }

    override suspend fun getAllByRoot(rootUri: String): List<ComicEntity> =
        comics.values.filter { it.rootUriString == rootUri }

    override suspend fun insert(comic: ComicEntity): Long {
        val id = nextId++
        comics[id] = comic.copy(id = id)
        notifyChange()
        return id
    }

    override suspend fun update(comic: ComicEntity) {
        comics[comic.id] = comic
        notifyChange()
    }

    override suspend fun setBookmarked(comicId: Long, bookmarked: Boolean) {
        comics[comicId]?.let { comics[comicId] = it.copy(isBookmarked = bookmarked) }
        notifyChange()
    }

    override suspend fun updateLastReadAt(comicId: Long, timestamp: Long) {
        comics[comicId]?.let { comics[comicId] = it.copy(lastReadAt = timestamp) }
        notifyChange()
    }

    override suspend fun deleteById(comicId: Long) {
        comics.remove(comicId)
        notifyChange()
    }

    override suspend fun deleteAllByRoot(rootUri: String) {
        comics.keys.removeAll(comics.values.filter { it.rootUriString == rootUri }.map { it.id }.toSet())
        notifyChange()
    }

    override suspend fun findOrphanedIds(rootUri: String, currentPaths: List<String>): List<Long> =
        comics.values.filter { it.rootUriString == rootUri && it.relativePath !in currentPaths }.map { it.id }
}

/**
 * In-memory fake [ChapterDao] backed by a [MutableMap].
 * Preserves chapter IDs on upsert.
 */
open class FakeChapterDao : ChapterDao {
    val chapters = mutableMapOf<Long, ChapterEntity>()
    private var nextId = 1L
    private val _flow = MutableStateFlow<Map<Long, ChapterEntity>>(emptyMap())

    private fun notifyChange() { _flow.value = chapters.toMap() }

    override fun observeChaptersByComicId(comicId: Long): Flow<List<ChapterEntity>> =
        _flow.map { it.values.filter { c -> c.comicId == comicId }.sortedBy { c -> c.chapterNumber } }

    override suspend fun getById(chapterId: Long): ChapterEntity? = chapters[chapterId]

    override suspend fun getByComicAndPath(comicId: Long, relativePath: String): ChapterEntity? =
        chapters.values.find { it.comicId == comicId && it.relativePath == relativePath }

    override suspend fun getChapterOneByComicId(comicId: Long): ChapterEntity? =
        chapters.values.find { it.comicId == comicId && it.chapterNumber == 1 }

    override suspend fun getAllByComicId(comicId: Long): List<ChapterEntity> =
        chapters.values.filter { it.comicId == comicId }.sortedBy { it.chapterNumber }

    override suspend fun countByComicId(comicId: Long): Int =
        chapters.values.count { it.comicId == comicId }

    override suspend fun insert(chapter: ChapterEntity): Long {
        val id = nextId++
        chapters[id] = chapter.copy(id = id)
        notifyChange()
        return id
    }

    override suspend fun insertAll(chapters: List<ChapterEntity>): List<Long> =
        chapters.map { insert(it) }

    override suspend fun update(chapter: ChapterEntity) {
        chapters[chapter.id] = chapter
        notifyChange()
    }

    override suspend fun deleteById(chapterId: Long) {
        chapters.remove(chapterId)
        notifyChange()
    }

    override suspend fun deleteAllByComicId(comicId: Long) {
        val toRemove = chapters.values.filter { it.comicId == comicId }.map { it.id }
        toRemove.forEach { chapters.remove(it) }
        notifyChange()
    }

    override suspend fun findOrphanedIds(comicId: Long, currentPaths: List<String>): List<Long> =
        chapters.values.filter { it.comicId == comicId && it.relativePath !in currentPaths }.map { it.id }

    override suspend fun deleteByIds(ids: List<Long>) {
        ids.forEach { chapters.remove(it) }
        notifyChange()
    }
}

/**
 * In-memory fake [ReadingProgressDao] backed by a [MutableMap].
 */
class FakeReadingProgressDao : ReadingProgressDao {
    private val progressMap = mutableMapOf<Long, ReadingProgressEntity>()

    override suspend fun getByComicId(comicId: Long): ReadingProgressEntity? = progressMap[comicId]

    override suspend fun save(progress: ReadingProgressEntity) {
        progressMap[progress.comicId] = progress
    }

    override suspend fun deleteByComicId(comicId: Long) {
        progressMap.remove(comicId)
    }

    override suspend fun getAll(): List<ReadingProgressEntity> =
        progressMap.values.sortedByDescending { it.updatedAt }
}

/**
 * Configurable fake [LibraryScanner] that returns a preset [LibraryScanResult].
 */
class FakeLibraryScanner : LibraryScanner {
    var result: LibraryScanResult = LibraryScanResult.RootNotConfigured

    override fun scanLibrary(): LibraryScanResult = result
}

/**
 * A [com.example.kitsune.scanner.CbzCataloger] that always returns [CbzCatalogResult.Error].
 * Used in [LibraryRepositoryTest] where [FakeLibraryScanner] bypasses the scanner pipeline
 * entirely, so the cataloger is never invoked. This satisfies the [IncrementalCbzCataloger]
 * constructor without performing any real I/O.
 */
object NoopCbzCataloger : com.example.kitsune.scanner.CbzCataloger {
    override fun catalogArchive(
        cbzRelativePath: String,
        fileMetadata: com.example.kitsune.storage.StorageEntry?
    ): CbzCatalogResult = CbzCatalogResult.Error(UnsupportedOperationException("NoopCbzCataloger"))
}

