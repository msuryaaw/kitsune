package com.example.kitsune.ui.screens.detail

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.kitsune.data.ChapterAvailabilityResult
import com.example.kitsune.data.CoverCacheManager
import com.example.kitsune.data.CoverCacheResult
import com.example.kitsune.data.CoverExtractor
import com.example.kitsune.data.DefaultLibraryRepository
import com.example.kitsune.data.KitsuneDatabase
import com.example.kitsune.data.LibraryRepository
import com.example.kitsune.data.NoopCbzCataloger
import com.example.kitsune.data.entity.BookmarkEntity
import com.example.kitsune.data.entity.ChapterEntity
import com.example.kitsune.data.entity.ComicEntity
import com.example.kitsune.data.entity.ReadingProgressEntity
import com.example.kitsune.scanner.IncrementalCbzCataloger
import com.example.kitsune.scanner.LibraryScanner
import com.example.kitsune.scanner.LibraryScanResult
import com.example.kitsune.storage.FakeStorageGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ComicDetailViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var db: KitsuneDatabase
    private lateinit var repository: LibraryRepository
    private val rootUri = "content://root"

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, KitsuneDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val fakeScanner = FakeDetailScanner()
        val noopCataloger = IncrementalCbzCataloger(NoopCbzCataloger)
        val fakeStorage = FakeStorageGateway(rootConfigured = true, permissionGranted = true)
        val coverExtractor = CoverExtractor(fakeStorage, db.comicDao(), db.chapterDao())
        val coverCacheManager = CoverCacheManager(
            File(System.getProperty("java.io.tmpdir"), "test_detail_covers"),
            coverExtractor, db.comicDao(), db.chapterDao()
        )
        val cbzProvider = com.example.kitsune.reader.DefaultCbzArchiveProvider(
            spoolDir = File(System.getProperty("java.io.tmpdir"), "test_detail_spool"),
            storageGateway = fakeStorage,
            chapterDao = db.chapterDao()
        )

        repository = DefaultLibraryRepository(
            fakeScanner, noopCataloger, db.comicDao(), db.chapterDao(), db.readingProgressDao(), coverExtractor, coverCacheManager, fakeStorage, cbzProvider, db
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    @Test
    fun uiState_emitsSuccessWithComicChaptersAndProgress() = runTest {
        val comicDao = db.comicDao()
        val chapterDao = db.chapterDao()
        val progressDao = db.readingProgressDao()

        val comic = ComicEntity(rootUriString = rootUri, relativePath = "Comics/Bleach", title = "Bleach", author = "Kubo")
        val comicId = comicDao.insert(comic)

        val ch1 = ChapterEntity(comicId = comicId, relativePath = "Comics/Bleach/Ch1.cbz", chapterNumber = 1, displayName = "Ch1.cbz", pageCount = 20)
        val ch1Id = chapterDao.insert(ch1)

        val progress = ReadingProgressEntity(comicId = comicId, lastReadChapterId = ch1Id, lastReadChapterNumber = 1, chapterRelativePath = "Comics/Bleach/Ch1.cbz", pageIndex = 10, updatedAt = 1000L)
        progressDao.save(progress)

        val viewModel = ComicDetailViewModel(comicId, repository)

        val state = viewModel.uiState.first { it is ComicDetailUiState.Success } as ComicDetailUiState.Success

        assertEquals("Bleach", state.comic.title)
        assertEquals("Kubo", state.comic.author)
        assertEquals(1, state.chapters.size)
        assertNotNull(state.progress)
        assertEquals(ch1Id, state.progress?.lastReadChapterId)
        assertEquals(10, state.progress?.pageIndex)
    }

    @Test
    fun updateComicCollections_managesMultiCollectionMembershipsExplicitly_perK32() = runTest {
        val comicDao = db.comicDao()
        val bookmarkDao = db.bookmarkDao()

        val comic = ComicEntity(rootUriString = rootUri, relativePath = "Comics/Naruto", title = "Naruto")
        val comicId = comicDao.insert(comic)

        val col1Id = repository.createBookmarkCollection("Shonen")
        val col2Id = repository.createBookmarkCollection("Ninja")

        // Direct repository & DAO test for K-32 multi-collection membership management
        repository.addComicToCollection(col1Id, comicId)
        repository.addComicToCollection(col2Id, comicId)

        val cols = bookmarkDao.getCollectionsForComic(comicId)
        assertEquals(2, cols.size)

        // Remove Col1, keep Col2
        repository.removeComicFromCollection(col1Id, comicId)

        val updatedCols = bookmarkDao.getCollectionsForComic(comicId)
        assertEquals(1, updatedCols.size)
        assertEquals(col2Id, updatedCols.first().id)
    }

    @Test
    fun getCoverFile_andReleaseCoverFile_executeWithoutError() = runTest {
        val comicDao = db.comicDao()
        val comic = ComicEntity(rootUriString = rootUri, relativePath = "Comics/OnePiece", title = "One Piece")
        val comicId = comicDao.insert(comic)

        val viewModel = ComicDetailViewModel(comicId, repository)

        val cacheResult = viewModel.getCoverFile(comicId)
        assertNotNull(cacheResult)

        val tempFile = File(System.getProperty("java.io.tmpdir"), "test_detail_cover.tmp")
        viewModel.releaseCoverFile(tempFile)
    }

    private class FakeDetailScanner : LibraryScanner {
        override fun scanLibrary(): LibraryScanResult = LibraryScanResult.RootNotConfigured
    }
}
