package com.example.kitsune.ui.screens.reader

import com.example.kitsune.data.CoverCacheResult
import com.example.kitsune.data.CoverResult
import com.example.kitsune.data.LibraryRepository
import com.example.kitsune.data.SettingsRepository
import com.example.kitsune.data.entity.ChapterEntity
import com.example.kitsune.data.entity.ComicEntity
import com.example.kitsune.data.entity.ReadingProgressEntity
import com.example.kitsune.data.entity.SettingsEntity
import com.example.kitsune.reader.CbzArchiveHandle
import com.example.kitsune.reader.CbzArchiveResult
import com.example.kitsune.reader.CbzPageDecoder
import com.example.kitsune.reader.CbzPageDecoderFactory
import com.example.kitsune.reader.CbzPageIndexer
import com.example.kitsune.reader.ChapterPageIndex
import com.example.kitsune.reader.PageDecodeResult
import com.example.kitsune.reader.PageIndexResult
import com.example.kitsune.reader.PageMetadata
import com.example.kitsune.scanner.LibraryScanResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config
import org.junit.rules.TemporaryFolder
import java.io.File

class FakeReaderLibraryRepository : LibraryRepository {
    var openResult: CbzArchiveResult = CbzArchiveResult.ChapterMissing
    val chaptersById = mutableMapOf<Long, ChapterEntity>()
    val progressByComicId = mutableMapOf<Long, ReadingProgressEntity>()
    val savedProgressList = mutableListOf<ReadingProgressEntity>()
    val flushedProgressList = mutableListOf<ReadingProgressEntity>()

    override suspend fun openCbzArchive(chapterId: Long): CbzArchiveResult = openResult
    override suspend fun getChapterById(chapterId: Long): ChapterEntity? = chaptersById[chapterId]
    override suspend fun getReadingProgress(comicId: Long): ReadingProgressEntity? = progressByComicId[comicId]
    override suspend fun saveReadingProgress(progress: ReadingProgressEntity) {
        savedProgressList.add(progress)
        progressByComicId[progress.comicId] = progress
    }
    override fun flushReadingProgress(progress: ReadingProgressEntity) {
        flushedProgressList.add(progress)
        progressByComicId[progress.comicId] = progress
    }

    override suspend fun syncLibrary(rootUri: String): LibraryScanResult = LibraryScanResult.RootNotConfigured
    override fun observeComicsByRoot(rootUri: String): Flow<List<ComicEntity>> = flowOf(emptyList())
    override fun observeBookmarkedComicsByRoot(rootUri: String): Flow<List<ComicEntity>> = flowOf(emptyList())
    override fun observeRecentlyReadByRoot(rootUri: String): Flow<List<ComicEntity>> = flowOf(emptyList())
    override fun observeChaptersByComicId(comicId: Long): Flow<List<ChapterEntity>> = flowOf(emptyList())
    override fun observeComicById(comicId: Long): Flow<ComicEntity?> = flowOf(null)
    override suspend fun verifyChapterAvailable(chapterId: Long) = com.example.kitsune.data.ChapterAvailabilityResult.Missing
    override suspend fun setBookmarked(comicId: Long, bookmarked: Boolean) {}
    override suspend fun fetchCover(comicId: Long): CoverResult = CoverResult.Unavailable("Fake")
    override suspend fun getCoverFile(comicId: Long): CoverCacheResult = CoverCacheResult.Unavailable("Fake")
    override suspend fun releaseCoverFile(file: File) {}
    override fun observeLastScanResult(): Flow<LibraryScanResult?> = flowOf(null)
}

class FakeReaderSettingsRepository(initialSettings: SettingsEntity = SettingsEntity()) : SettingsRepository {
    val settingsFlow = MutableStateFlow(initialSettings)
    override fun observeSettings(): Flow<SettingsEntity> = settingsFlow
    override suspend fun setGridColumns(columns: Int) {}
    override suspend fun setReaderMode(readerMode: String) {
        settingsFlow.value = settingsFlow.value.copy(readerMode = readerMode)
    }
    override suspend fun setReadingDirection(readingDirection: String) {
        settingsFlow.value = settingsFlow.value.copy(readingDirection = readingDirection)
    }
}

class FakeCbzPageIndexer : CbzPageIndexer {
    var indexResult: PageIndexResult = PageIndexResult.NoImagesFound
    override suspend fun buildIndex(chapterId: Long, archiveFile: File): PageIndexResult = indexResult
}

class FakeCbzPageDecoder(
    override val pageIndex: ChapterPageIndex
) : CbzPageDecoder {
    var closed = false
    override suspend fun decodePage(pageIndex: Int, targetWidth: Int, targetHeight: Int): PageDecodeResult =
        PageDecodeResult.PageNotFound(pageIndex)
    override fun close() { closed = true }
}

class FakeCbzPageDecoderFactory(
    private val decoderToReturn: FakeCbzPageDecoder
) : CbzPageDecoderFactory {
    var createCount = 0
    val createdDecoders = mutableListOf<CbzPageDecoder>()

    override fun createDecoder(pageIndex: ChapterPageIndex, archiveHandle: CbzArchiveHandle): CbzPageDecoder {
        createCount++
        createdDecoders.add(decoderToReturn)
        return decoderToReturn
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ReaderViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var fakeRepo: FakeReaderLibraryRepository
    private lateinit var fakeSettingsRepo: FakeReaderSettingsRepository
    private lateinit var fakeIndexer: FakeCbzPageIndexer
    private lateinit var fakeDecoder: FakeCbzPageDecoder
    private lateinit var fakeDecoderFactory: FakeCbzPageDecoderFactory

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeRepo = FakeReaderLibraryRepository()
        fakeSettingsRepo = FakeReaderSettingsRepository()
        fakeIndexer = FakeCbzPageIndexer()

        val samplePage = PageMetadata(pageIndex = 0, entryName = "p1.jpg", normalizedPath = "p1.jpg")
        val pageIndex = ChapterPageIndex(chapterId = 1L, pages = listOf(samplePage))
        fakeDecoder = FakeCbzPageDecoder(pageIndex)
        fakeDecoderFactory = FakeCbzPageDecoderFactory(fakeDecoder)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun uiState_initialValueIsLoading() = runTest {
        val slowRepo = object : LibraryRepository by fakeRepo {
            override suspend fun openCbzArchive(chapterId: Long): CbzArchiveResult {
                kotlinx.coroutines.delay(1000)
                return CbzArchiveResult.ChapterMissing
            }
        }
        val viewModel = ReaderViewModel(
            chapterId = 1L,
            libraryRepository = slowRepo,
            settingsRepository = fakeSettingsRepo,
            cbzPageIndexer = fakeIndexer,
            cbzPageDecoderFactory = fakeDecoderFactory
        )
        assertEquals(ReaderUiState.Loading, viewModel.uiState.value)
    }

    @Test
    fun uiState_emitsSuccess_whenArchiveOpenedAndIndexed() = runTest {
        val spooledFile = tempFolder.newFile("spool.cbz")
        var handleClosed = false
        val handle = CbzArchiveHandle(1L, spooledFile) { handleClosed = true }

        fakeRepo.openResult = CbzArchiveResult.Success(handle)

        val samplePage = PageMetadata(pageIndex = 0, entryName = "p1.jpg", normalizedPath = "p1.jpg")
        val pageIndex = ChapterPageIndex(chapterId = 1L, pages = listOf(samplePage))
        fakeIndexer.indexResult = PageIndexResult.Success(pageIndex)

        val viewModel = ReaderViewModel(
            chapterId = 1L,
            libraryRepository = fakeRepo,
            settingsRepository = fakeSettingsRepo,
            cbzPageIndexer = fakeIndexer,
            cbzPageDecoderFactory = fakeDecoderFactory
        )

        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }

        val currentState = viewModel.uiState.value
        assertTrue("State must be Success", currentState is ReaderUiState.Success)
        val success = currentState as ReaderUiState.Success
        assertEquals(1L, success.chapterId)
        assertEquals("single_page", success.readerMode)
        assertEquals(0, success.currentPageIndex)

        collectJob.cancel()
    }

    @Test
    fun uiState_emitsError_whenArchiveMissing() = runTest {
        fakeRepo.openResult = CbzArchiveResult.ChapterMissing

        val viewModel = ReaderViewModel(
            chapterId = 1L,
            libraryRepository = fakeRepo,
            settingsRepository = fakeSettingsRepo,
            cbzPageIndexer = fakeIndexer,
            cbzPageDecoderFactory = fakeDecoderFactory
        )

        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }

        val currentState = viewModel.uiState.value
        assertTrue("State must be Error", currentState is ReaderUiState.Error)
        assertEquals("File chapter tidak ditemukan di penyimpanan", (currentState as ReaderUiState.Error).message)

        collectJob.cancel()
    }

    @Test
    fun setReaderMode_updatesSettingsRepository() = runTest {
        val spooledFile = tempFolder.newFile("spool.cbz")
        val handle = CbzArchiveHandle(1L, spooledFile) {}
        fakeRepo.openResult = CbzArchiveResult.Success(handle)

        val samplePage = PageMetadata(pageIndex = 0, entryName = "p1.jpg", normalizedPath = "p1.jpg")
        fakeIndexer.indexResult = PageIndexResult.Success(ChapterPageIndex(chapterId = 1L, pages = listOf(samplePage)))

        val viewModel = ReaderViewModel(
            chapterId = 1L,
            libraryRepository = fakeRepo,
            settingsRepository = fakeSettingsRepo,
            cbzPageIndexer = fakeIndexer,
            cbzPageDecoderFactory = fakeDecoderFactory
        )

        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }

        viewModel.setReaderMode("vertical_continuous")

        val currentState = viewModel.uiState.value
        assertTrue(currentState is ReaderUiState.Success)
        assertEquals("vertical_continuous", (currentState as ReaderUiState.Success).readerMode)

        collectJob.cancel()
    }

    @Test
    fun indexingFailure_closesPendingHandleWithoutLeak() = runTest {
        val spooledFile = tempFolder.newFile("spool_corrupt.cbz")
        var handleClosed = false
        val handle = CbzArchiveHandle(1L, spooledFile) { handleClosed = true }

        fakeRepo.openResult = CbzArchiveResult.Success(handle)
        fakeIndexer.indexResult = PageIndexResult.CorruptArchive("Header invalid")

        val viewModel = ReaderViewModel(
            chapterId = 1L,
            libraryRepository = fakeRepo,
            settingsRepository = fakeSettingsRepo,
            cbzPageIndexer = fakeIndexer,
            cbzPageDecoderFactory = fakeDecoderFactory
        )

        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }

        assertTrue("Pending handle MUST be closed when indexing fails", handleClosed)
        assertTrue(viewModel.uiState.value is ReaderUiState.Error)

        collectJob.cancel()
    }

    @Test
    fun realViewModelToRoomApplicationFlush_persistsProgressInRoomDatabaseOnCleared() = runTest {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, com.example.kitsune.data.KitsuneDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val realComicDao = db.comicDao()
        val realChapterDao = db.chapterDao()
        val realProgressDao = db.readingProgressDao()

        val comicId = realComicDao.insert(ComicEntity(rootUriString = "content://root", relativePath = "Comics/OP", title = "OP"))
        val chapterId = realChapterDao.insert(ChapterEntity(comicId = comicId, relativePath = "Comics/OP/Ch1.cbz", chapterNumber = 1, displayName = "Ch1.cbz", pageCount = 10))

        val testAppScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + UnconfinedTestDispatcher(testScheduler))

        val realRepo = com.example.kitsune.data.DefaultLibraryRepository(
            com.example.kitsune.data.FakeLibraryScanner(),
            com.example.kitsune.scanner.IncrementalCbzCataloger(com.example.kitsune.data.NoopCbzCataloger),
            realComicDao, realChapterDao, realProgressDao,
            com.example.kitsune.data.CoverExtractor(com.example.kitsune.storage.FakeStorageGateway(), realComicDao, realChapterDao),
            com.example.kitsune.data.CoverCacheManager(java.io.File(System.getProperty("java.io.tmpdir"), "real_vm_covers"), com.example.kitsune.data.CoverExtractor(com.example.kitsune.storage.FakeStorageGateway(), realComicDao, realChapterDao), realComicDao, realChapterDao),
            com.example.kitsune.storage.FakeStorageGateway(),
            com.example.kitsune.reader.DefaultCbzArchiveProvider(java.io.File(System.getProperty("java.io.tmpdir"), "real_vm_spool"), com.example.kitsune.storage.FakeStorageGateway(), realChapterDao),
            db,
            applicationScope = testAppScope
        )

        val spooledFile = tempFolder.newFile("spool_real_vm.cbz")
        val handle = CbzArchiveHandle(chapterId, spooledFile) {}

        val pages = (1..10).map { i -> PageMetadata(pageIndex = i - 1, entryName = "p$i.jpg", normalizedPath = "p$i.jpg") }
        fakeIndexer.indexResult = PageIndexResult.Success(ChapterPageIndex(chapterId = chapterId, pages = pages))

        val realRepoWrapper = object : LibraryRepository by realRepo {
            override suspend fun openCbzArchive(cId: Long): CbzArchiveResult = CbzArchiveResult.Success(handle)
        }

        val viewModel = ReaderViewModel(
            chapterId = chapterId,
            libraryRepository = realRepoWrapper,
            settingsRepository = fakeSettingsRepo,
            cbzPageIndexer = fakeIndexer,
            cbzPageDecoderFactory = fakeDecoderFactory
        )

        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }

        // Allow Room background query executor to complete getChapterById and transition to Success
        var stateBefore: ReaderUiState = ReaderUiState.Loading
        for (step in 1..50) {
            testDispatcher.scheduler.advanceTimeBy(50)
            stateBefore = viewModel.uiState.value
            if (stateBefore is ReaderUiState.Success) break
            Thread.sleep(20)
        }
        assertTrue("ViewModel state must be Success, but was $stateBefore", stateBefore is ReaderUiState.Success)

        // Update reader position to page 8 and scroll offset 250
        viewModel.setCurrentPageIndexAndOffset(8, 250)

        // Delete progress from Room before onCleared to strictly prove onCleared() flush re-persists the progress to Room
        realProgressDao.deleteByComicId(comicId)
        assertNull(realProgressDao.getByComicId(comicId))

        // Destroy ViewModel (onCleared)
        val viewModelStore = androidx.lifecycle.ViewModelStore()
        viewModelStore.put("reader_vm", viewModel)
        viewModelStore.clear()

        // Allow Room background transaction thread to complete SQLite transaction
        var retrievedProgress: ReadingProgressEntity? = null
        for (step in 1..20) {
            retrievedProgress = realProgressDao.getByComicId(comicId)
            if (retrievedProgress != null) break
            Thread.sleep(50)
        }

        // Assert that progress IS REALLY COMMITTED AND PERSISTED in the Room DB!
        assertNotNull(retrievedProgress)
        assertEquals(8, retrievedProgress!!.pageIndex)
        assertEquals(250, retrievedProgress.scrollOffset)

        val retrievedComic = realComicDao.getById(comicId)!!
        assertEquals(retrievedProgress.updatedAt, retrievedComic.lastReadAt)

        collectJob.cancel()
        db.close()
    }

    @Test
    fun loadChapterAndInitialize_differentChapterProgress_seedsTimestampGeneratorAndSavesChapter2Successfully() = runTest {
        val spooledFile = tempFolder.newFile("spool_ch2.cbz")
        val handle = CbzArchiveHandle(202L, spooledFile) {}
        fakeRepo.openResult = CbzArchiveResult.Success(handle)

        // Chapter 2 being opened
        val chapter2 = ChapterEntity(id = 202L, comicId = 1L, relativePath = "Comics/OP/Ch2.cbz", chapterNumber = 2, displayName = "Ch2.cbz", pageCount = 10, isValid = true)
        fakeRepo.chaptersById[202L] = chapter2

        // Prior progress exists on Chapter 1 (id = 101L) with timestamp 5000L
        val savedProgressCh1 = ReadingProgressEntity(
            comicId = 1L,
            lastReadChapterId = 101L,
            lastReadChapterNumber = 1,
            chapterRelativePath = "Comics/OP/Ch1.cbz",
            pageIndex = 8,
            updatedAt = 5000L
        )
        fakeRepo.progressByComicId[1L] = savedProgressCh1

        val pagesCh2 = (1..10).map { i -> PageMetadata(pageIndex = i - 1, entryName = "p$i.jpg", normalizedPath = "p$i.jpg") }
        fakeIndexer.indexResult = PageIndexResult.Success(ChapterPageIndex(chapterId = 202L, pages = pagesCh2))

        val viewModel = ReaderViewModel(
            chapterId = 202L,
            libraryRepository = fakeRepo,
            settingsRepository = fakeSettingsRepo,
            cbzPageIndexer = fakeIndexer,
            cbzPageDecoderFactory = fakeDecoderFactory
        )

        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }

        val currentState = viewModel.uiState.value as ReaderUiState.Success
        // Chapter 1 progress MUST NOT be restored for Chapter 2 -> defaults to page 0
        assertEquals(0, currentState.currentPageIndex)

        // Save new progress on Chapter 2
        viewModel.setCurrentPageIndexAndOffset(1, 0)
        testDispatcher.scheduler.advanceTimeBy(600)

        // Verify Chapter 2 progress was saved with timestamp > 5000L and NOT rejected
        val savedCh2 = fakeRepo.savedProgressList.last()
        assertEquals(202L, savedCh2.lastReadChapterId)
        assertEquals(1, savedCh2.pageIndex)
        assertTrue("Chapter 2 progress timestamp must be seeded > 5000L", savedCh2.updatedAt > 5000L)

        collectJob.cancel()
    }

    @Test
    fun modeSwitch_preservesCurrentPageIndex() = runTest {
        val spooledFile = tempFolder.newFile("spool.cbz")
        val handle = CbzArchiveHandle(1L, spooledFile) {}
        fakeRepo.openResult = CbzArchiveResult.Success(handle)

        val pages = listOf(
            PageMetadata(pageIndex = 0, entryName = "p1.jpg", normalizedPath = "p1.jpg"),
            PageMetadata(pageIndex = 1, entryName = "p2.jpg", normalizedPath = "p2.jpg"),
            PageMetadata(pageIndex = 2, entryName = "p3.jpg", normalizedPath = "p3.jpg")
        )
        fakeIndexer.indexResult = PageIndexResult.Success(ChapterPageIndex(chapterId = 1L, pages = pages))

        val viewModel = ReaderViewModel(
            chapterId = 1L,
            libraryRepository = fakeRepo,
            settingsRepository = fakeSettingsRepo,
            cbzPageIndexer = fakeIndexer,
            cbzPageDecoderFactory = fakeDecoderFactory
        )

        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }

        // Set page index to 2
        viewModel.setCurrentPageIndex(2)
        assertEquals(2, (viewModel.uiState.value as ReaderUiState.Success).currentPageIndex)

        // Switch mode to vertical_continuous
        viewModel.setReaderMode("vertical_continuous")

        val stateAfterModeChange = viewModel.uiState.value as ReaderUiState.Success
        assertEquals("vertical_continuous", stateAfterModeChange.readerMode)
        assertEquals("currentPageIndex MUST be preserved across mode switch", 2, stateAfterModeChange.currentPageIndex)

        collectJob.cancel()
    }

    @Test
    fun modeSwitch_reusesSameDecoderInstance_andDoesNotCloseArchiveHandle() = runTest {
        val spooledFile = tempFolder.newFile("spool.cbz")
        var handleClosed = false
        val handle = CbzArchiveHandle(1L, spooledFile) { handleClosed = true }
        fakeRepo.openResult = CbzArchiveResult.Success(handle)

        val samplePage = PageMetadata(pageIndex = 0, entryName = "p1.jpg", normalizedPath = "p1.jpg")
        fakeIndexer.indexResult = PageIndexResult.Success(ChapterPageIndex(chapterId = 1L, pages = listOf(samplePage)))

        val viewModel = ReaderViewModel(
            chapterId = 1L,
            libraryRepository = fakeRepo,
            settingsRepository = fakeSettingsRepo,
            cbzPageIndexer = fakeIndexer,
            cbzPageDecoderFactory = fakeDecoderFactory
        )

        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }

        // Switch mode back and forth
        viewModel.setReaderMode("vertical_continuous")
        viewModel.setReaderMode("single_page")

        assertFalse("Archive handle MUST NOT be closed on mode switch", handleClosed)
        assertFalse("Decoder MUST NOT be closed on mode switch", fakeDecoder.closed)

        collectJob.cancel()
    }

    @Test
    fun goToNextPage_incrementsPage_boundedByTotalPages() = runTest {
        val spooledFile = tempFolder.newFile("spool.cbz")
        val handle = CbzArchiveHandle(1L, spooledFile) {}
        fakeRepo.openResult = CbzArchiveResult.Success(handle)

        val pages = listOf(
            PageMetadata(pageIndex = 0, entryName = "p1.jpg", normalizedPath = "p1.jpg"),
            PageMetadata(pageIndex = 1, entryName = "p2.jpg", normalizedPath = "p2.jpg"),
            PageMetadata(pageIndex = 2, entryName = "p3.jpg", normalizedPath = "p3.jpg")
        )
        fakeIndexer.indexResult = PageIndexResult.Success(ChapterPageIndex(chapterId = 1L, pages = pages))

        val viewModel = ReaderViewModel(
            chapterId = 1L,
            libraryRepository = fakeRepo,
            settingsRepository = fakeSettingsRepo,
            cbzPageIndexer = fakeIndexer,
            cbzPageDecoderFactory = fakeDecoderFactory
        )

        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }

        assertEquals(0, (viewModel.uiState.value as ReaderUiState.Success).currentPageIndex)

        viewModel.goToNextPage()
        assertEquals(1, (viewModel.uiState.value as ReaderUiState.Success).currentPageIndex)

        viewModel.goToNextPage()
        assertEquals(2, (viewModel.uiState.value as ReaderUiState.Success).currentPageIndex)

        // Attempt next on last page (page 2 of 3) -> remains 2, no-op, no crash, no auto-chapter advance
        viewModel.goToNextPage()
        assertEquals(2, (viewModel.uiState.value as ReaderUiState.Success).currentPageIndex)

        collectJob.cancel()
    }

    @Test
    fun goToPreviousPage_decrementsPage_boundedByZero() = runTest {
        val spooledFile = tempFolder.newFile("spool.cbz")
        val handle = CbzArchiveHandle(1L, spooledFile) {}
        fakeRepo.openResult = CbzArchiveResult.Success(handle)

        val pages = listOf(
            PageMetadata(pageIndex = 0, entryName = "p1.jpg", normalizedPath = "p1.jpg"),
            PageMetadata(pageIndex = 1, entryName = "p2.jpg", normalizedPath = "p2.jpg")
        )
        fakeIndexer.indexResult = PageIndexResult.Success(ChapterPageIndex(chapterId = 1L, pages = pages))

        val viewModel = ReaderViewModel(
            chapterId = 1L,
            libraryRepository = fakeRepo,
            settingsRepository = fakeSettingsRepo,
            cbzPageIndexer = fakeIndexer,
            cbzPageDecoderFactory = fakeDecoderFactory
        )

        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }

        viewModel.setCurrentPageIndex(1)
        assertEquals(1, (viewModel.uiState.value as ReaderUiState.Success).currentPageIndex)

        viewModel.goToPreviousPage()
        assertEquals(0, (viewModel.uiState.value as ReaderUiState.Success).currentPageIndex)

        // Attempt previous on first page -> remains 0, no-op, no crash
        viewModel.goToPreviousPage()
        assertEquals(0, (viewModel.uiState.value as ReaderUiState.Success).currentPageIndex)

        collectJob.cancel()
    }

    @Test
    fun setReadingDirection_updatesSettingsRepository_andPreservesDecoder() = runTest {
        val spooledFile = tempFolder.newFile("spool.cbz")
        var handleClosed = false
        val handle = CbzArchiveHandle(1L, spooledFile) { handleClosed = true }
        fakeRepo.openResult = CbzArchiveResult.Success(handle)

        val pages = listOf(
            PageMetadata(pageIndex = 0, entryName = "p1.jpg", normalizedPath = "p1.jpg"),
            PageMetadata(pageIndex = 1, entryName = "p2.jpg", normalizedPath = "p2.jpg")
        )
        fakeIndexer.indexResult = PageIndexResult.Success(ChapterPageIndex(chapterId = 1L, pages = pages))

        val viewModel = ReaderViewModel(
            chapterId = 1L,
            libraryRepository = fakeRepo,
            settingsRepository = fakeSettingsRepo,
            cbzPageIndexer = fakeIndexer,
            cbzPageDecoderFactory = fakeDecoderFactory
        )

        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }

        // Initial factory create count must be 1
        assertEquals(1, fakeDecoderFactory.createCount)
        val initialDecoder = fakeDecoderFactory.createdDecoders.first()

        // Transition LTR -> RTL -> LTR
        viewModel.setReadingDirection("rtl")
        val stateRtl = viewModel.uiState.value as ReaderUiState.Success
        assertEquals("rtl", stateRtl.readingDirection)
        assertEquals("Factory MUST NOT create a new decoder on direction change", 1, fakeDecoderFactory.createCount)

        viewModel.setReadingDirection("ltr")
        val stateLtr = viewModel.uiState.value as ReaderUiState.Success
        assertEquals("ltr", stateLtr.readingDirection)
        assertEquals("Factory MUST NOT create a new decoder on direction change", 1, fakeDecoderFactory.createCount)

        // ASSERTION SPESIFIK: Decoder instance is 100% same instance!
        org.junit.Assert.assertSame(initialDecoder, fakeDecoderFactory.createdDecoders.first())

        assertFalse("Archive handle MUST NOT be closed on reading direction change", handleClosed)
        assertFalse("Decoder MUST NOT be closed on reading direction change", fakeDecoder.closed)

        collectJob.cancel()
    }

    @Test
    fun emptyChapter_navigationCallsDoNotCrashOrProduceInvalidIndex() = runTest {
        val spooledFile = tempFolder.newFile("spool_empty.cbz")
        val handle = CbzArchiveHandle(1L, spooledFile) {}
        fakeRepo.openResult = CbzArchiveResult.Success(handle)

        // Empty ChapterPageIndex with 0 pages (totalPages == 0)
        val emptyPageIndex = ChapterPageIndex(chapterId = 1L, pages = emptyList())
        fakeIndexer.indexResult = PageIndexResult.Success(emptyPageIndex)

        val viewModel = ReaderViewModel(
            chapterId = 1L,
            libraryRepository = fakeRepo,
            settingsRepository = fakeSettingsRepo,
            cbzPageIndexer = fakeIndexer,
            cbzPageDecoderFactory = fakeDecoderFactory
        )

        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }

        val currentState = viewModel.uiState.value as ReaderUiState.Success
        assertEquals(0, currentState.pageIndex.totalPages)
        assertEquals(0, currentState.currentPageIndex)

        // Navigation calls on empty chapter MUST NOT crash or produce negative index
        viewModel.goToNextPage()
        assertEquals(0, (viewModel.uiState.value as ReaderUiState.Success).currentPageIndex)

        viewModel.goToPreviousPage()
        assertEquals(0, (viewModel.uiState.value as ReaderUiState.Success).currentPageIndex)

        viewModel.setCurrentPageIndex(5)
        assertEquals(0, (viewModel.uiState.value as ReaderUiState.Success).currentPageIndex)

        collectJob.cancel()
    }

    @Test
    fun loadChapterAndInitialize_restoresProgressWhenAvailable() = runTest {
        val spooledFile = tempFolder.newFile("spool_restore.cbz")
        val handle = CbzArchiveHandle(1L, spooledFile) {}
        fakeRepo.openResult = CbzArchiveResult.Success(handle)

        val chapter = ChapterEntity(id = 101L, comicId = 1L, relativePath = "Comics/OP/Ch1.cbz", chapterNumber = 1, displayName = "Ch1.cbz", pageCount = 10, isValid = true)
        fakeRepo.chaptersById[101L] = chapter

        val savedProgress = ReadingProgressEntity(
            comicId = 1L,
            lastReadChapterId = 101L,
            lastReadChapterNumber = 1,
            chapterRelativePath = "Comics/OP/Ch1.cbz",
            pageIndex = 4,
            scrollOffset = 120,
            updatedAt = 1000L
        )
        fakeRepo.progressByComicId[1L] = savedProgress

        val pages = (1..10).map { i -> PageMetadata(pageIndex = i - 1, entryName = "p$i.jpg", normalizedPath = "p$i.jpg") }
        fakeIndexer.indexResult = PageIndexResult.Success(ChapterPageIndex(chapterId = 101L, pages = pages))

        val viewModel = ReaderViewModel(
            chapterId = 101L,
            libraryRepository = fakeRepo,
            settingsRepository = fakeSettingsRepo,
            cbzPageIndexer = fakeIndexer,
            cbzPageDecoderFactory = fakeDecoderFactory
        )

        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }

        val currentState = viewModel.uiState.value as ReaderUiState.Success
        assertEquals(4, currentState.currentPageIndex)
        assertEquals(120, currentState.scrollOffset)

        collectJob.cancel()
    }

    @Test
    fun loadChapterAndInitialize_outOfBoundsProgress_clampsToLastValidPage() = runTest {
        val spooledFile = tempFolder.newFile("spool_clamp.cbz")
        val handle = CbzArchiveHandle(1L, spooledFile) {}
        fakeRepo.openResult = CbzArchiveResult.Success(handle)

        val chapter = ChapterEntity(id = 101L, comicId = 1L, relativePath = "Comics/OP/Ch1.cbz", chapterNumber = 1, displayName = "Ch1.cbz", pageCount = 5, isValid = true)
        fakeRepo.chaptersById[101L] = chapter

        // Progress pageIndex = 99 (out of bounds for 5-page chapter)
        val savedProgress = ReadingProgressEntity(
            comicId = 1L,
            lastReadChapterId = 101L,
            lastReadChapterNumber = 1,
            chapterRelativePath = "Comics/OP/Ch1.cbz",
            pageIndex = 99,
            scrollOffset = 50,
            updatedAt = 1000L
        )
        fakeRepo.progressByComicId[1L] = savedProgress

        val pages = (1..5).map { i -> PageMetadata(pageIndex = i - 1, entryName = "p$i.jpg", normalizedPath = "p$i.jpg") }
        fakeIndexer.indexResult = PageIndexResult.Success(ChapterPageIndex(chapterId = 101L, pages = pages))

        val viewModel = ReaderViewModel(
            chapterId = 101L,
            libraryRepository = fakeRepo,
            settingsRepository = fakeSettingsRepo,
            cbzPageIndexer = fakeIndexer,
            cbzPageDecoderFactory = fakeDecoderFactory
        )

        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }

        val currentState = viewModel.uiState.value as ReaderUiState.Success
        assertEquals("Out of bounds progress page 99 must clamp to page 4", 4, currentState.currentPageIndex)

        collectJob.cancel()
    }

    @Test
    fun setCurrentPageIndexAndOffset_debouncesSaveProgress() = runTest {
        val spooledFile = tempFolder.newFile("spool_debounce.cbz")
        val handle = CbzArchiveHandle(1L, spooledFile) {}
        fakeRepo.openResult = CbzArchiveResult.Success(handle)

        val chapter = ChapterEntity(id = 101L, comicId = 1L, relativePath = "Comics/OP/Ch1.cbz", chapterNumber = 1, displayName = "Ch1.cbz", pageCount = 10, isValid = true)
        fakeRepo.chaptersById[101L] = chapter

        val pages = (1..10).map { i -> PageMetadata(pageIndex = i - 1, entryName = "p$i.jpg", normalizedPath = "p$i.jpg") }
        fakeIndexer.indexResult = PageIndexResult.Success(ChapterPageIndex(chapterId = 101L, pages = pages))

        val viewModel = ReaderViewModel(
            chapterId = 101L,
            libraryRepository = fakeRepo,
            settingsRepository = fakeSettingsRepo,
            cbzPageIndexer = fakeIndexer,
            cbzPageDecoderFactory = fakeDecoderFactory
        )

        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }

        fakeRepo.savedProgressList.clear()

        // Rapid page changes within 200ms
        viewModel.setCurrentPageIndexAndOffset(1, 0)
        viewModel.setCurrentPageIndexAndOffset(2, 0)
        viewModel.setCurrentPageIndexAndOffset(3, 100)

        // Advance time past 500ms debounce
        testDispatcher.scheduler.advanceTimeBy(600)

        assertEquals("Rapid page turns must debounce to 1 final save", 1, fakeRepo.savedProgressList.size)
        assertEquals(3, fakeRepo.savedProgressList.last().pageIndex)
        assertEquals(100, fakeRepo.savedProgressList.last().scrollOffset)

        collectJob.cancel()
    }
}
