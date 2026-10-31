package com.example.kitsune.ui.screens.history

import com.example.kitsune.core.RootFolderPreferences
import com.example.kitsune.data.ChapterAvailabilityResult
import com.example.kitsune.data.CoverCacheResult
import com.example.kitsune.data.CoverResult
import com.example.kitsune.data.LibraryRepository
import com.example.kitsune.data.entity.ChapterEntity
import com.example.kitsune.data.entity.ComicEntity
import com.example.kitsune.data.entity.ReadingProgressEntity
import com.example.kitsune.data.model.ComicWithProgress
import com.example.kitsune.scanner.LibraryScanResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import com.example.kitsune.reader.CbzArchiveHandle
import com.example.kitsune.reader.ChapterPageIndex
import com.example.kitsune.reader.PageIndexResult
import com.example.kitsune.reader.PageMetadata
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config
import java.io.File

class FakeHistoryLibraryRepository : LibraryRepository {
    val recentlyReadByRoot = MutableStateFlow<Map<String, List<ComicWithProgress>>>(emptyMap())
    var availabilityResult: ChapterAvailabilityResult = ChapterAvailabilityResult.Available
    var verifiedChapterId: Long? = null

    override fun observeRecentlyReadWithProgressByRoot(rootUri: String): Flow<List<ComicWithProgress>> =
        recentlyReadByRoot.map { map ->
            (map[rootUri] ?: emptyList()).sortedByDescending { it.comic.lastReadAt ?: 0L }
        }

    override suspend fun verifyChapterAvailable(chapterId: Long): ChapterAvailabilityResult {
        verifiedChapterId = chapterId
        return availabilityResult
    }

    override suspend fun syncLibrary(rootUri: String): LibraryScanResult = LibraryScanResult.RootNotConfigured
    override fun observeComicsByRoot(rootUri: String): Flow<List<ComicEntity>> = flowOf(emptyList())
    override fun observeBookmarkedComicsByRoot(rootUri: String): Flow<List<ComicEntity>> = flowOf(emptyList())
    override fun observeRecentlyReadByRoot(rootUri: String): Flow<List<ComicEntity>> = flowOf(emptyList())
    override fun observeChaptersByComicId(comicId: Long): Flow<List<ChapterEntity>> = flowOf(emptyList())
    override fun observeComicById(comicId: Long): Flow<ComicEntity?> = flowOf(null)
    override suspend fun getReadingProgress(comicId: Long): ReadingProgressEntity? = null
    override suspend fun getChapterById(chapterId: Long): ChapterEntity? = null
    override suspend fun saveReadingProgress(progress: ReadingProgressEntity) {}
    override fun flushReadingProgress(progress: ReadingProgressEntity) {}
    override suspend fun setBookmarked(comicId: Long, bookmarked: Boolean) {}
    override suspend fun fetchCover(comicId: Long): CoverResult = CoverResult.Unavailable("Fake")
    override suspend fun getCoverFile(comicId: Long): CoverCacheResult = CoverCacheResult.Unavailable("Fake")
    override suspend fun releaseCoverFile(file: File) {}
    val lastScanResult = MutableStateFlow<LibraryScanResult?>(null)
    override fun observeLastScanResult(): Flow<LibraryScanResult?> = lastScanResult
    override suspend fun openCbzArchive(chapterId: Long) = com.example.kitsune.reader.CbzArchiveResult.ChapterMissing
}

class FakeHistoryRootPrefs(initialRoot: String? = null) : RootFolderPreferences {
    val rootUriFlow = MutableStateFlow(initialRoot)
    override fun getRootUriString(): String? = rootUriFlow.value
    override fun setRootUriString(uriString: String?) { rootUriFlow.value = uriString }
    override fun observeRootUriString(): Flow<String?> = rootUriFlow
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class HistoryViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var fakeRepo: FakeHistoryLibraryRepository
    private lateinit var fakeRootPrefs: FakeHistoryRootPrefs
    private lateinit var viewModel: HistoryViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeRepo = FakeHistoryLibraryRepository()
        fakeRootPrefs = FakeHistoryRootPrefs()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun uiState_emitsRootNotConfigured_whenRootUriIsNull() = runTest {
        fakeRootPrefs.rootUriFlow.value = null

        viewModel = HistoryViewModel(fakeRepo, fakeRootPrefs)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }

        assertEquals(HistoryUiState.RootNotConfigured, viewModel.uiState.value)

        collectJob.cancel()
    }

    @Test
    fun uiState_emitsEmpty_whenNoRecentlyReadComicsExist() = runTest {
        val rootUri = "content://root"
        fakeRootPrefs.rootUriFlow.value = rootUri
        fakeRepo.recentlyReadByRoot.value = mapOf(rootUri to emptyList())

        viewModel = HistoryViewModel(fakeRepo, fakeRootPrefs)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }

        assertEquals(HistoryUiState.Empty, viewModel.uiState.value)

        collectJob.cancel()
    }

    @Test
    fun uiState_emitsSuccess_orderedByLastReadAtDesc() = runTest {
        val rootUri = "content://root"
        fakeRootPrefs.rootUriFlow.value = rootUri

        val comic1 = ComicEntity(id = 1L, rootUriString = rootUri, relativePath = "Comics/A", title = "A", lastReadAt = 1000L)
        val progress1 = ReadingProgressEntity(comicId = 1L, lastReadChapterId = 101L, lastReadChapterNumber = 1, chapterRelativePath = "Comics/A/Ch1.cbz", pageIndex = 2, updatedAt = 1000L)

        val comic2 = ComicEntity(id = 2L, rootUriString = rootUri, relativePath = "Comics/B", title = "B", lastReadAt = 5000L)
        val progress2 = ReadingProgressEntity(comicId = 2L, lastReadChapterId = 202L, lastReadChapterNumber = 2, chapterRelativePath = "Comics/B/Ch2.cbz", pageIndex = 5, updatedAt = 5000L)

        val item1 = ComicWithProgress(comic1, progress1)
        val item2 = ComicWithProgress(comic2, progress2)

        fakeRepo.recentlyReadByRoot.value = mapOf(rootUri to listOf(item1, item2))

        viewModel = HistoryViewModel(fakeRepo, fakeRootPrefs)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }

        val currentState = viewModel.uiState.value
        assertTrue("State must be Success", currentState is HistoryUiState.Success)
        val success = currentState as HistoryUiState.Success
        assertEquals(2, success.items.size)
        // Item 2 (lastReadAt = 5000L) must come before Item 1 (1000L)
        assertEquals(2L, success.items[0].comic.id)
        assertEquals(1L, success.items[1].comic.id)

        collectJob.cancel()
    }

    @Test
    fun continueReading_validChapter_emitsNavigateToReaderEvent() = runTest {
        val rootUri = "content://root"
        fakeRootPrefs.rootUriFlow.value = rootUri

        val comic = ComicEntity(id = 1L, rootUriString = rootUri, relativePath = "Comics/A", title = "A", lastReadAt = 1000L)
        val progress = ReadingProgressEntity(comicId = 1L, lastReadChapterId = 101L, lastReadChapterNumber = 1, chapterRelativePath = "Comics/A/Ch1.cbz", pageIndex = 3, scrollOffset = 50, updatedAt = 1000L)
        val item = ComicWithProgress(comic, progress)

        fakeRepo.recentlyReadByRoot.value = mapOf(rootUri to listOf(item))
        fakeRepo.availabilityResult = ChapterAvailabilityResult.Available

        viewModel = HistoryViewModel(fakeRepo, fakeRootPrefs)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }

        val events = mutableListOf<HistoryEvent>()
        val eventJob = launch(testDispatcher) {
            viewModel.eventFlow.collect { events.add(it) }
        }

        viewModel.onContinueReadingClicked(item)

        assertEquals(101L, fakeRepo.verifiedChapterId)
        assertEquals(1, events.size)
        assertTrue(events.first() is HistoryEvent.NavigateToReader)
        assertEquals(101L, (events.first() as HistoryEvent.NavigateToReader).chapterId)

        eventJob.cancel()
        collectJob.cancel()
    }

    @Test
    fun continueReading_missingChapter_emitsFeedbackAndNavigatesToDetail() = runTest {
        val rootUri = "content://root"
        fakeRootPrefs.rootUriFlow.value = rootUri

        val comic = ComicEntity(id = 1L, rootUriString = rootUri, relativePath = "Comics/A", title = "A", lastReadAt = 1000L)
        val progress = ReadingProgressEntity(comicId = 1L, lastReadChapterId = 999L, lastReadChapterNumber = 1, chapterRelativePath = "Comics/A/Ch1.cbz", pageIndex = 0, updatedAt = 1000L)
        val item = ComicWithProgress(comic, progress)

        fakeRepo.recentlyReadByRoot.value = mapOf(rootUri to listOf(item))
        fakeRepo.availabilityResult = ChapterAvailabilityResult.Missing

        viewModel = HistoryViewModel(fakeRepo, fakeRootPrefs)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }

        val events = mutableListOf<HistoryEvent>()
        val eventJob = launch(testDispatcher) {
            viewModel.eventFlow.collect { events.add(it) }
        }

        viewModel.onContinueReadingClicked(item)

        // Must emit feedback AND navigate to detail cleanly without crash!
        assertEquals(2, events.size)
        assertTrue(events[0] is HistoryEvent.ShowFeedback)
        assertTrue(events[1] is HistoryEvent.NavigateToComicDetail)
        assertEquals(1L, (events[1] as HistoryEvent.NavigateToComicDetail).comicId)

        eventJob.cancel()
        collectJob.cancel()
    }

    @Test
    fun continueReading_comicWithoutReadingProgress_navigatesToComicDetailSafely() = runTest {
        val rootUri = "content://root"
        fakeRootPrefs.rootUriFlow.value = rootUri

        val comicWithoutProgress = ComicEntity(id = 5L, rootUriString = rootUri, relativePath = "Comics/NoProgress", title = "No Progress Comic", lastReadAt = 2000L)
        val itemWithoutProgress = ComicWithProgress(comicWithoutProgress, progress = null)

        fakeRepo.recentlyReadByRoot.value = mapOf(rootUri to listOf(itemWithoutProgress))

        viewModel = HistoryViewModel(fakeRepo, fakeRootPrefs)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }

        val events = mutableListOf<HistoryEvent>()
        val eventJob = launch(testDispatcher) {
            viewModel.eventFlow.collect { events.add(it) }
        }

        viewModel.onContinueReadingClicked(itemWithoutProgress)

        assertEquals(1, events.size)
        assertTrue("Item without progress MUST navigate to ComicDetailScreen safely", events.first() is HistoryEvent.NavigateToComicDetail)
        assertEquals(5L, (events.first() as HistoryEvent.NavigateToComicDetail).comicId)

        eventJob.cancel()
        collectJob.cancel()
    }

    @Test
    fun uiState_reactsToRootChanges_updatesHistoryListForActiveRoot() = runTest {
        val rootA = "content://rootA"
        val rootB = "content://rootB"

        val comicA = ComicEntity(id = 1L, rootUriString = rootA, relativePath = "Comics/A", title = "Comic A", lastReadAt = 1000L)
        val comicB = ComicEntity(id = 2L, rootUriString = rootB, relativePath = "Comics/B", title = "Comic B", lastReadAt = 3000L)

        fakeRepo.recentlyReadByRoot.value = mapOf(
            rootA to listOf(ComicWithProgress(comicA, null)),
            rootB to listOf(ComicWithProgress(comicB, null))
        )

        // Start on Root A
        fakeRootPrefs.rootUriFlow.value = rootA
        viewModel = HistoryViewModel(fakeRepo, fakeRootPrefs)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }

        val stateA = viewModel.uiState.value as HistoryUiState.Success
        assertEquals(1, stateA.items.size)
        assertEquals(1L, stateA.items.first().comic.id)

        // Switch to Root B
        fakeRootPrefs.rootUriFlow.value = rootB

        val stateB = viewModel.uiState.value as HistoryUiState.Success
        assertEquals(1, stateB.items.size)
        assertEquals(2L, stateB.items.first().comic.id)

        collectJob.cancel()
    }

    @Test
    fun uiState_emitsPermissionDenied_whenScanReturnsPermissionDenied() = runTest {
        val rootUri = "content://root"
        fakeRootPrefs.rootUriFlow.value = rootUri
        fakeRepo.lastScanResult.value = LibraryScanResult.PermissionDenied("Access revoked by SAF")

        viewModel = HistoryViewModel(fakeRepo, fakeRootPrefs)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }

        val currentState = viewModel.uiState.value
        assertTrue("State must be PermissionDenied", currentState is HistoryUiState.PermissionDenied)
        assertEquals("Access revoked by SAF", (currentState as HistoryUiState.PermissionDenied).message)

        collectJob.cancel()
    }

    @Test
    fun history_continueReading_restoresSameRoomProgressInReader() = runTest {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, com.example.kitsune.data.KitsuneDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val realComicDao = db.comicDao()
        val realChapterDao = db.chapterDao()
        val realProgressDao = db.readingProgressDao()

        val rootUri = "content://primary%3ARoot"
        fakeRootPrefs.rootUriFlow.value = rootUri

        // Step 1: Insert comic and chapter in real Room DB
        val comicId = realComicDao.insert(ComicEntity(rootUriString = rootUri, relativePath = "Comics/OnePiece", title = "One Piece", lastReadAt = 5000L))
        val chapterId = realChapterDao.insert(ChapterEntity(comicId = comicId, relativePath = "Comics/OnePiece/Ch1.cbz", chapterNumber = 1, displayName = "Ch1.cbz", pageCount = 10, isValid = true))

        // Save ReadingProgress: pageIndex = 8, scrollOffset = 240
        val savedProgress = ReadingProgressEntity(
            comicId = comicId,
            lastReadChapterId = chapterId,
            lastReadChapterNumber = 1,
            chapterRelativePath = "Comics/OnePiece/Ch1.cbz",
            pageIndex = 8,
            scrollOffset = 240,
            updatedAt = 5000L
        )
        realProgressDao.save(savedProgress)

        val fakeStorage = com.example.kitsune.storage.FakeStorageGateway(rootConfigured = true, permissionGranted = true).apply {
            addFile("Comics/OnePiece/Ch1.cbz", ByteArray(1024))
        }

        val realRepo = com.example.kitsune.data.DefaultLibraryRepository(
            com.example.kitsune.data.FakeLibraryScanner(),
            com.example.kitsune.scanner.IncrementalCbzCataloger(com.example.kitsune.data.NoopCbzCataloger),
            realComicDao, realChapterDao, realProgressDao,
            com.example.kitsune.data.CoverExtractor(fakeStorage, realComicDao, realChapterDao),
            com.example.kitsune.data.CoverCacheManager(java.io.File(System.getProperty("java.io.tmpdir"), "real_hist_covers"), com.example.kitsune.data.CoverExtractor(fakeStorage, realComicDao, realChapterDao), realComicDao, realChapterDao),
            fakeStorage,
            com.example.kitsune.reader.DefaultCbzArchiveProvider(java.io.File(System.getProperty("java.io.tmpdir"), "real_hist_spool"), fakeStorage, realChapterDao),
            db
        )

        // Step 2: Initialize HistoryViewModel using realRepo and real Room DB
        viewModel = HistoryViewModel(realRepo, fakeRootPrefs)
        val collectHistoryJob = launch(testDispatcher) { viewModel.uiState.collect() }

        val events = mutableListOf<HistoryEvent>()
        val eventJob = launch(testDispatcher) {
            viewModel.eventFlow.collect { events.add(it) }
        }

        // Allow Room background query executor to complete observeRecentlyReadWithProgressByRoot
        var historyState: HistoryUiState = HistoryUiState.Loading
        for (step in 1..50) {
            testDispatcher.scheduler.advanceTimeBy(50)
            historyState = viewModel.uiState.value
            if (historyState is HistoryUiState.Success) break
            Thread.sleep(20)
        }
        assertTrue("HistoryViewModel state must be Success", historyState is HistoryUiState.Success)
        val successHistory = historyState as HistoryUiState.Success
        assertEquals(1, successHistory.items.size)
        val historyItem = successHistory.items.first()
        assertEquals(comicId, historyItem.comic.id)
        assertEquals(8, historyItem.progress!!.pageIndex)
        assertEquals(240, historyItem.progress!!.scrollOffset)

        // Step 3: Trigger Continue Reading
        viewModel.onContinueReadingClicked(historyItem)

        // Allow Room background query executor to complete verifyChapterAvailable and emit NavigateToReader
        for (step in 1..50) {
            testDispatcher.scheduler.advanceTimeBy(50)
            if (events.isNotEmpty()) break
            Thread.sleep(20)
        }

        // Verify HistoryEvent.NavigateToReader event carries chapterId = chapterId (101L)
        assertEquals(1, events.size)
        assertTrue(events.first() is HistoryEvent.NavigateToReader)
        val targetChapterId = (events.first() as HistoryEvent.NavigateToReader).chapterId
        assertEquals(chapterId, targetChapterId)

        // Step 4: Open ReaderViewModel with chapterId using SAME realRepo and SAME Room DB
        val tempSpoolFile = tempFolder.newFile("spool_hist_reader.cbz")
        val handle = CbzArchiveHandle(chapterId, tempSpoolFile) {}

        val pages = (1..10).map { i -> PageMetadata(pageIndex = i - 1, entryName = "p$i.jpg", normalizedPath = "p$i.jpg") }
        val fakeIndexer = com.example.kitsune.ui.screens.reader.FakeCbzPageIndexer().apply {
            indexResult = PageIndexResult.Success(ChapterPageIndex(chapterId = chapterId, pages = pages))
        }
        val samplePage = PageMetadata(pageIndex = 0, entryName = "p1.jpg", normalizedPath = "p1.jpg")
        val fakeDecoder = com.example.kitsune.ui.screens.reader.FakeCbzPageDecoder(ChapterPageIndex(chapterId = chapterId, pages = listOf(samplePage)))
        val fakeDecoderFactory = com.example.kitsune.ui.screens.reader.FakeCbzPageDecoderFactory(fakeDecoder)
        val fakeSettingsRepo = com.example.kitsune.ui.screens.reader.FakeReaderSettingsRepository()

        val realRepoWrapper = object : LibraryRepository by realRepo {
            override suspend fun openCbzArchive(cId: Long): com.example.kitsune.reader.CbzArchiveResult = com.example.kitsune.reader.CbzArchiveResult.Success(handle)
        }

        val readerViewModel = com.example.kitsune.ui.screens.reader.ReaderViewModel(
            chapterId = targetChapterId,
            libraryRepository = realRepoWrapper,
            settingsRepository = fakeSettingsRepo,
            cbzPageIndexer = fakeIndexer,
            cbzPageDecoderFactory = fakeDecoderFactory
        )

        val collectReaderJob = launch(testDispatcher) { readerViewModel.uiState.collect() }

        // Allow Room background query executor to complete getChapterById and restore progress
        var readerState: com.example.kitsune.ui.screens.reader.ReaderUiState = com.example.kitsune.ui.screens.reader.ReaderUiState.Loading
        for (step in 1..50) {
            testDispatcher.scheduler.advanceTimeBy(50)
            readerState = readerViewModel.uiState.value
            if (readerState is com.example.kitsune.ui.screens.reader.ReaderUiState.Success) break
            Thread.sleep(20)
        }

        // Step 5: END-TO-END ASSERTION: Reader restores EXACT pageIndex = 8 and scrollOffset = 240 from Room DB!
        assertTrue("ReaderViewModel state must be Success", readerState is com.example.kitsune.ui.screens.reader.ReaderUiState.Success)
        val successReader = readerState as com.example.kitsune.ui.screens.reader.ReaderUiState.Success
        assertEquals("Target chapterId must be chapterId", chapterId, successReader.chapterId)
        assertEquals("Reader MUST restore exact pageIndex 8 from Room DB", 8, successReader.currentPageIndex)
        assertEquals("Reader MUST restore exact scrollOffset 240 from Room DB", 240, successReader.scrollOffset)

        // Verify Room DB progress record remains untouched after navigation
        val persistedProgress = realProgressDao.getByComicId(comicId)!!
        assertEquals(8, persistedProgress.pageIndex)
        assertEquals(240, persistedProgress.scrollOffset)

        eventJob.cancel()
        collectHistoryJob.cancel()
        collectReaderJob.cancel()
        db.close()
    }
}
