package com.example.kitsune.ui.screens.library

import com.example.kitsune.core.FakeRootFolderPreferences
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FakeLibraryRepository : LibraryRepository {
    val comicsByRoot = MutableStateFlow<Map<String, List<ComicEntity>>>(emptyMap())
    var shouldThrowError = false
    var errorMessage = "Database query failed"

    override suspend fun syncLibrary(rootUri: String): LibraryScanResult {
        return LibraryScanResult.RootNotConfigured
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

    override fun observeChaptersByComicId(comicId: Long): Flow<List<ChapterEntity>> {
        return flowOf(emptyList())
    }

    override suspend fun getReadingProgress(comicId: Long): ReadingProgressEntity? = null

    override suspend fun saveReadingProgress(progress: ReadingProgressEntity) {}

    override suspend fun setBookmarked(comicId: Long, bookmarked: Boolean) {}

    override suspend fun fetchCover(comicId: Long): CoverResult = CoverResult.Unavailable("Fake")
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
}

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var fakeRepo: FakeLibraryRepository
    private lateinit var fakePrefs: FakeRootFolderPreferences
    private lateinit var fakeSettingsRepo: FakeSettingsRepository
    private lateinit var viewModel: LibraryViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeRepo = FakeLibraryRepository()
        fakePrefs = FakeRootFolderPreferences()
        fakeSettingsRepo = FakeSettingsRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun uiState_initiallyRootNotConfigured_whenNoRootSelected() = runTest {
        viewModel = LibraryViewModel(fakeRepo, fakePrefs, fakeSettingsRepo)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }

        val currentState = viewModel.uiState.value
        assertTrue(
            "State must be RootNotConfigured when no root URI is set",
            currentState is LibraryUiState.RootNotConfigured
        )

        collectJob.cancel()
    }

    @Test
    fun uiState_emitsEmptyComics_whenRootConfiguredButCollectionEmpty() = runTest {
        val rootUri = "content://com.android.externalstorage.documents/tree/primary%3AKitsune"
        fakePrefs.setRootUriString(rootUri)

        viewModel = LibraryViewModel(fakeRepo, fakePrefs, fakeSettingsRepo)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }

        val currentState = viewModel.uiState.value
        assertTrue(
            "State must be EmptyComics when repo emits empty list",
            currentState is LibraryUiState.EmptyComics
        )
        assertEquals(rootUri, (currentState as LibraryUiState.EmptyComics).rootUriString)

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

        viewModel = LibraryViewModel(fakeRepo, fakePrefs, fakeSettingsRepo)
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

        viewModel = LibraryViewModel(fakeRepo, fakePrefs, fakeSettingsRepo)
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

        viewModel = LibraryViewModel(fakeRepo, fakePrefs, fakeSettingsRepo)
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
        viewModel = LibraryViewModel(fakeRepo, fakePrefs, fakeSettingsRepo)
        assertEquals("Initial value before flow observation must be Loading", LibraryUiState.Loading, viewModel.uiState.value)
    }

    @Test
    fun uiState_emitsError_whenRepoThrowsException() = runTest {
        val rootUri = "content://primary%3AKitsune"
        fakePrefs.setRootUriString(rootUri)
        fakeRepo.shouldThrowError = true
        fakeRepo.errorMessage = "Room DB Disk I/O Failure"

        viewModel = LibraryViewModel(fakeRepo, fakePrefs, fakeSettingsRepo)
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

        viewModel = LibraryViewModel(fakeRepo, fakePrefs, fakeSettingsRepo)
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
}
