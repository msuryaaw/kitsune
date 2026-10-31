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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FakeReaderLibraryRepository : LibraryRepository {
    var openResult: CbzArchiveResult = CbzArchiveResult.ChapterMissing

    override suspend fun openCbzArchive(chapterId: Long): CbzArchiveResult = openResult

    override suspend fun syncLibrary(rootUri: String): LibraryScanResult = LibraryScanResult.RootNotConfigured
    override fun observeComicsByRoot(rootUri: String): Flow<List<ComicEntity>> = flowOf(emptyList())
    override fun observeBookmarkedComicsByRoot(rootUri: String): Flow<List<ComicEntity>> = flowOf(emptyList())
    override fun observeRecentlyReadByRoot(rootUri: String): Flow<List<ComicEntity>> = flowOf(emptyList())
    override fun observeChaptersByComicId(comicId: Long): Flow<List<ChapterEntity>> = flowOf(emptyList())
    override fun observeComicById(comicId: Long): Flow<ComicEntity?> = flowOf(null)
    override suspend fun verifyChapterAvailable(chapterId: Long) = com.example.kitsune.data.ChapterAvailabilityResult.Missing
    override suspend fun getReadingProgress(comicId: Long): ReadingProgressEntity? = null
    override suspend fun saveReadingProgress(progress: ReadingProgressEntity) {}
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
}
