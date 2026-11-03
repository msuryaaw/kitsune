package com.example.kitsune.ui.screens.bookmark

import com.example.kitsune.core.RootFolderPreferences
import com.example.kitsune.data.CoverCacheResult
import com.example.kitsune.data.CoverResult
import com.example.kitsune.data.LibraryRepository
import com.example.kitsune.data.SettingsRepository
import com.example.kitsune.data.entity.BookmarkEntity
import com.example.kitsune.data.entity.ComicBookmarkCrossRef
import com.example.kitsune.data.entity.ChapterEntity
import com.example.kitsune.data.entity.ComicEntity
import com.example.kitsune.data.entity.ReadingProgressEntity
import com.example.kitsune.data.entity.SettingsEntity
import com.example.kitsune.data.model.ComicWithProgress
import com.example.kitsune.scanner.LibraryScanResult
import com.example.kitsune.storage.StorageEntry
import com.example.kitsune.storage.StorageResult
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class FakeBookmarkLibraryRepository : LibraryRepository {
    val bookmarkedComicsByRoot = MutableStateFlow<Map<String, List<ComicEntity>>>(emptyMap())
    val allComicsById = mutableMapOf<Long, ComicEntity>()

    override fun observeBookmarkedComicsByRoot(rootUri: String): Flow<List<ComicEntity>> =
        bookmarkedComicsByRoot.map { map ->
            (map[rootUri] ?: emptyList()).filter { it.isBookmarked }
        }

    override suspend fun setBookmarked(comicId: Long, bookmarked: Boolean) {
        val comic = allComicsById[comicId] ?: return
        val updatedComic = comic.copy(isBookmarked = bookmarked)
        allComicsById[comicId] = updatedComic

        val currentMap = bookmarkedComicsByRoot.value.toMutableMap()
        val rootList = (currentMap[comic.rootUriString] ?: emptyList()).toMutableList()
        val index = rootList.indexOfFirst { it.id == comicId }
        if (index >= 0) {
            rootList[index] = updatedComic
        } else {
            rootList.add(updatedComic)
        }
        currentMap[comic.rootUriString] = rootList
        bookmarkedComicsByRoot.value = currentMap
    }

    override suspend fun syncLibrary(rootUri: String): LibraryScanResult = LibraryScanResult.RootNotConfigured
    override fun observeComicsByRoot(rootUri: String): Flow<List<ComicEntity>> = flowOf(emptyList())
    override fun observeRecentlyReadByRoot(rootUri: String): Flow<List<ComicEntity>> = flowOf(emptyList())
    override fun observeRecentlyReadWithProgressByRoot(rootUri: String): Flow<List<ComicWithProgress>> = flowOf(emptyList())
    override suspend fun clearHistoryForComic(comicId: Long) {}
    override suspend fun clearAllHistoryByRoot(rootUri: String) {}
    override fun observeChaptersByComicId(comicId: Long): Flow<List<ChapterEntity>> = flowOf(emptyList())
    override fun observeComicById(comicId: Long): Flow<ComicEntity?> = flowOf(null)
    override suspend fun verifyChapterAvailable(chapterId: Long) = com.example.kitsune.data.ChapterAvailabilityResult.Missing
    override suspend fun getReadingProgress(comicId: Long): ReadingProgressEntity? = null
    override suspend fun getChapterById(chapterId: Long): ChapterEntity? = null
    override suspend fun saveReadingProgress(progress: ReadingProgressEntity) {}
    override fun flushReadingProgress(progress: ReadingProgressEntity) {}
    override suspend fun fetchCover(comicId: Long): CoverResult = CoverResult.Unavailable("Fake")
    override suspend fun getCoverFile(comicId: Long): CoverCacheResult = CoverCacheResult.Unavailable("Fake")
    override suspend fun releaseCoverFile(file: File) {}
    override fun observeLastScanResult(): Flow<LibraryScanResult?> = flowOf(null)
    override suspend fun openCbzArchive(chapterId: Long) = com.example.kitsune.reader.CbzArchiveResult.ChapterMissing
    override suspend fun ensureComicsDirectory(): StorageResult<StorageEntry> = StorageResult.Success(StorageEntry("Comics", "Comics", true))

    val collectionsFlow = MutableStateFlow<List<BookmarkEntity>>(listOf(BookmarkEntity(id = 1L, name = "Favorit")))

    override fun observeAllBookmarkCollections(): Flow<List<BookmarkEntity>> = collectionsFlow
    override fun observeComicsInBookmarkCollection(bookmarkId: Long, rootUri: String): Flow<List<ComicEntity>> = observeBookmarkedComicsByRoot(rootUri)
    override fun observeCollectionsForComic(comicId: Long): Flow<List<BookmarkEntity>> = collectionsFlow
    override suspend fun getCollectionsForComic(comicId: Long): List<BookmarkEntity> = collectionsFlow.value
    override suspend fun createBookmarkCollection(name: String): Long {
        val newId = (collectionsFlow.value.maxOfOrNull { it.id } ?: 0L) + 1L
        val newCol = BookmarkEntity(id = newId, name = name)
        collectionsFlow.value = collectionsFlow.value + newCol
        return newId
    }
    override suspend fun renameBookmarkCollection(bookmarkId: Long, newName: String) {
        collectionsFlow.value = collectionsFlow.value.map { if (it.id == bookmarkId) it.copy(name = newName) else it }
    }
    override suspend fun deleteBookmarkCollection(bookmarkId: Long) {
        collectionsFlow.value = collectionsFlow.value.filterNot { it.id == bookmarkId }
    }
    override suspend fun addComicToCollection(bookmarkId: Long, comicId: Long) {}
    override suspend fun addComicsToCollection(bookmarkId: Long, comicIds: List<Long>) {}
    override suspend fun removeComicFromCollection(bookmarkId: Long, comicId: Long) {}
    override suspend fun removeComicsFromCollection(bookmarkId: Long, comicIds: List<Long>) {}
    override suspend fun toggleComicInCollection(bookmarkId: Long, comicId: Long) {}
    override suspend fun reorderBookmarkCollections(collectionsInOrder: List<BookmarkEntity>) {}
}

class FakeBookmarkRootPrefs(initialRoot: String? = null) : RootFolderPreferences {
    val rootUriFlow = MutableStateFlow(initialRoot)
    override fun getRootUriString(): String? = rootUriFlow.value
    override fun setRootUriString(uriString: String?) { rootUriFlow.value = uriString }
    override fun observeRootUriString(): Flow<String?> = rootUriFlow
}

class FakeBookmarkSettingsRepo(initialSettings: SettingsEntity = SettingsEntity()) : SettingsRepository {
    val settingsFlow = MutableStateFlow(initialSettings)
    override fun observeSettings(): Flow<SettingsEntity> = settingsFlow
    override suspend fun setGridColumns(columns: Int) {
        settingsFlow.value = settingsFlow.value.copy(gridColumns = columns)
    }
    override suspend fun setReaderMode(readerMode: String) {}
    override suspend fun setReadingDirection(readingDirection: String) {}
    override suspend fun setPageFit(pageFit: String) {}
    override suspend fun setTheme(theme: String) {}
    override suspend fun setReadingMode(readingMode: String) {
        settingsFlow.value = settingsFlow.value.copy(readingMode = readingMode)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class BookmarkViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var fakeRepo: FakeBookmarkLibraryRepository
    private lateinit var fakeRootPrefs: FakeBookmarkRootPrefs
    private lateinit var fakeSettingsRepo: FakeBookmarkSettingsRepo
    private lateinit var viewModel: BookmarkViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeRepo = FakeBookmarkLibraryRepository()
        fakeRootPrefs = FakeBookmarkRootPrefs()
        fakeSettingsRepo = FakeBookmarkSettingsRepo()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun uiState_emitsRootNotConfigured_whenRootUriIsNull() = runTest {
        fakeRootPrefs.rootUriFlow.value = null

        viewModel = BookmarkViewModel(fakeRepo, fakeRootPrefs, fakeSettingsRepo)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }

        assertEquals(BookmarkUiState.RootNotConfigured, viewModel.uiState.value)

        collectJob.cancel()
    }

    @Test
    fun uiState_emitsEmpty_whenNoBookmarkedComicsExist() = runTest {
        val rootUri = "content://root"
        fakeRootPrefs.rootUriFlow.value = rootUri
        fakeRepo.bookmarkedComicsByRoot.value = mapOf(rootUri to emptyList())

        viewModel = BookmarkViewModel(fakeRepo, fakeRootPrefs, fakeSettingsRepo)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }

        assertEquals(BookmarkUiState.Empty, viewModel.uiState.value)

        collectJob.cancel()
    }

    @Test
    fun uiState_emitsSuccess_andDynamicallyUpdates_whenBookmarkToggled() = runTest {
        val rootUri = "content://root"
        fakeRootPrefs.rootUriFlow.value = rootUri

        val comic1 = ComicEntity(id = 1L, rootUriString = rootUri, relativePath = "Comics/A", title = "Comic A", isBookmarked = true)
        val comic2 = ComicEntity(id = 2L, rootUriString = rootUri, relativePath = "Comics/B", title = "Comic B", isBookmarked = true)

        fakeRepo.allComicsById[1L] = comic1
        fakeRepo.allComicsById[2L] = comic2
        fakeRepo.bookmarkedComicsByRoot.value = mapOf(rootUri to listOf(comic1, comic2))

        viewModel = BookmarkViewModel(fakeRepo, fakeRootPrefs, fakeSettingsRepo)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }

        val initialState = viewModel.uiState.value
        assertTrue("Initial state must be Success", initialState is BookmarkUiState.Success)
        assertEquals(2, (initialState as BookmarkUiState.Success).bookmarkedComics.size)

        // Unbookmark comic 1 from BookmarkViewModel
        viewModel.toggleBookmark(1L, currentIsBookmarked = true)

        val updatedState = viewModel.uiState.value as BookmarkUiState.Success
        assertEquals(1, updatedState.bookmarkedComics.size)
        assertEquals(2L, updatedState.bookmarkedComics.first().id)

        // Unbookmark comic 2 -> state becomes Empty dynamically!
        viewModel.toggleBookmark(2L, currentIsBookmarked = true)

        assertEquals("State must become Empty when last comic is unbookmarked", BookmarkUiState.Empty, viewModel.uiState.value)

        collectJob.cancel()
    }

    @Test
    fun setGridColumns_updatesSettingsRepository() = runTest {
        val rootUri = "content://root"
        fakeRootPrefs.rootUriFlow.value = rootUri
        val comic1 = ComicEntity(id = 1L, rootUriString = rootUri, relativePath = "Comics/A", title = "Comic A", isBookmarked = true)
        fakeRepo.allComicsById[1L] = comic1
        fakeRepo.bookmarkedComicsByRoot.value = mapOf(rootUri to listOf(comic1))

        viewModel = BookmarkViewModel(fakeRepo, fakeRootPrefs, fakeSettingsRepo)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }

        viewModel.setGridColumns(4)

        val currentState = viewModel.uiState.value as BookmarkUiState.Success
        assertEquals(4, currentState.gridColumns)

        collectJob.cancel()
    }
}
