package com.example.kitsune.ui.screens.home

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.kitsune.core.RootFolderPreferences
import com.example.kitsune.data.CoverCacheManager
import com.example.kitsune.data.DefaultLibraryRepository
import com.example.kitsune.data.KitsuneDatabase
import com.example.kitsune.data.LibraryRepository
import com.example.kitsune.data.NoopCbzCataloger
import com.example.kitsune.data.entity.ChapterEntity
import com.example.kitsune.data.entity.ComicEntity
import com.example.kitsune.data.entity.ReadingProgressEntity
import com.example.kitsune.data.CoverExtractor
import com.example.kitsune.scanner.IncrementalCbzCataloger
import com.example.kitsune.scanner.LibraryScanner
import com.example.kitsune.scanner.LibraryScanResult
import com.example.kitsune.storage.FakeStorageGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class HomeViewModelTest {

    @get:org.junit.Rule
    val tempFolder = org.junit.rules.TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var db: KitsuneDatabase
    private lateinit var repository: LibraryRepository
    private lateinit var rootPrefs: FakeHomeRootPrefs
    private lateinit var viewModel: HomeViewModel

    private val rootUri = "content://root"

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, KitsuneDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val fakeScanner = FakeHomeLibraryScanner()
        val noopCataloger = IncrementalCbzCataloger(NoopCbzCataloger)
        val fakeStorage = FakeStorageGateway()
        val coverExtractor = CoverExtractor(fakeStorage, db.comicDao(), db.chapterDao())
        val coverCacheManager = CoverCacheManager(
            File(System.getProperty("java.io.tmpdir"), "test_home_covers"),
            coverExtractor, db.comicDao(), db.chapterDao()
        )
        val cbzProvider = com.example.kitsune.reader.DefaultCbzArchiveProvider(
            spoolDir = File(System.getProperty("java.io.tmpdir"), "test_home_spool"),
            storageGateway = fakeStorage,
            chapterDao = db.chapterDao()
        )

        repository = DefaultLibraryRepository(
            fakeScanner, noopCataloger, db.comicDao(), db.chapterDao(), db.readingProgressDao(), coverExtractor, coverCacheManager, fakeStorage, cbzProvider, db
        )
        rootPrefs = FakeHomeRootPrefs(rootUri)
        viewModel = HomeViewModel(repository, rootPrefs)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    @Test
    fun uiState_noReadingProgress_returnsSuccessWithNullLastRead() = runTest {
        val comic = ComicEntity(rootUriString = rootUri, relativePath = "Comics/Bleach", title = "Bleach")
        db.comicDao().insert(comic)

        val state = viewModel.uiState.filter { it is HomeUiState.Success }.first() as HomeUiState.Success
        assertNull(state.lastReadItem)
        assertEquals(1, state.libraryCount)
        assertEquals(0, state.historyCount)
    }

    @Test
    fun uiState_populatedReadingProgress_returnsSuccessWithLatestLastRead() = runTest {
        val comicDao = db.comicDao()
        val chapterDao = db.chapterDao()
        val progressDao = db.readingProgressDao()

        val comic = ComicEntity(rootUriString = rootUri, relativePath = "Comics/Naruto", title = "Naruto", author = "Kishimoto")
        val comicId = comicDao.insert(comic)

        val chapter = ChapterEntity(comicId = comicId, relativePath = "Comics/Naruto/Ch1.cbz", chapterNumber = 1, displayName = "Ch1.cbz", pageCount = 20)
        val chapterId = chapterDao.insert(chapter)

        val progress = ReadingProgressEntity(
            comicId = comicId,
            lastReadChapterId = chapterId,
            lastReadChapterNumber = 1,
            chapterRelativePath = "Comics/Naruto/Ch1.cbz",
            pageIndex = 5,
            updatedAt = 5000L
        )
        progressDao.save(progress)
        comicDao.updateLastReadAt(comicId, 5000L)

        val state = viewModel.uiState.filter { it is HomeUiState.Success }.first() as HomeUiState.Success

        assertNotNull(state.lastReadItem)
        assertEquals("Naruto", state.lastReadItem?.comic?.title)
        assertEquals(1, state.lastReadItem?.progress?.lastReadChapterNumber)
        assertEquals(5, state.lastReadItem?.progress?.pageIndex)
        assertEquals(20, state.lastReadItem?.totalPages)
        assertEquals(1, state.libraryCount)
        assertEquals(1, state.historyCount)
    }

    @Test
    fun getCoverFile_andReleaseCoverFile_executeWithoutError() = runTest {
        val comicDao = db.comicDao()
        val comic = ComicEntity(rootUriString = rootUri, relativePath = "Comics/OnePiece", title = "One Piece")
        val comicId = comicDao.insert(comic)

        val cacheResult = viewModel.getCoverFile(comicId)
        assertNotNull(cacheResult)

        val tempFile = tempFolder.newFile("test_cover.tmp")
        viewModel.releaseCoverFile(tempFile)
    }

    private class FakeHomeLibraryScanner : LibraryScanner {
        override fun scanLibrary(): LibraryScanResult = LibraryScanResult.RootNotConfigured
    }

    private class FakeHomeRootPrefs(initialRoot: String? = null) : RootFolderPreferences {
        val rootUriFlow = MutableStateFlow(initialRoot)
        override fun getRootUriString(): String? = rootUriFlow.value
        override fun setRootUriString(uriString: String?) { rootUriFlow.value = uriString }
        override fun observeRootUriString(): Flow<String?> = rootUriFlow
    }
}
