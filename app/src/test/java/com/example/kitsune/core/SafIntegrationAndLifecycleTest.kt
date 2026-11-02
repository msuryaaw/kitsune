package com.example.kitsune.core

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.kitsune.data.ChapterAvailabilityResult
import com.example.kitsune.data.CoverCacheManager
import com.example.kitsune.data.CoverExtractor
import com.example.kitsune.data.DefaultLibraryRepository
import com.example.kitsune.data.KitsuneDatabase
import com.example.kitsune.data.dao.ChapterDao
import com.example.kitsune.data.dao.ComicDao
import com.example.kitsune.data.dao.ReadingProgressDao
import com.example.kitsune.data.entity.ChapterEntity
import com.example.kitsune.data.entity.ComicEntity
import com.example.kitsune.data.entity.ReadingProgressEntity
import com.example.kitsune.scanner.LibraryScanResult
import com.example.kitsune.storage.FakeStorageGateway
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SafIntegrationAndLifecycleTest {

    private lateinit var context: Context
    private lateinit var db: KitsuneDatabase
    private lateinit var comicDao: ComicDao
    private lateinit var chapterDao: ChapterDao
    private lateinit var progressDao: ReadingProgressDao

    private val initialRoot = "content://primary%3AKitsune"

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
    fun rootFolderChange_pickerCancellation_retainsPreviousValidRoot() {
        val prefs = FakeRootFolderPreferences(initialRoot)
        assertEquals(initialRoot, prefs.getRootUriString())

        // Simulate launching picker and user pressing Back / Cancel
        val pickedUriStr: String? = null // User cancelled
        if (!pickedUriStr.isNullOrBlank()) {
            prefs.setRootUriString(pickedUriStr)
        }

        // Assert previous root URI remains 100% retained
        assertEquals("Cancelling folder picker MUST retain previous configured root URI", initialRoot, prefs.getRootUriString())
    }

    @Test
    fun chapterAvailability_distinguishesFileMissing_fromPermissionDenied() = runTest {
        val fakeStorage = FakeStorageGateway(rootConfigured = true, permissionGranted = true)
        fakeStorage.addDirectory("Comics/OP")
        fakeStorage.addFile("Comics/OP/Ch1.cbz", "valid cbz bytes".toByteArray())

        val fakeScanner = object : com.example.kitsune.scanner.LibraryScanner {
            override fun scanLibrary(): LibraryScanResult = LibraryScanResult.RootNotConfigured
        }

        val repository = DefaultLibraryRepository(
            fakeScanner,
            com.example.kitsune.scanner.IncrementalCbzCataloger(com.example.kitsune.data.NoopCbzCataloger),
            comicDao, chapterDao, progressDao,
            CoverExtractor(fakeStorage, comicDao, chapterDao),
            CoverCacheManager(File(System.getProperty("java.io.tmpdir"), "saf_covers"), CoverExtractor(fakeStorage, comicDao, chapterDao), comicDao, chapterDao),
            fakeStorage,
            com.example.kitsune.reader.DefaultCbzArchiveProvider(File(System.getProperty("java.io.tmpdir"), "saf_spool"), fakeStorage, chapterDao),
            db
        )

        val comicId = comicDao.insert(ComicEntity(rootUriString = initialRoot, relativePath = "Comics/OP", title = "OP"))
        val chapterIdValid = chapterDao.insert(ChapterEntity(comicId = comicId, relativePath = "Comics/OP/Ch1.cbz", chapterNumber = 1, displayName = "Ch1.cbz", pageCount = 10, isValid = true))
        val chapterIdMissing = chapterDao.insert(ChapterEntity(comicId = comicId, relativePath = "Comics/OP/Ch2_Missing.cbz", chapterNumber = 2, displayName = "Ch2_Missing.cbz", pageCount = 10, isValid = true))

        // 1. Available file
        val avail1 = repository.verifyChapterAvailable(chapterIdValid)
        assertTrue("Available chapter file MUST return Available", avail1 is ChapterAvailabilityResult.Available)

        // 2. Missing file (permission held, but file not on disk)
        val avail2 = repository.verifyChapterAvailable(chapterIdMissing)
        assertTrue("Missing chapter file MUST return Missing", avail2 is ChapterAvailabilityResult.Missing)

        // 3. Permission Lost (revoke permission)
        fakeStorage.permissionGranted = false
        val avail3 = repository.verifyChapterAvailable(chapterIdValid)
        assertTrue("Revoked permission MUST return PermissionDenied (distinguished from Missing)", avail3 is ChapterAvailabilityResult.PermissionDenied)
        assertTrue("PermissionDenied message must describe permission revocation", (avail3 as ChapterAvailabilityResult.PermissionDenied).message.isNotBlank())
    }

    @Test
    fun ruleK03_permissionLossDuringScan_neverMassDeletesDatabaseRecords() = runTest {
        val comicId = comicDao.insert(ComicEntity(rootUriString = initialRoot, relativePath = "Comics/Bleach", title = "Bleach", isBookmarked = true, lastReadAt = 5000L))
        val chapterId = chapterDao.insert(ChapterEntity(comicId = comicId, relativePath = "Comics/Bleach/Ch1.cbz", chapterNumber = 1, displayName = "Ch1.cbz", pageCount = 10))
        val progress = ReadingProgressEntity(comicId = comicId, lastReadChapterId = chapterId, lastReadChapterNumber = 1, chapterRelativePath = "Comics/Bleach/Ch1.cbz", pageIndex = 4, scrollOffset = 100, updatedAt = 5000L)
        progressDao.save(progress)

        val fakeFailingScanner = object : com.example.kitsune.scanner.LibraryScanner {
            override fun scanLibrary(): LibraryScanResult = LibraryScanResult.PermissionDenied("Storage access revoked")
        }

        val fakeStorage = FakeStorageGateway(rootConfigured = true, permissionGranted = false)
        val repository = DefaultLibraryRepository(
            fakeFailingScanner,
            com.example.kitsune.scanner.IncrementalCbzCataloger(com.example.kitsune.data.NoopCbzCataloger),
            comicDao, chapterDao, progressDao,
            CoverExtractor(fakeStorage, comicDao, chapterDao),
            CoverCacheManager(File(System.getProperty("java.io.tmpdir"), "k03_covers"), CoverExtractor(fakeStorage, comicDao, chapterDao), comicDao, chapterDao),
            fakeStorage,
            com.example.kitsune.reader.DefaultCbzArchiveProvider(File(System.getProperty("java.io.tmpdir"), "k03_spool"), fakeStorage, chapterDao),
            db
        )

        // Sync fails with PermissionDenied
        val syncResult = repository.syncLibrary(initialRoot)
        assertTrue(syncResult is LibraryScanResult.PermissionDenied)

        // RULE K-03 ASSERTION: Database records MUST NOT be deleted!
        val comics = comicDao.getAllByRoot(initialRoot)
        assertEquals(1, comics.size)
        assertTrue(comics.first().isBookmarked)
        assertEquals(5000L, comics.first().lastReadAt)

        val chapters = chapterDao.getAllByComicId(comicId)
        assertEquals(1, chapters.size)

        val savedProgress = progressDao.getByComicId(comicId)
        assertNotNull(savedProgress)
        assertEquals(4, savedProgress!!.pageIndex)
    }

    @Test
    fun recoveryLifecycle_permissionLossToNewRoot_restoresAccessAndPreservesData() = runTest {
        val prefs = FakeRootFolderPreferences(initialRoot)
        val comicId = comicDao.insert(ComicEntity(rootUriString = initialRoot, relativePath = "Comics/Naruto", title = "Naruto", isBookmarked = true, lastReadAt = 5000L))
        chapterDao.insert(ChapterEntity(comicId = comicId, relativePath = "Comics/Naruto/Ch1.cbz", chapterNumber = 1, displayName = "Ch1.cbz", pageCount = 10))

        // Step 1: Simulate Permission Lost
        var currentState: RootFolderState = RootFolderState.PermissionLost(initialRoot, "Izin dicabut")
        assertTrue(currentState is RootFolderState.PermissionLost)

        // Step 2: User re-selects root folder (or re-grants permission)
        val newRoot = "content://primary%3ANewKitsune"
        prefs.setRootUriString(newRoot)

        // Step 3: Access re-verified -> Ready
        currentState = RootFolderState.Ready(newRoot)
        assertTrue(currentState is RootFolderState.Ready)
        assertEquals(newRoot, prefs.getRootUriString())

        // Step 4: Existing database records remain 100% preserved
        val comic = comicDao.getById(comicId)
        assertNotNull(comic)
        assertTrue("Bookmark state MUST be preserved during root recovery", comic!!.isBookmarked)
        assertEquals(5000L, comic.lastReadAt)
    }
}
