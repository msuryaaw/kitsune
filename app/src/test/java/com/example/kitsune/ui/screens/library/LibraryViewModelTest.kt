package com.example.kitsune.ui.screens.library

import com.example.kitsune.core.FakeRootFolderPreferences
import com.example.kitsune.data.ChapterAvailabilityResult
import com.example.kitsune.data.CoverCacheResult
import com.example.kitsune.data.CoverResult
import com.example.kitsune.data.LibraryRepository
import com.example.kitsune.data.SettingsRepository
import com.example.kitsune.data.entity.ChapterEntity
import com.example.kitsune.data.entity.ComicEntity
import com.example.kitsune.data.entity.ReadingProgressEntity
import com.example.kitsune.data.entity.SettingsEntity
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config

class FakeLibraryRepository : LibraryRepository {
    val comicsByRoot = MutableStateFlow<Map<String, List<ComicEntity>>>(emptyMap())
    val lastScanResult = MutableStateFlow<LibraryScanResult?>(null)
    var shouldThrowError = false
    var errorMessage = "Database query failed"
    val releasedFiles = mutableListOf<java.io.File>()
    var syncCalls = 0
    var syncRootUri: String? = null

    override suspend fun syncLibrary(rootUri: String): LibraryScanResult {
        syncCalls++
        syncRootUri = rootUri
        return lastScanResult.value ?: LibraryScanResult.RootNotConfigured
    }

    override fun observeComicsByRoot(rootUri: String): Flow<List<ComicEntity>> {
        if (shouldThrowError) {
            return flow { throw RuntimeException(errorMessage) }
        }
        return comicsByRoot.map { map -> map[rootUri] ?: emptyList() }
    }

    override fun observeBookmarkedComicsByRoot(rootUri: String): Flow<List<ComicEntity>> {
        return flowOf(emptyList())
    }

    override fun observeRecentlyReadByRoot(rootUri: String): Flow<List<ComicEntity>> {
        return flowOf(emptyList())
    }

    override fun observeRecentlyReadWithProgressByRoot(rootUri: String): Flow<List<com.example.kitsune.data.model.ComicWithProgress>> {
        return flowOf(emptyList())
    }

    override fun observeChaptersByComicId(comicId: Long): Flow<List<ChapterEntity>> {
        return flowOf(emptyList())
    }

    override suspend fun getReadingProgress(comicId: Long): ReadingProgressEntity? = null

    override suspend fun saveReadingProgress(progress: ReadingProgressEntity) {}

    var setBookmarkedCalled = false
    var lastBookmarkedComicId = 0L
    var lastBookmarkedState = false

    override suspend fun setBookmarked(comicId: Long, bookmarked: Boolean) {
        setBookmarkedCalled = true
        lastBookmarkedComicId = comicId
        lastBookmarkedState = bookmarked
        val currentMap = comicsByRoot.value.toMutableMap()
        currentMap.keys.forEach { root ->
            val list = (currentMap[root] ?: emptyList()).toMutableList()
            val idx = list.indexOfFirst { it.id == comicId }
            if (idx >= 0) {
                list[idx] = list[idx].copy(isBookmarked = bookmarked)
                currentMap[root] = list
            }
        }
        comicsByRoot.value = currentMap
    }

    override suspend fun fetchCover(comicId: Long): CoverResult = CoverResult.Unavailable("Fake")

    override suspend fun getCoverFile(comicId: Long): CoverCacheResult = CoverCacheResult.Unavailable("Fake")

    override suspend fun releaseCoverFile(file: java.io.File) {
        releasedFiles.add(file)
    }

    override fun observeComicById(comicId: Long): Flow<ComicEntity?> = flowOf(null)

    override suspend fun verifyChapterAvailable(chapterId: Long): ChapterAvailabilityResult = ChapterAvailabilityResult.Available

    override suspend fun openCbzArchive(chapterId: Long): com.example.kitsune.reader.CbzArchiveResult =
        com.example.kitsune.reader.CbzArchiveResult.ChapterMissing

    override suspend fun getChapterById(chapterId: Long): ChapterEntity? = null

    override fun flushReadingProgress(progress: ReadingProgressEntity) {}

    override fun observeLastScanResult(): Flow<LibraryScanResult?> = lastScanResult
}

class FakeComicsDirectoryDetector : com.example.kitsune.scanner.ComicsDirectoryDetector {
    var detectedState: com.example.kitsune.scanner.ComicsDirectoryState = com.example.kitsune.scanner.ComicsDirectoryState.DirectoryNotFound

    override fun detectComicsDirectory(): com.example.kitsune.scanner.ComicsDirectoryState {
        return detectedState
    }
}

class FakeSettingsRepository(
    initialSettings: SettingsEntity = SettingsEntity()
) : SettingsRepository {
    val settingsFlow = MutableStateFlow(initialSettings)

    override fun observeSettings(): Flow<SettingsEntity> = settingsFlow

    override suspend fun setGridColumns(columns: Int) {
        val sanitized = columns.coerceIn(2, 4)
        settingsFlow.value = settingsFlow.value.copy(gridColumns = sanitized)
    }

    override suspend fun setReaderMode(readerMode: String) {
        settingsFlow.value = settingsFlow.value.copy(readerMode = readerMode)
    }

    override suspend fun setReadingDirection(readingDirection: String) {
        settingsFlow.value = settingsFlow.value.copy(readingDirection = readingDirection)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LibraryViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var fakeRepo: FakeLibraryRepository
    private lateinit var fakePrefs: FakeRootFolderPreferences
    private lateinit var fakeSettingsRepo: FakeSettingsRepository
    private lateinit var fakeDetector: FakeComicsDirectoryDetector
    private lateinit var viewModel: LibraryViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeRepo = FakeLibraryRepository()
        fakePrefs = FakeRootFolderPreferences()
        fakeSettingsRepo = FakeSettingsRepository()
        fakeDetector = FakeComicsDirectoryDetector()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun uiState_initiallyRootNotConfigured_whenNoRootSelected() = runTest {
        viewModel = LibraryViewModel(fakeRepo, fakePrefs, fakeSettingsRepo, fakeDetector)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }

        val currentState = viewModel.uiState.value
        assertTrue(
            "State must be RootNotConfigured when no root URI is set",
            currentState is LibraryUiState.RootNotConfigured
        )

        collectJob.cancel()
    }

    @Test
    fun uiState_emitsComicsDirectoryNotFound_whenRootConfiguredButComicsFolderMissing() = runTest {
        val rootUri = "content://com.android.externalstorage.documents/tree/primary%3AKitsune"
        fakePrefs.setRootUriString(rootUri)
        fakeDetector.detectedState = com.example.kitsune.scanner.ComicsDirectoryState.DirectoryNotFound

        viewModel = LibraryViewModel(fakeRepo, fakePrefs, fakeSettingsRepo, fakeDetector)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }

        val currentState = viewModel.uiState.value
        assertTrue(
            "State must be ComicsDirectoryNotFound when Comics directory is missing",
            currentState is LibraryUiState.ComicsDirectoryNotFound
        )

        collectJob.cancel()
    }

    @Test
    fun uiState_emitsSuccess_whenRepoHasComics() = runTest {
        val rootUri = "content://com.android.externalstorage.documents/tree/primary%3AKitsune"
        val comic1 = ComicEntity(
            id = 1L,
            rootUriString = rootUri,
            relativePath = "Comics/One Piece",
            title = "One Piece",
            lastScannedAt = 1000L
        )
        fakeRepo.comicsByRoot.value = mapOf(rootUri to listOf(comic1))
        fakePrefs.setRootUriString(rootUri)

        viewModel = LibraryViewModel(fakeRepo, fakePrefs, fakeSettingsRepo, fakeDetector)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }

        val currentState = viewModel.uiState.value
        assertTrue(
            "State must be Success when repo emits comics",
            currentState is LibraryUiState.Success
        )
        val successState = currentState as LibraryUiState.Success
        assertEquals(rootUri, successState.rootUriString)
        assertEquals(1, successState.comics.size)
        assertEquals("One Piece", successState.comics.first().title)
        assertEquals(3, successState.gridColumns) // Default 3 columns

        collectJob.cancel()
    }

    @Test
    fun setGridColumns_updatesSettingsAndEmitsNewGridColumnsInUiState() = runTest {
        val rootUri = "content://primary%3AKitsune"
        val comic1 = ComicEntity(id = 1L, rootUriString = rootUri, relativePath = "Comics/A", title = "A", lastScannedAt = 100L)
        fakeRepo.comicsByRoot.value = mapOf(rootUri to listOf(comic1))
        fakePrefs.setRootUriString(rootUri)

        viewModel = LibraryViewModel(fakeRepo, fakePrefs, fakeSettingsRepo, fakeDetector)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }

        assertEquals(3, (viewModel.uiState.value as LibraryUiState.Success).gridColumns)

        // Change columns to 2
        viewModel.setGridColumns(2)
        assertEquals(2, (viewModel.uiState.value as LibraryUiState.Success).gridColumns)

        // Change columns to 4
        viewModel.setGridColumns(4)
        assertEquals(4, (viewModel.uiState.value as LibraryUiState.Success).gridColumns)

        collectJob.cancel()
    }

    @Test
    fun toggleBookmark_togglesBookmarkStateInRepository() = runTest {
        val rootUri = "content://primary%3AKitsune"
        val comic1 = ComicEntity(id = 1L, rootUriString = rootUri, relativePath = "Comics/A", title = "A", isBookmarked = false)
        fakeRepo.comicsByRoot.value = mapOf(rootUri to listOf(comic1))
        fakePrefs.setRootUriString(rootUri)

        viewModel = LibraryViewModel(fakeRepo, fakePrefs, fakeSettingsRepo, fakeDetector)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }

        assertFalse((viewModel.uiState.value as LibraryUiState.Success).comics.first().isBookmarked)

        // Toggle false -> true
        viewModel.toggleBookmark(1L, currentIsBookmarked = false)

        assertTrue(fakeRepo.setBookmarkedCalled)
        assertEquals(1L, fakeRepo.lastBookmarkedComicId)
        assertTrue(fakeRepo.lastBookmarkedState)
        assertTrue((viewModel.uiState.value as LibraryUiState.Success).comics.first().isBookmarked)

        // Toggle true -> false
        viewModel.toggleBookmark(1L, currentIsBookmarked = true)

        assertFalse(fakeRepo.lastBookmarkedState)
        assertFalse((viewModel.uiState.value as LibraryUiState.Success).comics.first().isBookmarked)

        collectJob.cancel()
    }

    @Test
    fun uiState_reactsToRootChanges_andClearsToRootNotConfiguredWhenCleared() = runTest {
        val rootUri1 = "content://primary%3AKitsune"
        val rootUri2 = "content://primary%3AOther"

        val comicRoot1 = ComicEntity(id = 1L, rootUriString = rootUri1, relativePath = "Comics/Manga1", title = "Manga 1", lastScannedAt = 1L)
        val comicRoot2 = ComicEntity(id = 2L, rootUriString = rootUri2, relativePath = "Comics/Manga2", title = "Manga 2", lastScannedAt = 2L)

        fakeRepo.comicsByRoot.value = mapOf(
            rootUri1 to listOf(comicRoot1),
            rootUri2 to listOf(comicRoot2)
        )
        fakePrefs.setRootUriString(rootUri1)

        viewModel = LibraryViewModel(fakeRepo, fakePrefs, fakeSettingsRepo, fakeDetector)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }

        // Root 1 active
        val state1 = viewModel.uiState.value
        assertTrue(state1 is LibraryUiState.Success)
        assertEquals("Manga 1", (state1 as LibraryUiState.Success).comics.first().title)

        // Change to Root 2
        fakePrefs.setRootUriString(rootUri2)
        val state2 = viewModel.uiState.value
        assertTrue(state2 is LibraryUiState.Success)
        assertEquals("Manga 2", (state2 as LibraryUiState.Success).comics.first().title)

        // Clear root
        fakePrefs.setRootUriString(null)
        val state3 = viewModel.uiState.value
        assertTrue("State must revert to RootNotConfigured when root cleared", state3 is LibraryUiState.RootNotConfigured)

        collectJob.cancel()
    }

    @Test
    fun uiState_initialValueIsLoading() {
        viewModel = LibraryViewModel(fakeRepo, fakePrefs, fakeSettingsRepo, fakeDetector)
        assertEquals("Initial value before flow observation must be Loading", LibraryUiState.Loading, viewModel.uiState.value)
    }

    @Test
    fun uiState_emitsError_whenRepoThrowsException() = runTest {
        val rootUri = "content://primary%3AKitsune"
        fakePrefs.setRootUriString(rootUri)
        fakeRepo.shouldThrowError = true
        fakeRepo.errorMessage = "Room DB Disk I/O Failure"

        viewModel = LibraryViewModel(fakeRepo, fakePrefs, fakeSettingsRepo, fakeDetector)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }

        val currentState = viewModel.uiState.value
        assertTrue("State must be Error when repository throws exception", currentState is LibraryUiState.Error)
        assertEquals("Room DB Disk I/O Failure", (currentState as LibraryUiState.Error).message)

        collectJob.cancel()
    }

    @Test
    fun uiState_recoversFromError_whenSwitchedToValidRoot() = runTest {
        val rootUriA = "content://primary%3AErrorRoot"
        val rootUriB = "content://primary%3AValidRoot"

        val comicRootB = ComicEntity(id = 10L, rootUriString = rootUriB, relativePath = "Comics/B", title = "Manga B", lastScannedAt = 100L)
        fakeRepo.comicsByRoot.value = mapOf(rootUriB to listOf(comicRootB))

        // Start on Root A which produces an error
        fakePrefs.setRootUriString(rootUriA)
        fakeRepo.shouldThrowError = true
        fakeRepo.errorMessage = "Root A I/O Error"

        viewModel = LibraryViewModel(fakeRepo, fakePrefs, fakeSettingsRepo, fakeDetector)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }

        // Root A is in Error state
        val stateA = viewModel.uiState.value
        assertTrue("Root A must be Error state", stateA is LibraryUiState.Error)
        assertEquals("Root A I/O Error", (stateA as LibraryUiState.Error).message)

        // Switch to Root B which is healthy
        fakeRepo.shouldThrowError = false
        fakePrefs.setRootUriString(rootUriB)

        // ViewModel MUST recover and observe Root B successfully
        val stateB = viewModel.uiState.value
        assertTrue("ViewModel must recover from Root A error and observe Root B", stateB is LibraryUiState.Success)
        val successB = stateB as LibraryUiState.Success
        assertEquals(rootUriB, successB.rootUriString)
        assertEquals("Manga B", successB.comics.first().title)

        collectJob.cancel()
    }

    @Test
    fun getCoverFile_delegatesToRepository() = runTest {
        viewModel = LibraryViewModel(fakeRepo, fakePrefs, fakeSettingsRepo, fakeDetector)
        val result = viewModel.getCoverFile(1L)
        assertTrue(result is CoverCacheResult.Unavailable)
    }

    @Test
    fun releaseCoverFile_delegatesToRepository() = runTest {
        val testFile = java.io.File("dummy.jpg")
        fakeRepo.releasedFiles.clear()
        viewModel = LibraryViewModel(fakeRepo, fakePrefs, fakeSettingsRepo, fakeDetector)

        viewModel.releaseCoverFile(testFile)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue("FakeRepo must record released file", fakeRepo.releasedFiles.contains(testFile))
    }

    @Test
    fun oneShotFileReleaser_executesReleaseOnlyOnce() {
        var releaseCalls = 0
        val testFile = java.io.File("dummy.jpg")
        val releaser = OneShotFileReleaser(testFile) {
            releaseCalls++
        }

        releaser.release()
        releaser.release()
        releaser.release()

        assertEquals("Release callback must execute exactly once regardless of repeated calls", 1, releaseCalls)
    }

    @Test
    fun coverLifecycle_singleCoroutineOwnership_activeReleaseAndCancellationOrder() = runTest {
        val testFile = java.io.File("dummy_ephemeral_1.jpg")
        fakeRepo.releasedFiles.clear()
        viewModel = LibraryViewModel(fakeRepo, fakePrefs, fakeSettingsRepo, fakeDetector)

        val isExecuting = java.util.concurrent.atomic.AtomicBoolean(false)
        val releaseExecuted = java.util.concurrent.atomic.AtomicBoolean(false)

        val job = launch {
            var acquiredFile: java.io.File? = null
            try {
                acquiredFile = testFile
                isExecuting.set(true)
                kotlinx.coroutines.delay(100)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } finally {
                acquiredFile?.let { file ->
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        viewModel.releaseCoverFile(file)
                        releaseExecuted.set(true)
                    }
                }
            }
        }

        // Mid-execution check
        testDispatcher.scheduler.advanceTimeBy(50)
        assertTrue("Request must be active mid-execution", isExecuting.get())
        assertFalse("Release must NOT run before execution completes", releaseExecuted.get())

        // Complete execution
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue("Release must execute in finally block after completion", releaseExecuted.get())
        assertTrue("FakeRepo must record released file", fakeRepo.releasedFiles.contains(testFile))
    }

    @Test
    fun coverLifecycle_cancellationWhileExecuting_runsReleaseOnNonCancellableAndPropagatesCancellation() = runTest {
        val testFile = java.io.File("dummy_ephemeral_2.jpg")
        fakeRepo.releasedFiles.clear()
        viewModel = LibraryViewModel(fakeRepo, fakePrefs, fakeSettingsRepo, fakeDetector)

        val releaseExecutedOnNonCancellable = java.util.concurrent.atomic.AtomicBoolean(false)

        val job = launch {
            var acquiredFile: java.io.File? = null
            try {
                acquiredFile = testFile
                kotlinx.coroutines.delay(1000)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } finally {
                acquiredFile?.let { file ->
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        viewModel.releaseCoverFile(file)
                        releaseExecutedOnNonCancellable.set(true)
                    }
                }
            }
        }

        testDispatcher.scheduler.advanceTimeBy(100)
        job.cancel(kotlinx.coroutines.CancellationException("Cancelled mid-execution"))

        try {
            job.join()
        } catch (ce: kotlinx.coroutines.CancellationException) {
            assertEquals("Cancelled mid-execution", ce.message)
        }

        assertTrue("Release MUST execute on NonCancellable context when coroutine cancelled", releaseExecutedOnNonCancellable.get())
        assertTrue("FakeRepo must record released file upon cancellation", fakeRepo.releasedFiles.contains(testFile))
    }

    @Test
    fun uiState_emitsPermissionDenied_whenScanReturnsPermissionDenied() = runTest {
        val rootUri = "content://primary%3AKitsune"
        fakePrefs.setRootUriString(rootUri)
        fakeRepo.lastScanResult.value = LibraryScanResult.PermissionDenied("Access revoked")

        viewModel = LibraryViewModel(fakeRepo, fakePrefs, fakeSettingsRepo, fakeDetector)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }

        val currentState = viewModel.uiState.value
        assertTrue("State must be PermissionDenied", currentState is LibraryUiState.PermissionDenied)
        assertEquals("Access revoked", (currentState as LibraryUiState.PermissionDenied).message)

        collectJob.cancel()
    }

    @Test
    fun uiState_emitsComicsDirectoryNotFound_whenComicsDirectoryIsMissing() = runTest {
        val rootUri = "content://primary%3AKitsune"
        fakePrefs.setRootUriString(rootUri)
        fakeRepo.lastScanResult.value = LibraryScanResult.ComicsDirectoryNotFound

        viewModel = LibraryViewModel(fakeRepo, fakePrefs, fakeSettingsRepo, fakeDetector)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }

        val currentState = viewModel.uiState.value
        assertTrue("State must be ComicsDirectoryNotFound", currentState is LibraryUiState.ComicsDirectoryNotFound)

        collectJob.cancel()
    }

    @Test
    fun uiState_emitsEmptyComicsDirectory_whenComicsDirectoryIsEmpty() = runTest {
        val rootUri = "content://primary%3AKitsune"
        fakePrefs.setRootUriString(rootUri)
        fakeRepo.lastScanResult.value = LibraryScanResult.EmptyComicsDirectory

        viewModel = LibraryViewModel(fakeRepo, fakePrefs, fakeSettingsRepo, fakeDetector)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }

        val currentState = viewModel.uiState.value
        assertTrue("State must be EmptyComicsDirectory", currentState is LibraryUiState.EmptyComicsDirectory)

        collectJob.cancel()
    }

    @Test
    fun uiState_emitsNoValidChapters_whenComicsFolderReadyButZeroValidChaptersIndexed() = runTest {
        val rootUri = "content://primary%3AKitsune"
        fakePrefs.setRootUriString(rootUri)
        fakeRepo.comicsByRoot.value = mapOf(rootUri to emptyList())
        fakeDetector.detectedState = com.example.kitsune.scanner.ComicsDirectoryState.Ready(
            entry = com.example.kitsune.storage.StorageEntry("Comics", "Comics", true),
            childCount = 2
        )

        viewModel = LibraryViewModel(fakeRepo, fakePrefs, fakeSettingsRepo, fakeDetector)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }

        val currentState = viewModel.uiState.value
        assertTrue("State must be NoValidChapters when comic folders exist but zero valid chapters indexed", currentState is LibraryUiState.NoValidChapters)

        collectJob.cancel()
    }

    @Test
    fun uiState_includesComicWithChapter2Only_inSuccessState() = runTest {
        val rootUri = "content://primary%3AKitsune"
        // Comic has Chapter 2 valid, but coverEntryPath is null because Chapter 1 is missing
        val comicWithChapter2Only = ComicEntity(
            id = 5L,
            rootUriString = rootUri,
            relativePath = "Comics/Naruto",
            title = "Naruto",
            coverEntryPath = null, // Chapter 1 missing
            lastScannedAt = 1000L
        )
        fakeRepo.comicsByRoot.value = mapOf(rootUri to listOf(comicWithChapter2Only))
        fakePrefs.setRootUriString(rootUri)

        viewModel = LibraryViewModel(fakeRepo, fakePrefs, fakeSettingsRepo, fakeDetector)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }

        val currentState = viewModel.uiState.value
        assertTrue("Comic with Chapter 2 valid MUST appear in Library Success state", currentState is LibraryUiState.Success)
        val success = currentState as LibraryUiState.Success
        assertEquals(1, success.comics.size)
        assertEquals("Naruto", success.comics.first().title)

        collectJob.cancel()
    }

    @Test
    fun refreshLibrary_triggersLibrarySyncOnActiveRoot() = runTest {
        val rootUri = "content://primary%3AKitsune"
        fakePrefs.setRootUriString(rootUri)
        fakeRepo.syncCalls = 0
        viewModel = LibraryViewModel(fakeRepo, fakePrefs, fakeSettingsRepo, fakeDetector)

        viewModel.refreshLibrary()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("refreshLibrary must invoke syncLibrary on repository", 1, fakeRepo.syncCalls)
        assertEquals(rootUri, fakeRepo.syncRootUri)
    }

    @Test
    fun uiState_emitsSuccessWithIssuesCount_whenPartialScanHasValidComicsAndIssues() = runTest {
        val rootUri = "content://primary%3AKitsune"
        val comic1 = ComicEntity(id = 1L, rootUriString = rootUri, relativePath = "Comics/A", title = "Manga A", lastScannedAt = 100L)
        fakeRepo.comicsByRoot.value = mapOf(rootUri to listOf(comic1))
        fakePrefs.setRootUriString(rootUri)

        val scanIssue = com.example.kitsune.scanner.ComicScanIssue.CorruptChapter("Comics/B/Ch1.cbz", "Bad archive")
        fakeRepo.lastScanResult.value = LibraryScanResult.Success(
            comics = emptyList(),
            summary = com.example.kitsune.scanner.ScanSummary(1, 1, 1, 1, 1, 0, 0, 0),
            issues = listOf(scanIssue)
        )

        viewModel = LibraryViewModel(fakeRepo, fakePrefs, fakeSettingsRepo, fakeDetector)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }

        val currentState = viewModel.uiState.value
        assertTrue("State must be Success for valid comics even when scan produced partial issues", currentState is LibraryUiState.Success)
        val success = currentState as LibraryUiState.Success
        assertEquals(1, success.comics.size)
        assertEquals(1, success.issuesCount)

        collectJob.cancel()
    }

    @Test
    fun searchQuery_filtersComicsCaseInsensitiveAndTrimsWhitespace() = runTest {
        val rootUri = "content://primary%3AKitsune"
        val comic1 = ComicEntity(id = 1L, rootUriString = rootUri, relativePath = "Comics/OnePiece", title = "One Piece", lastScannedAt = 100L)
        val comic2 = ComicEntity(id = 2L, rootUriString = rootUri, relativePath = "Comics/Naruto", title = "Naruto", lastScannedAt = 100L)
        fakeRepo.comicsByRoot.value = mapOf(rootUri to listOf(comic1, comic2))
        fakePrefs.setRootUriString(rootUri)

        viewModel = LibraryViewModel(fakeRepo, fakePrefs, fakeSettingsRepo, fakeDetector)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }

        // Initial full collection (2 comics)
        val initialState = viewModel.uiState.value as LibraryUiState.Success
        assertEquals(2, initialState.comics.size)

        // Search "  piece  " -> case-insensitive & trimmed whitespace -> matches "One Piece"
        viewModel.onSearchQueryChanged("  piece  ")

        val searchState = viewModel.uiState.value as LibraryUiState.Success
        assertEquals(1, searchState.comics.size)
        assertEquals("One Piece", searchState.comics.first().title)
        assertEquals("  piece  ", searchState.searchQuery)

        collectJob.cancel()
    }

    @Test
    fun searchQuery_nonMatchingQuery_emitsSearchNoResultsState() = runTest {
        val rootUri = "content://primary%3AKitsune"
        val comic1 = ComicEntity(id = 1L, rootUriString = rootUri, relativePath = "Comics/OnePiece", title = "One Piece", lastScannedAt = 100L)
        fakeRepo.comicsByRoot.value = mapOf(rootUri to listOf(comic1))
        fakePrefs.setRootUriString(rootUri)

        viewModel = LibraryViewModel(fakeRepo, fakePrefs, fakeSettingsRepo, fakeDetector)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }

        // Search non-matching query
        viewModel.onSearchQueryChanged("Bleach")

        val searchState = viewModel.uiState.value
        assertTrue("Non-matching query MUST emit SearchNoResults state", searchState is LibraryUiState.SearchNoResults)
        val noResults = searchState as LibraryUiState.SearchNoResults
        assertEquals("Bleach", noResults.searchQuery)

        // Clear search query -> restores full collection
        viewModel.onSearchQueryChanged("")

        val restoredState = viewModel.uiState.value
        assertTrue("Clearing query MUST restore Success state with full collection", restoredState is LibraryUiState.Success)
        assertEquals(1, (restoredState as LibraryUiState.Success).comics.size)

        collectJob.cancel()
    }

    @Test
    fun searchQuery_liveObservation_updatesResultWhenCollectionDataChangesUnderUnchangedQuery() = runTest {
        val rootUri = "content://primary%3AKitsune"
        val comic1 = ComicEntity(id = 1L, rootUriString = rootUri, relativePath = "Comics/OnePiece1", title = "One Piece 1", lastScannedAt = 100L)
        fakeRepo.comicsByRoot.value = mapOf(rootUri to listOf(comic1))
        fakePrefs.setRootUriString(rootUri)

        viewModel = LibraryViewModel(fakeRepo, fakePrefs, fakeSettingsRepo, fakeDetector)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }

        // Set search query "Piece"
        viewModel.onSearchQueryChanged("Piece")

        val initialSearch = viewModel.uiState.value as LibraryUiState.Success
        assertEquals(1, initialSearch.comics.size)
        assertEquals("One Piece 1", initialSearch.comics.first().title)

        // Add a second matching comic "One Piece 2" while query "Piece" remains UNCHANGED
        val comic2 = ComicEntity(id = 2L, rootUriString = rootUri, relativePath = "Comics/OnePiece2", title = "One Piece 2", lastScannedAt = 100L)
        fakeRepo.comicsByRoot.value = mapOf(rootUri to listOf(comic1, comic2))

        val updatedSearch = viewModel.uiState.value as LibraryUiState.Success
        assertEquals("UI state MUST dynamically update under unchanged search query when new matching comic added", 2, updatedSearch.comics.size)

        collectJob.cancel()
    }

    @Test
    fun searchQuery_specialCharacters_handledLiterally() = runTest {
        val rootUri = "content://primary%3AKitsune"
        val comicSpecial = ComicEntity(id = 1L, rootUriString = rootUri, relativePath = "Comics/Special", title = "Comic 100% Vol_1\\Test", lastScannedAt = 100L)
        fakeRepo.comicsByRoot.value = mapOf(rootUri to listOf(comicSpecial))
        fakePrefs.setRootUriString(rootUri)

        viewModel = LibraryViewModel(fakeRepo, fakePrefs, fakeSettingsRepo, fakeDetector)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }

        // Raw input "%"
        viewModel.onSearchQueryChanged("%")
        assertTrue(viewModel.uiState.value is LibraryUiState.Success)
        assertEquals("Comic 100% Vol_1\\Test", (viewModel.uiState.value as LibraryUiState.Success).comics.first().title)

        // Raw input "_"
        viewModel.onSearchQueryChanged("_")
        assertTrue(viewModel.uiState.value is LibraryUiState.Success)

        // Raw input "\"
        viewModel.onSearchQueryChanged("\\")
        assertTrue(viewModel.uiState.value is LibraryUiState.Success)

        collectJob.cancel()
    }

    @Test
    fun searchQuery_rootSwitching_updatesResultsForNewRootUnderSameQuery() = runTest {
        val rootA = "content://primary%3ARootA"
        val rootB = "content://primary%3ARootB"

        val comicA = ComicEntity(id = 1L, rootUriString = rootA, relativePath = "Comics/MangaA", title = "Manga A", lastScannedAt = 100L)
        val comicB = ComicEntity(id = 2L, rootUriString = rootB, relativePath = "Comics/MangaB", title = "Manga B", lastScannedAt = 100L)

        fakeRepo.comicsByRoot.value = mapOf(
            rootA to listOf(comicA),
            rootB to listOf(comicB)
        )

        fakePrefs.setRootUriString(rootA)
        viewModel = LibraryViewModel(fakeRepo, fakePrefs, fakeSettingsRepo, fakeDetector)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }

        // Set search query "Manga"
        viewModel.onSearchQueryChanged("Manga")
        assertEquals("Manga A", (viewModel.uiState.value as LibraryUiState.Success).comics.first().title)

        // Switch to Root B under SAME active search query
        fakePrefs.setRootUriString(rootB)

        val stateRootB = viewModel.uiState.value as LibraryUiState.Success
        assertEquals(rootB, stateRootB.rootUriString)
        assertEquals("Manga B", stateRootB.comics.first().title)

        collectJob.cancel()
    }

    @Test
    fun searchQuery_permissionDenied_preservesPermissionDeniedState() = runTest {
        val rootUri = "content://primary%3AKitsune"
        fakePrefs.setRootUriString(rootUri)

        viewModel = LibraryViewModel(fakeRepo, fakePrefs, fakeSettingsRepo, fakeDetector)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }

        viewModel.onSearchQueryChanged("Piece")

        // Revoke permission during active search
        fakeRepo.lastScanResult.value = LibraryScanResult.PermissionDenied("Access revoked")

        val state = viewModel.uiState.value
        assertTrue("PermissionDenied state MUST be preserved during active search", state is LibraryUiState.PermissionDenied)
        assertEquals("Access revoked", (state as LibraryUiState.PermissionDenied).message)

        collectJob.cancel()
    }

    @Test
    fun searchLibrary_endToEnd_rawQueryMatchingLiteralSpecialChars_realRoomDatabase() = runTest {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, com.example.kitsune.data.KitsuneDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val realComicDao = db.comicDao()
        val realChapterDao = db.chapterDao()
        val realProgressDao = db.readingProgressDao()

        val rootUri = "content://primary%3ARoot"
        fakePrefs.setRootUriString(rootUri)

        // Target & Comparison pairs inserted into real Room database
        realComicDao.insert(ComicEntity(rootUriString = rootUri, relativePath = "Comics/P1", title = "Comic 100% Edition"))
        realComicDao.insert(ComicEntity(rootUriString = rootUri, relativePath = "Comics/P2", title = "Comic 1000 Edition"))

        realComicDao.insert(ComicEntity(rootUriString = rootUri, relativePath = "Comics/U1", title = "Comic Vol_1"))
        realComicDao.insert(ComicEntity(rootUriString = rootUri, relativePath = "Comics/U2", title = "Comic VolA1"))

        realComicDao.insert(ComicEntity(rootUriString = rootUri, relativePath = "Comics/B1", title = "Folder\\Series"))
        realComicDao.insert(ComicEntity(rootUriString = rootUri, relativePath = "Comics/B2", title = "FolderXSeries"))

        val realRepo = com.example.kitsune.data.DefaultLibraryRepository(
            com.example.kitsune.data.FakeLibraryScanner(),
            com.example.kitsune.scanner.IncrementalCbzCataloger(com.example.kitsune.data.NoopCbzCataloger),
            realComicDao, realChapterDao, realProgressDao,
            com.example.kitsune.data.CoverExtractor(com.example.kitsune.storage.FakeStorageGateway(), realComicDao, realChapterDao),
            com.example.kitsune.data.CoverCacheManager(java.io.File(System.getProperty("java.io.tmpdir"), "real_search_covers"), com.example.kitsune.data.CoverExtractor(com.example.kitsune.storage.FakeStorageGateway(), realComicDao, realChapterDao), realComicDao, realChapterDao),
            com.example.kitsune.storage.FakeStorageGateway(),
            com.example.kitsune.reader.DefaultCbzArchiveProvider(java.io.File(System.getProperty("java.io.tmpdir"), "real_search_spool"), com.example.kitsune.storage.FakeStorageGateway(), realChapterDao),
            db
        )

        viewModel = LibraryViewModel(realRepo, fakePrefs, fakeSettingsRepo, fakeDetector)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }

        // Allow Room background query executor to initialize observeComicsByRoot
        for (step in 1..50) {
            testDispatcher.scheduler.advanceTimeBy(50)
            if (viewModel.uiState.value is LibraryUiState.Success) break
            Thread.sleep(10)
        }

        // Test 1: Raw user input "100%" -> matches "Comic 100% Edition", "Comic 1000 Edition" MUST NOT match
        viewModel.onSearchQueryChanged("100%")
        testDispatcher.scheduler.advanceUntilIdle()

        val percentState = viewModel.uiState.value as LibraryUiState.Success
        assertEquals(1, percentState.comics.size)
        assertEquals("Comic 100% Edition", percentState.comics.first().title)
        assertFalse(percentState.comics.any { it.title == "Comic 1000 Edition" })

        // Test 2: Raw user input "Vol_1" -> matches "Comic Vol_1", "Comic VolA1" MUST NOT match
        viewModel.onSearchQueryChanged("Vol_1")
        testDispatcher.scheduler.advanceUntilIdle()

        val underscoreState = viewModel.uiState.value as LibraryUiState.Success
        assertEquals(1, underscoreState.comics.size)
        assertEquals("Comic Vol_1", underscoreState.comics.first().title)
        assertFalse(underscoreState.comics.any { it.title == "Comic VolA1" })

        // Test 3: Raw user input "Folder\Series" -> matches "Folder\Series", "FolderXSeries" MUST NOT match
        viewModel.onSearchQueryChanged("Folder\\Series")
        testDispatcher.scheduler.advanceUntilIdle()

        val backslashState = viewModel.uiState.value as LibraryUiState.Success
        assertEquals(1, backslashState.comics.size)
        assertEquals("Folder\\Series", backslashState.comics.first().title)
        assertFalse(backslashState.comics.any { it.title == "FolderXSeries" })

        collectJob.cancel()
        db.close()
    }
}
