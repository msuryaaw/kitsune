package com.example.kitsune.ui.screens.detail

import com.example.kitsune.data.ChapterAvailabilityResult
import com.example.kitsune.data.CoverCacheResult
import com.example.kitsune.data.CoverResult
import com.example.kitsune.data.LibraryRepository
import com.example.kitsune.data.entity.ChapterEntity
import com.example.kitsune.data.entity.ComicEntity
import com.example.kitsune.data.entity.ReadingProgressEntity
import com.example.kitsune.scanner.LibraryScanResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class FakeComicDetailLibraryRepository : LibraryRepository {
    val comicsById = MutableStateFlow<Map<Long, ComicEntity>>(emptyMap())
    val chaptersByComicId = MutableStateFlow<Map<Long, List<ChapterEntity>>>(emptyMap())
    var shouldThrowError = false
    var errorMessage = "Database error"
    var availabilityResult: ChapterAvailabilityResult = ChapterAvailabilityResult.Available
    val releasedFiles = mutableListOf<File>()

    override suspend fun syncLibrary(rootUri: String): LibraryScanResult = LibraryScanResult.RootNotConfigured

    override fun observeComicsByRoot(rootUri: String): Flow<List<ComicEntity>> = flowOf(emptyList())

    override fun observeComicById(comicId: Long): Flow<ComicEntity?> {
        if (shouldThrowError) return flow { throw RuntimeException(errorMessage) }
        return comicsById.map { it[comicId] }
    }

    override fun observeBookmarkedComicsByRoot(rootUri: String): Flow<List<ComicEntity>> = flowOf(emptyList())

    override fun observeRecentlyReadByRoot(rootUri: String): Flow<List<ComicEntity>> = flowOf(emptyList())

    override fun observeChaptersByComicId(comicId: Long): Flow<List<ChapterEntity>> {
        if (shouldThrowError) return flow { throw RuntimeException(errorMessage) }
        return chaptersByComicId.map { it[comicId] ?: emptyList() }
    }

    override suspend fun verifyChapterAvailable(chapterId: Long): ChapterAvailabilityResult = availabilityResult

    override suspend fun getReadingProgress(comicId: Long): ReadingProgressEntity? = null

    override suspend fun saveReadingProgress(progress: ReadingProgressEntity) {}

    override suspend fun setBookmarked(comicId: Long, bookmarked: Boolean) {}

    override suspend fun fetchCover(comicId: Long): CoverResult = CoverResult.Unavailable("Fake")

    override suspend fun getCoverFile(comicId: Long): CoverCacheResult = CoverCacheResult.Unavailable("Fake")

    override suspend fun releaseCoverFile(file: File) {
        releasedFiles.add(file)
    }

    override fun observeLastScanResult(): Flow<LibraryScanResult?> = flowOf(null)

    override suspend fun openCbzArchive(chapterId: Long): com.example.kitsune.reader.CbzArchiveResult =
        com.example.kitsune.reader.CbzArchiveResult.ChapterMissing
}

@OptIn(ExperimentalCoroutinesApi::class)
class ComicDetailViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var fakeRepo: FakeComicDetailLibraryRepository
    private lateinit var viewModel: ComicDetailViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeRepo = FakeComicDetailLibraryRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun uiState_initialValueIsLoading() {
        viewModel = ComicDetailViewModel(comicId = 1L, libraryRepository = fakeRepo)
        assertEquals("Initial value must be Loading", ComicDetailUiState.Loading, viewModel.uiState.value)
    }

    @Test
    fun uiState_emitsSuccess_whenComicAndChaptersExist() = runTest {
        val comic = ComicEntity(id = 1L, rootUriString = "content://root", relativePath = "Comics/OP", title = "One Piece", lastScannedAt = 1000L)
        val chapter1 = ChapterEntity(id = 101L, comicId = 1L, relativePath = "Comics/OP/Chapter 1.cbz", chapterNumber = 1, displayName = "Chapter 1.cbz", pageCount = 20, isValid = true)
        val chapter2 = ChapterEntity(id = 102L, comicId = 1L, relativePath = "Comics/OP/Chapter 2.cbz", chapterNumber = 2, displayName = "Chapter 2.cbz", pageCount = 22, isValid = true)

        fakeRepo.comicsById.value = mapOf(1L to comic)
        fakeRepo.chaptersByComicId.value = mapOf(1L to listOf(chapter1, chapter2))

        viewModel = ComicDetailViewModel(comicId = 1L, libraryRepository = fakeRepo)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }

        val currentState = viewModel.uiState.value
        assertTrue("State must be Success", currentState is ComicDetailUiState.Success)
        val success = currentState as ComicDetailUiState.Success
        assertEquals("One Piece", success.comic.title)
        assertEquals(2, success.chapters.size)
        assertEquals("Chapter 1.cbz", success.chapters[0].displayName)
        assertEquals("Chapter 2.cbz", success.chapters[1].displayName)

        collectJob.cancel()
    }

    @Test
    fun uiState_emitsComicNotFound_whenComicDoesNotExist() = runTest {
        fakeRepo.comicsById.value = emptyMap()

        viewModel = ComicDetailViewModel(comicId = 999L, libraryRepository = fakeRepo)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }

        val currentState = viewModel.uiState.value
        assertTrue("State must be ComicNotFound", currentState is ComicDetailUiState.ComicNotFound)

        collectJob.cancel()
    }

    @Test
    fun uiState_emitsEmptyChapters_whenComicExistsButChaptersEmpty() = runTest {
        val comic = ComicEntity(id = 1L, rootUriString = "content://root", relativePath = "Comics/Empty", title = "Empty", lastScannedAt = 1000L)
        fakeRepo.comicsById.value = mapOf(1L to comic)
        fakeRepo.chaptersByComicId.value = mapOf(1L to emptyList())

        viewModel = ComicDetailViewModel(comicId = 1L, libraryRepository = fakeRepo)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }

        val currentState = viewModel.uiState.value
        assertTrue("State must be EmptyChapters", currentState is ComicDetailUiState.EmptyChapters)

        collectJob.cancel()
    }

    @Test
    fun uiState_emitsError_whenRepositoryThrowsException() = runTest {
        fakeRepo.shouldThrowError = true
        fakeRepo.errorMessage = "Database Disk Error"

        viewModel = ComicDetailViewModel(comicId = 1L, libraryRepository = fakeRepo)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }

        val currentState = viewModel.uiState.value
        assertTrue("State must be Error", currentState is ComicDetailUiState.Error)
        assertEquals("Database Disk Error", (currentState as ComicDetailUiState.Error).message)

        collectJob.cancel()
    }

    @Test
    fun uiState_includesComicWithChapter2Only_inSuccessState() = runTest {
        val comic = ComicEntity(id = 2L, rootUriString = "content://root", relativePath = "Comics/Naruto", title = "Naruto", coverEntryPath = null, lastScannedAt = 1000L)
        val chapter2 = ChapterEntity(id = 202L, comicId = 2L, relativePath = "Comics/Naruto/Chapter 2.cbz", chapterNumber = 2, displayName = "Chapter 2.cbz", pageCount = 18, isValid = true)

        fakeRepo.comicsById.value = mapOf(2L to comic)
        fakeRepo.chaptersByComicId.value = mapOf(2L to listOf(chapter2))

        viewModel = ComicDetailViewModel(comicId = 2L, libraryRepository = fakeRepo)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }

        val currentState = viewModel.uiState.value
        assertTrue("Comic with Chapter 2 valid MUST be emitted in Success state", currentState is ComicDetailUiState.Success)
        val success = currentState as ComicDetailUiState.Success
        assertEquals("Naruto", success.comic.title)
        assertEquals(1, success.chapters.size)
        assertEquals(2, success.chapters.first().chapterNumber)

        collectJob.cancel()
    }

    @Test
    fun onChapterSelected_availableChapter_emitsNavigateToReader() = runTest {
        val comic = ComicEntity(id = 1L, rootUriString = "content://root", relativePath = "Comics/OP", title = "One Piece", lastScannedAt = 1000L)
        val chapter1 = ChapterEntity(id = 101L, comicId = 1L, relativePath = "Comics/OP/Chapter 1.cbz", chapterNumber = 1, displayName = "Chapter 1.cbz", pageCount = 20, isValid = true)

        fakeRepo.comicsById.value = mapOf(1L to comic)
        fakeRepo.chaptersByComicId.value = mapOf(1L to listOf(chapter1))
        fakeRepo.availabilityResult = ChapterAvailabilityResult.Available

        viewModel = ComicDetailViewModel(comicId = 1L, libraryRepository = fakeRepo)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }

        var emittedEvent: ComicDetailEvent? = null
        val eventJob = launch(testDispatcher) {
            viewModel.eventFlow.collect { emittedEvent = it }
        }

        viewModel.onChapterSelected(101L)

        assertTrue("Event must be NavigateToReader when chapter is available", emittedEvent is ComicDetailEvent.NavigateToReader)
        assertEquals(101L, (emittedEvent as ComicDetailEvent.NavigateToReader).chapterId)

        collectJob.cancel()
        eventJob.cancel()
    }

    @Test
    fun onChapterSelected_missingChapter_emitsShowFeedbackAndBlocksNavigation() = runTest {
        val comic = ComicEntity(id = 1L, rootUriString = "content://root", relativePath = "Comics/OP", title = "One Piece", lastScannedAt = 1000L)
        val chapter1 = ChapterEntity(id = 101L, comicId = 1L, relativePath = "Comics/OP/Chapter 1.cbz", chapterNumber = 1, displayName = "Chapter 1.cbz", pageCount = 20, isValid = true)

        fakeRepo.comicsById.value = mapOf(1L to comic)
        fakeRepo.chaptersByComicId.value = mapOf(1L to listOf(chapter1))
        fakeRepo.availabilityResult = ChapterAvailabilityResult.Missing

        viewModel = ComicDetailViewModel(comicId = 1L, libraryRepository = fakeRepo)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }

        var emittedEvent: ComicDetailEvent? = null
        val eventJob = launch(testDispatcher) {
            viewModel.eventFlow.collect { emittedEvent = it }
        }

        viewModel.onChapterSelected(101L)

        assertTrue("Event must be ShowFeedback when chapter is missing", emittedEvent is ComicDetailEvent.ShowFeedback)
        assertEquals("File chapter tidak ditemukan di penyimpanan.", (emittedEvent as ComicDetailEvent.ShowFeedback).message)

        collectJob.cancel()
        eventJob.cancel()
    }

    @Test
    fun onChapterSelected_permissionDenied_emitsShowFeedbackAndBlocksNavigation() = runTest {
        val comic = ComicEntity(id = 1L, rootUriString = "content://root", relativePath = "Comics/OP", title = "One Piece", lastScannedAt = 1000L)
        val chapter1 = ChapterEntity(id = 101L, comicId = 1L, relativePath = "Comics/OP/Chapter 1.cbz", chapterNumber = 1, displayName = "Chapter 1.cbz", pageCount = 20, isValid = true)

        fakeRepo.comicsById.value = mapOf(1L to comic)
        fakeRepo.chaptersByComicId.value = mapOf(1L to listOf(chapter1))
        fakeRepo.availabilityResult = ChapterAvailabilityResult.PermissionDenied("Permission revoked")

        viewModel = ComicDetailViewModel(comicId = 1L, libraryRepository = fakeRepo)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }

        var emittedEvent: ComicDetailEvent? = null
        val eventJob = launch(testDispatcher) {
            viewModel.eventFlow.collect { emittedEvent = it }
        }

        viewModel.onChapterSelected(101L)

        assertTrue("Event must be ShowFeedback when permission is denied", emittedEvent is ComicDetailEvent.ShowFeedback)
        assertTrue((emittedEvent as ComicDetailEvent.ShowFeedback).message.contains("Permission revoked"))

        collectJob.cancel()
        eventJob.cancel()
    }

    @Test
    fun onChapterSelected_mismatchedChapterId_blocksNavigationAndShowsFeedback() = runTest {
        val comic = ComicEntity(id = 1L, rootUriString = "content://root", relativePath = "Comics/OP", title = "One Piece", lastScannedAt = 1000L)
        val chapter1 = ChapterEntity(id = 101L, comicId = 1L, relativePath = "Comics/OP/Chapter 1.cbz", chapterNumber = 1, displayName = "Chapter 1.cbz", pageCount = 20, isValid = true)

        fakeRepo.comicsById.value = mapOf(1L to comic)
        fakeRepo.chaptersByComicId.value = mapOf(1L to listOf(chapter1))

        viewModel = ComicDetailViewModel(comicId = 1L, libraryRepository = fakeRepo)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }

        var emittedEvent: ComicDetailEvent? = null
        val eventJob = launch(testDispatcher) {
            viewModel.eventFlow.collect { emittedEvent = it }
        }

        // Try selecting chapter 999L which does not belong to active comic 1L
        viewModel.onChapterSelected(999L)

        assertTrue("Event must be ShowFeedback for mismatched chapter", emittedEvent is ComicDetailEvent.ShowFeedback)
        assertEquals("Chapter tidak terasosiasi dengan komik ini.", (emittedEvent as ComicDetailEvent.ShowFeedback).message)

        collectJob.cancel()
        eventJob.cancel()
    }

    @Test
    fun onChapterSelected_storageError_emitsExplicitErrorFeedback() = runTest {
        val comic = ComicEntity(id = 1L, rootUriString = "content://root", relativePath = "Comics/OP", title = "One Piece", lastScannedAt = 1000L)
        val chapter1 = ChapterEntity(id = 101L, comicId = 1L, relativePath = "Comics/OP/Chapter 1.cbz", chapterNumber = 1, displayName = "Chapter 1.cbz", pageCount = 20, isValid = true)

        fakeRepo.comicsById.value = mapOf(1L to comic)
        fakeRepo.chaptersByComicId.value = mapOf(1L to listOf(chapter1))
        fakeRepo.availabilityResult = ChapterAvailabilityResult.Error(RuntimeException("Disk I/O Error"))

        viewModel = ComicDetailViewModel(comicId = 1L, libraryRepository = fakeRepo)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }

        var emittedEvent: ComicDetailEvent? = null
        val eventJob = launch(testDispatcher) {
            viewModel.eventFlow.collect { emittedEvent = it }
        }

        viewModel.onChapterSelected(101L)

        assertTrue("Event must be ShowFeedback for storage error", emittedEvent is ComicDetailEvent.ShowFeedback)
        assertTrue((emittedEvent as ComicDetailEvent.ShowFeedback).message.contains("Disk I/O Error"))

        collectJob.cancel()
        eventJob.cancel()
    }
}
