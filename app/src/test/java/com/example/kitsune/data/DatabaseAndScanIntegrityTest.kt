package com.example.kitsune.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.kitsune.data.dao.ChapterDao
import com.example.kitsune.data.dao.ComicDao
import com.example.kitsune.data.dao.ReadingProgressDao
import com.example.kitsune.data.entity.ChapterEntity
import com.example.kitsune.data.entity.ComicEntity
import com.example.kitsune.data.entity.ReadingProgressEntity
import com.example.kitsune.scanner.CbzCatalogResult
import com.example.kitsune.scanner.CbzPageEntry
import com.example.kitsune.scanner.DiscoveredChapter
import com.example.kitsune.scanner.LibraryScanResult
import com.example.kitsune.scanner.ScannedChapter
import com.example.kitsune.scanner.ScannedComic
import com.example.kitsune.storage.FakeStorageGateway
import com.example.kitsune.storage.StorageEntry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DatabaseAndScanIntegrityTest {

    private lateinit var context: Context
    private lateinit var db: KitsuneDatabase
    private lateinit var comicDao: ComicDao
    private lateinit var chapterDao: ChapterDao
    private lateinit var progressDao: ReadingProgressDao

    private val rootUri = "content://primary%3AKitsune"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, KitsuneDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        comicDao = db.comicDao()
        chapterDao = db.chapterDao()
        progressDao = db.readingProgressDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun databaseSchemaVersion4_andCreatesAllTablesAndIndices() {
        assertEquals(4, db.openHelper.readableDatabase.version)

        val cursor = db.openHelper.readableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type='table'"
        )

        val tables = mutableSetOf<String>()
        while (cursor.moveToNext()) {
            tables.add(cursor.getString(0))
        }
        cursor.close()

        assertTrue("Table 'comics' MUST exist in SQLite master", tables.contains("comics"))
        assertTrue("Table 'chapters' MUST exist in SQLite master", tables.contains("chapters"))
        assertTrue("Table 'reading_progress' MUST exist in SQLite master", tables.contains("reading_progress"))
        assertTrue("Table 'app_settings' MUST exist in SQLite master", tables.contains("app_settings"))
        assertTrue("Table 'bookmarks' MUST exist in SQLite master", tables.contains("bookmarks"))
        assertTrue("Table 'comic_bookmark_cross_ref' MUST exist in SQLite master", tables.contains("comic_bookmark_cross_ref"))
        assertTrue("Table 'room_master_table' MUST exist in SQLite master", tables.contains("room_master_table"))
    }

    @Test
    fun scanSynchronization_initialScan_insertsComicsAndChaptersWithoutDuplicates() = runTest {
        val folderEntry = StorageEntry("Bleach", "Comics/Bleach", isDirectory = true)
        val discoveredFolder = com.example.kitsune.scanner.DiscoveredComicFolder("Bleach", "Comics/Bleach", "Bleach", folderEntry)

        val chEntry = StorageEntry("Chapter 1.cbz", "Comics/Bleach/Chapter 1.cbz", isDirectory = false, sizeBytes = 1024L, lastModifiedMillis = 1000L)
        val discoveredCh1 = DiscoveredChapter("Chapter 1.cbz", "Comics/Bleach/Chapter 1.cbz", 1, chEntry)
        val scannedCh1 = ScannedChapter(discoveredCh1, catalogResult = CbzCatalogResult.Success("Comics/Bleach/Chapter 1.cbz", listOf(CbzPageEntry("p1.jpg", 1))))

        val scannedComic = ScannedComic(discoveredFolder, listOf(scannedCh1), emptyList(), emptyList())
        val scanSummary = com.example.kitsune.scanner.ScanSummary(1, 1, 1, 1, 0, 0, 0, 0)
        val scanResult = LibraryScanResult.Success(comics = listOf(scannedComic), summary = scanSummary, issues = emptyList())

        val fakeScanner = object : com.example.kitsune.scanner.LibraryScanner {
            override fun scanLibrary(): LibraryScanResult = scanResult
        }

        val fakeStorage = FakeStorageGateway(rootConfigured = true, permissionGranted = true)
        val repository = DefaultLibraryRepository(
            fakeScanner,
            com.example.kitsune.scanner.IncrementalCbzCataloger(com.example.kitsune.data.NoopCbzCataloger),
            comicDao, chapterDao, progressDao,
            CoverExtractor(fakeStorage, comicDao, chapterDao),
            CoverCacheManager(File(System.getProperty("java.io.tmpdir"), "integrity_covers"), CoverExtractor(fakeStorage, comicDao, chapterDao), comicDao, chapterDao),
            fakeStorage,
            com.example.kitsune.reader.DefaultCbzArchiveProvider(File(System.getProperty("java.io.tmpdir"), "integrity_spool"), fakeStorage, chapterDao),
            db
        )

        // Perform Initial Sync
        val syncResult = repository.syncLibrary(rootUri)
        assertTrue(syncResult is LibraryScanResult.Success)

        val comics = comicDao.getAllByRoot(rootUri)
        assertEquals(1, comics.size)
        assertEquals("Bleach", comics.first().title)

        val chapters = chapterDao.getAllByComicId(comics.first().id)
        assertEquals(1, chapters.size)
        assertEquals("Chapter 1.cbz", chapters.first().displayName)
    }

    @Test
    fun scanSynchronization_repeatedScan_isIdempotent_andPreservesBookmarksAndProgress() = runTest {
        val folderEntry = StorageEntry("Bleach", "Comics/Bleach", isDirectory = true)
        val discoveredFolder = com.example.kitsune.scanner.DiscoveredComicFolder("Bleach", "Comics/Bleach", "Bleach", folderEntry)

        val chEntry = StorageEntry("Chapter 1.cbz", "Comics/Bleach/Chapter 1.cbz", isDirectory = false, sizeBytes = 1024L, lastModifiedMillis = 1000L)
        val discoveredCh1 = DiscoveredChapter("Chapter 1.cbz", "Comics/Bleach/Chapter 1.cbz", 1, chEntry)
        val scannedCh1 = ScannedChapter(discoveredCh1, catalogResult = CbzCatalogResult.Success("Comics/Bleach/Chapter 1.cbz", listOf(CbzPageEntry("p1.jpg", 1))))

        val scannedComic = ScannedComic(discoveredFolder, listOf(scannedCh1), emptyList(), emptyList())
        val scanSummary = com.example.kitsune.scanner.ScanSummary(1, 1, 1, 1, 0, 0, 0, 0)
        val scanResult = LibraryScanResult.Success(comics = listOf(scannedComic), summary = scanSummary, issues = emptyList())

        val fakeScanner = object : com.example.kitsune.scanner.LibraryScanner {
            override fun scanLibrary(): LibraryScanResult = scanResult
        }

        val fakeStorage = FakeStorageGateway(rootConfigured = true, permissionGranted = true)
        val repository = DefaultLibraryRepository(
            fakeScanner,
            com.example.kitsune.scanner.IncrementalCbzCataloger(com.example.kitsune.data.NoopCbzCataloger),
            comicDao, chapterDao, progressDao,
            CoverExtractor(fakeStorage, comicDao, chapterDao),
            CoverCacheManager(File(System.getProperty("java.io.tmpdir"), "integrity_covers_idempotent"), CoverExtractor(fakeStorage, comicDao, chapterDao), comicDao, chapterDao),
            fakeStorage,
            com.example.kitsune.reader.DefaultCbzArchiveProvider(File(System.getProperty("java.io.tmpdir"), "integrity_spool_idempotent"), fakeStorage, chapterDao),
            db
        )

        // 1. Initial Sync
        repository.syncLibrary(rootUri)
        val comicId = comicDao.getAllByRoot(rootUri).first().id

        // 2. User mutates state: Bookmark = true, lastReadAt = 5000L, progress saved
        repository.setBookmarked(comicId, true)
        val progress = ReadingProgressEntity(
            comicId = comicId,
            lastReadChapterId = 1L,
            lastReadChapterNumber = 1,
            chapterRelativePath = "Comics/Bleach/Chapter 1.cbz",
            pageIndex = 5,
            scrollOffset = 100,
            updatedAt = 5000L
        )
        repository.saveReadingProgress(progress)

        // 3. Repeated Sync
        repository.syncLibrary(rootUri)

        // Verify IDEMPOTENCY and PRESERVATION
        val comics = comicDao.getAllByRoot(rootUri)
        assertEquals("Repeated scan MUST NOT create duplicate comic rows", 1, comics.size)
        assertTrue("Bookmark state MUST be preserved on repeated scan", comics.first().isBookmarked)
        assertEquals("lastReadAt timestamp MUST be preserved on repeated scan", 5000L, comics.first().lastReadAt)

        val chapters = chapterDao.getAllByComicId(comicId)
        assertEquals("Repeated scan MUST NOT create duplicate chapter rows", 1, chapters.size)

        val preservedProgress = repository.getReadingProgress(comicId)
        assertNotNull(preservedProgress)
        assertEquals(5, preservedProgress!!.pageIndex)
    }

    @Test
    fun scanSynchronization_failedScanOrPermissionLost_neverTriggersMassDeletion() = runTest {
        // Seed database with comic & chapter
        val comicId = comicDao.insert(ComicEntity(rootUriString = rootUri, relativePath = "Comics/Naruto", title = "Naruto", isBookmarked = true, lastReadAt = 1000L))
        chapterDao.insert(ChapterEntity(comicId = comicId, relativePath = "Comics/Naruto/Chapter 1.cbz", chapterNumber = 1, displayName = "Chapter 1.cbz", pageCount = 10))

        // Scanner fails with PermissionDenied
        val fakeFailingScanner = object : com.example.kitsune.scanner.LibraryScanner {
            override fun scanLibrary(): LibraryScanResult = LibraryScanResult.PermissionDenied("Access revoked")
        }

        val fakeStorage = FakeStorageGateway(rootConfigured = true, permissionGranted = false)
        val repository = DefaultLibraryRepository(
            fakeFailingScanner,
            com.example.kitsune.scanner.IncrementalCbzCataloger(com.example.kitsune.data.NoopCbzCataloger),
            comicDao, chapterDao, progressDao,
            CoverExtractor(fakeStorage, comicDao, chapterDao),
            CoverCacheManager(File(System.getProperty("java.io.tmpdir"), "integrity_covers_fail"), CoverExtractor(fakeStorage, comicDao, chapterDao), comicDao, chapterDao),
            fakeStorage,
            com.example.kitsune.reader.DefaultCbzArchiveProvider(File(System.getProperty("java.io.tmpdir"), "integrity_spool_fail"), fakeStorage, chapterDao),
            db
        )

        // Sync with failed scan
        val result = repository.syncLibrary(rootUri)
        assertTrue(result is LibraryScanResult.PermissionDenied)

        // RULE K-03 ASSERTION: Existing database records MUST NOT be mass-deleted!
        val comics = comicDao.getAllByRoot(rootUri)
        assertEquals("Failed scan MUST NOT delete existing comics", 1, comics.size)
        assertEquals("Naruto", comics.first().title)

        val chapters = chapterDao.getAllByComicId(comicId)
        assertEquals("Failed scan MUST NOT delete existing chapters", 1, chapters.size)
    }

    @Test
    fun fileBackedDatabase_reopenAndIntegrity_preservesBookmarksProgressAndOptionBHistory() = runTest {
        val dbFile = context.getDatabasePath("test_file_backed_integrity.db")
        dbFile.delete() // Ensure clean start

        // 1. Create file-backed DB instance
        var fileDb = Room.databaseBuilder(context, KitsuneDatabase::class.java, dbFile.name)
            .allowMainThreadQueries()
            .build()

        val fComicDao = fileDb.comicDao()
        val fChapterDao = fileDb.chapterDao()
        val fProgressDao = fileDb.readingProgressDao()

        val comicId = fComicDao.insert(ComicEntity(rootUriString = rootUri, relativePath = "Comics/OP", title = "One Piece", isBookmarked = true, lastReadAt = 5000L))
        val chapterId = fChapterDao.insert(ChapterEntity(comicId = comicId, relativePath = "Comics/OP/Ch1.cbz", chapterNumber = 1, displayName = "Ch1.cbz", pageCount = 10))

        val progress = ReadingProgressEntity(
            comicId = comicId,
            lastReadChapterId = chapterId,
            lastReadChapterNumber = 1,
            chapterRelativePath = "Comics/OP/Ch1.cbz",
            pageIndex = 7,
            scrollOffset = 310,
            updatedAt = 5000L
        )
        fProgressDao.save(progress)

        // Execute Option B History clear (lastReadAt = null)
        fComicDao.clearHistoryForComic(comicId)

        // Close file-backed DB
        fileDb.close()

        // 2. Re-open SAME file-backed DB
        fileDb = Room.databaseBuilder(context, KitsuneDatabase::class.java, dbFile.name)
            .allowMainThreadQueries()
            .build()

        val rComicDao = fileDb.comicDao()
        val rChapterDao = fileDb.chapterDao()
        val rProgressDao = fileDb.readingProgressDao()

        // 3. Assert 100% Data Integrity After DB Reopen
        val restoredComic = rComicDao.getById(comicId)
        assertNotNull(restoredComic)
        assertNull("lastReadAt MUST remain null after DB reopen", restoredComic!!.lastReadAt)
        assertTrue("isBookmarked MUST remain true after DB reopen", restoredComic.isBookmarked)

        val restoredChapter = rChapterDao.getById(chapterId)
        assertNotNull(restoredChapter)
        assertEquals("Ch1.cbz", restoredChapter!!.displayName)

        val restoredProgress = rProgressDao.getByComicId(comicId)
        assertNotNull(restoredProgress)
        assertEquals("OPTION B: pageIndex 7 MUST be preserved after DB reopen", 7, restoredProgress!!.pageIndex)
        assertEquals("OPTION B: scrollOffset 310 MUST be preserved after DB reopen", 310, restoredProgress.scrollOffset)

        fileDb.close()
        dbFile.delete()
    }
}
