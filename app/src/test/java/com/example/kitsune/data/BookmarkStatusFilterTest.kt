package com.example.kitsune.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.kitsune.data.entity.BookmarkEntity
import com.example.kitsune.data.entity.ChapterEntity
import com.example.kitsune.data.entity.ComicEntity
import com.example.kitsune.data.entity.ReadingProgressEntity
import com.example.kitsune.scanner.IncrementalCbzCataloger
import com.example.kitsune.scanner.LibraryScanner
import com.example.kitsune.scanner.LibraryScanResult
import com.example.kitsune.storage.FakeStorageGateway
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BookmarkStatusFilterTest {

    private lateinit var db: KitsuneDatabase
    private lateinit var repository: LibraryRepository

    private val rootUri = "content://root"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, KitsuneDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val fakeScanner = FakeFilterLibraryScanner()
        val noopCataloger = IncrementalCbzCataloger(NoopCbzCataloger)
        val fakeStorage = FakeStorageGateway()
        val coverExtractor = CoverExtractor(fakeStorage, db.comicDao(), db.chapterDao())
        val coverCacheManager = CoverCacheManager(
            File(System.getProperty("java.io.tmpdir"), "test_filter_covers"),
            coverExtractor, db.comicDao(), db.chapterDao()
        )
        val cbzProvider = com.example.kitsune.reader.DefaultCbzArchiveProvider(
            spoolDir = File(System.getProperty("java.io.tmpdir"), "test_filter_spool"),
            storageGateway = fakeStorage,
            chapterDao = db.chapterDao()
        )

        repository = DefaultLibraryRepository(
            fakeScanner, noopCataloger, db.comicDao(), db.chapterDao(), db.readingProgressDao(), coverExtractor, coverCacheManager, fakeStorage, cbzProvider, db
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun freshInstall_hasZeroBookmarkCollections() = runTest {
        val collections = db.bookmarkDao().observeAllCollections().first()
        assertEquals("Fresh install MUST have zero bookmark collections", 0, collections.size)
    }

    @Test
    fun isComicCompleted_derivedStateCalculation_perDecisionK30() = runTest {
        val comicDao = db.comicDao()
        val chapterDao = db.chapterDao()
        val progressDao = db.readingProgressDao()

        val comic = ComicEntity(rootUriString = rootUri, relativePath = "Comics/Naruto", title = "Naruto")
        val comicId = comicDao.insert(comic)

        val ch1 = ChapterEntity(comicId = comicId, relativePath = "Comics/Naruto/Ch1.cbz", chapterNumber = 1, displayName = "Ch1.cbz", pageCount = 10)
        val ch2 = ChapterEntity(comicId = comicId, relativePath = "Comics/Naruto/Ch2.cbz", chapterNumber = 2, displayName = "Ch2.cbz", pageCount = 10)
        val ch1Id = chapterDao.insert(ch1)
        val ch2Id = chapterDao.insert(ch2)

        val validChapters = chapterDao.getAllByComicId(comicId)

        // 1. No progress -> Not Completed
        var progress = progressDao.getByComicId(comicId)
        assertFalse(isComicCompleted(progress, validChapters))

        // 2. Progress on Ch1 (not last chapter) -> Not Completed
        progress = ReadingProgressEntity(comicId = comicId, lastReadChapterId = ch1Id, lastReadChapterNumber = 1, chapterRelativePath = "Comics/Naruto/Ch1.cbz", pageIndex = 9, updatedAt = 1000L)
        progressDao.save(progress)
        assertFalse(isComicCompleted(progressDao.getByComicId(comicId), validChapters))

        // 3. Progress on Ch2 page 5 of 10 -> Not Completed
        progress = ReadingProgressEntity(comicId = comicId, lastReadChapterId = ch2Id, lastReadChapterNumber = 2, chapterRelativePath = "Comics/Naruto/Ch2.cbz", pageIndex = 5, updatedAt = 2000L)
        progressDao.save(progress)
        assertFalse(isComicCompleted(progressDao.getByComicId(comicId), validChapters))

        // 4. Progress on Ch2 page 9 of 10 (last page of last chapter) -> Completed!
        progress = ReadingProgressEntity(comicId = comicId, lastReadChapterId = ch2Id, lastReadChapterNumber = 2, chapterRelativePath = "Comics/Naruto/Ch2.cbz", pageIndex = 9, updatedAt = 3000L)
        progressDao.save(progress)
        assertTrue(isComicCompleted(progressDao.getByComicId(comicId), validChapters))
    }

    private fun isComicCompleted(progress: ReadingProgressEntity?, validChapters: List<ChapterEntity>): Boolean {
        if (progress == null || validChapters.isEmpty()) return false
        val finalChapter = validChapters.maxByOrNull { it.chapterNumber } ?: return false
        return progress.lastReadChapterId == finalChapter.id && progress.pageIndex >= finalChapter.pageCount - 1
    }

    private class FakeFilterLibraryScanner : LibraryScanner {
        override fun scanLibrary(): LibraryScanResult = LibraryScanResult.RootNotConfigured
    }
}
