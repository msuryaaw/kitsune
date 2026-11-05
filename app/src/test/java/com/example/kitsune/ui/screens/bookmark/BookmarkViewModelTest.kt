package com.example.kitsune.ui.screens.bookmark

import com.example.kitsune.core.RootFolderPreferences
import com.example.kitsune.data.CoverCacheResult
import com.example.kitsune.data.CoverResult
import com.example.kitsune.data.LibraryRepository
import com.example.kitsune.data.SettingsRepository
import com.example.kitsune.data.entity.BookmarkEntity
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class FakeBookmarkLibraryRepository : LibraryRepository {
    val bookmarkedComicsByRoot = MutableStateFlow<Map<String, List<ComicEntity>>>(emptyMap())
    val allComicsById = mutableMapOf<Long, ComicEntity>()
    val readingProgressByComicId = mutableMapOf<Long, ReadingProgressEntity>()
    val chaptersByComicId = mutableMapOf<Long, List<ChapterEntity>>()
    val collectionsFlow = MutableStateFlow<List<BookmarkEntity>>(emptyList())
    val comicsInCollectionFlow = MutableStateFlow<Map<Long, List<ComicEntity>>>(emptyMap())

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
    override fun observeChaptersByComicId(comicId: Long): Flow<List<ChapterEntity>> =
        flowOf(chaptersByComicId[comicId] ?: emptyList())
    override fun observeComicById(comicId: Long): Flow<ComicEntity?> = flowOf(allComicsById[comicId])
    override suspend fun verifyChapterAvailable(chapterId: Long) = com.example.kitsune.data.ChapterAvailabilityResult.Missing
    override suspend fun getReadingProgress(comicId: Long): ReadingProgressEntity? = readingProgressByComicId[comicId]
    override suspend fun getChapterById(chapterId: Long): ChapterEntity? = null
    override suspend fun saveReadingProgress(progress: ReadingProgressEntity) {
        readingProgressByComicId[progress.comicId] = progress
    }
    override fun flushReadingProgress(progress: ReadingProgressEntity) {
        readingProgressByComicId[progress.comicId] = progress
    }
    override suspend fun fetchCover(comicId: Long): CoverResult = CoverResult.Unavailable("Fake")
    override suspend fun getCoverFile(comicId: Long): CoverCacheResult = CoverCacheResult.Unavailable("Fake")
    override suspend fun releaseCoverFile(file: File) {}
    override fun observeLastScanResult(): Flow<LibraryScanResult?> = flowOf(null)
    override suspend fun openCbzArchive(chapterId: Long) = com.example.kitsune.reader.CbzArchiveResult.ChapterMissing
    override suspend fun ensureComicsDirectory(): StorageResult<StorageEntry> = StorageResult.Success(StorageEntry("Comics", "Comics", true))

    override fun observeAllBookmarkCollections(): Flow<List<BookmarkEntity>> = collectionsFlow
    override fun observeComicsInBookmarkCollection(bookmarkId: Long, rootUri: String): Flow<List<ComicEntity>> =
        comicsInCollectionFlow.map { map -> map[bookmarkId] ?: emptyList() }

    override fun observeCollectionsForComic(comicId: Long): Flow<List<BookmarkEntity>> = collectionsFlow
    override suspend fun getCollectionsForComic(comicId: Long): List<BookmarkEntity> = collectionsFlow.value
    override suspend fun createBookmarkCollection(name: String): Long {
        val newId = (collectionsFlow.value.maxOfOrNull { it.id } ?: 0L) + 1L
        val newCol = BookmarkEntity(id = newId, name = name, displayOrder = collectionsFlow.value.size)
        collectionsFlow.value = collectionsFlow.value + newCol
        return newId
    }
    override suspend fun renameBookmarkCollection(bookmarkId: Long, newName: String) {
        collectionsFlow.value = collectionsFlow.value.map { if (it.id == bookmarkId) it.copy(name = newName) else it }
    }
    override suspend fun deleteBookmarkCollection(bookmarkId: Long) {
        collectionsFlow.value = collectionsFlow.value.filterNot { it.id == bookmarkId }
        val currentMap = comicsInCollectionFlow.value.toMutableMap()
        currentMap.remove(bookmarkId)
        comicsInCollectionFlow.value = currentMap
    }
    override suspend fun addComicToCollection(bookmarkId: Long, comicId: Long) {
        val comic = allComicsById[comicId] ?: return
        val currentMap = comicsInCollectionFlow.value.toMutableMap()
        val list = (currentMap[bookmarkId] ?: emptyList()).toMutableList()
        if (list.none { it.id == comicId }) {
            list.add(comic)
            currentMap[bookmarkId] = list
            comicsInCollectionFlow.value = currentMap
        }
    }
    override suspend fun addComicsToCollection(bookmarkId: Long, comicIds: List<Long>) {
        comicIds.forEach { addComicToCollection(bookmarkId, it) }
    }
    override suspend fun removeComicFromCollection(bookmarkId: Long, comicId: Long) {
        val currentMap = comicsInCollectionFlow.value.toMutableMap()
        val list = (currentMap[bookmarkId] ?: emptyList()).filterNot { it.id == comicId }
        currentMap[bookmarkId] = list
        comicsInCollectionFlow.value = currentMap
    }
    override suspend fun removeComicsFromCollection(bookmarkId: Long, comicIds: List<Long>) {
        val currentMap = comicsInCollectionFlow.value.toMutableMap()
        val list = (currentMap[bookmarkId] ?: emptyList()).filterNot { comicIds.contains(it.id) }
        currentMap[bookmarkId] = list
        comicsInCollectionFlow.value = currentMap
    }
    override suspend fun toggleComicInCollection(bookmarkId: Long, comicId: Long) {}
    override suspend fun reorderBookmarkCollections(collectionsInOrder: List<BookmarkEntity>) {
        collectionsFlow.value = collectionsInOrder
    }
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
    override suspend fun setKeepScreenAwake(keepScreenAwake: Boolean) {}
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
    fun landingUiState_emitsRootNotConfigured_whenRootUriIsNull() = runTest {
        fakeRootPrefs.rootUriFlow.value = null

        viewModel = BookmarkViewModel(fakeRepo, fakeRootPrefs, fakeSettingsRepo)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }

        assertEquals(BookmarkLandingUiState.RootNotConfigured, viewModel.uiState.value)

        collectJob.cancel()
    }

    @Test
    fun landingUiState_emitsEmptyNoCollections_whenNoBookmarkCollectionsExist() = runTest {
        val rootUri = "content://root"
        fakeRootPrefs.rootUriFlow.value = rootUri
        fakeRepo.collectionsFlow.value = emptyList()

        viewModel = BookmarkViewModel(fakeRepo, fakeRootPrefs, fakeSettingsRepo)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }

        assertEquals(BookmarkLandingUiState.EmptyNoCollections, viewModel.uiState.value)

        collectJob.cancel()
    }

    @Test
    fun landingUiState_emitsSuccess_whenCollectionsExist() = runTest {
        val rootUri = "content://root"
        fakeRootPrefs.rootUriFlow.value = rootUri
        val col1 = BookmarkEntity(id = 1L, name = "Shonen")
        val col2 = BookmarkEntity(id = 2L, name = "Seinen")
        fakeRepo.collectionsFlow.value = listOf(col1, col2)

        viewModel = BookmarkViewModel(fakeRepo, fakeRootPrefs, fakeSettingsRepo)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }

        val state = viewModel.uiState.value
        assertTrue("Landing state must be Success", state is BookmarkLandingUiState.Success)
        assertEquals(2, (state as BookmarkLandingUiState.Success).collections.size)
        assertEquals("Shonen", state.collections[0].name)
        assertEquals("Seinen", state.collections[1].name)

        collectJob.cancel()
    }

    @Test
    fun landing_createRenameDeleteReorderCollections() = runTest {
        val rootUri = "content://root"
        fakeRootPrefs.rootUriFlow.value = rootUri

        viewModel = BookmarkViewModel(fakeRepo, fakeRootPrefs, fakeSettingsRepo)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }

        // Create
        viewModel.createCollection("Favorites")
        assertEquals(1, fakeRepo.collectionsFlow.value.size)
        assertEquals("Favorites", fakeRepo.collectionsFlow.value.first().name)

        // Rename
        val id = fakeRepo.collectionsFlow.value.first().id
        viewModel.renameCollection(id, "Top Manga")
        assertEquals("Top Manga", fakeRepo.collectionsFlow.value.first().name)

        // Reorder
        viewModel.createCollection("Secondary")
        val list = fakeRepo.collectionsFlow.value
        viewModel.reorderCollections(listOf(list[1], list[0]))
        assertEquals("Secondary", fakeRepo.collectionsFlow.value.first().name)

        // Delete
        viewModel.deleteCollection(id)
        assertEquals(1, fakeRepo.collectionsFlow.value.size)
        assertEquals("Secondary", fakeRepo.collectionsFlow.value.first().name)

        collectJob.cancel()
    }

    @Test
    fun collectionDetailViewModel_scopedQuery_onlyReturnsMembersOfActiveCollection() = runTest {
        val rootUri = "content://root"
        fakeRootPrefs.rootUriFlow.value = rootUri

        val col1 = BookmarkEntity(id = 1L, name = "Col 1")
        val col2 = BookmarkEntity(id = 2L, name = "Col 2")
        fakeRepo.collectionsFlow.value = listOf(col1, col2)

        val comicA = ComicEntity(id = 10L, rootUriString = rootUri, relativePath = "Comics/A", title = "Comic A", isBookmarked = true)
        val comicB = ComicEntity(id = 20L, rootUriString = rootUri, relativePath = "Comics/B", title = "Comic B", isBookmarked = true)
        val comicC = ComicEntity(id = 30L, rootUriString = rootUri, relativePath = "Comics/C", title = "Comic C", isBookmarked = true)

        fakeRepo.allComicsById[10L] = comicA
        fakeRepo.allComicsById[20L] = comicB
        fakeRepo.allComicsById[30L] = comicC

        // Col 1 has Comic A and Comic B; Col 2 has Comic B and Comic C
        fakeRepo.comicsInCollectionFlow.value = mapOf(
            1L to listOf(comicA, comicB),
            2L to listOf(comicB, comicC)
        )

        val detailVm1 = CollectionDetailViewModel(1L, fakeRepo, fakeRootPrefs, fakeSettingsRepo)
        val job1 = launch(testDispatcher) { detailVm1.uiState.collect() }

        val state1 = detailVm1.uiState.value as CollectionDetailUiState.Success
        assertEquals(2, state1.comics.size)
        assertTrue(state1.comics.any { it.id == 10L })
        assertTrue(state1.comics.any { it.id == 20L })
        assertTrue(state1.comics.none { it.id == 30L })

        val detailVm2 = CollectionDetailViewModel(2L, fakeRepo, fakeRootPrefs, fakeSettingsRepo)
        val job2 = launch(testDispatcher) { detailVm2.uiState.collect() }

        val state2 = detailVm2.uiState.value as CollectionDetailUiState.Success
        assertEquals(2, state2.comics.size)
        assertTrue(state2.comics.any { it.id == 20L })
        assertTrue(state2.comics.any { it.id == 30L })
        assertTrue(state2.comics.none { it.id == 10L })

        job1.cancel()
        job2.cancel()
    }

    @Test
    fun collectionDetailViewModel_searchAndStatusFilterAndSort() = runTest {
        val rootUri = "content://root"
        fakeRootPrefs.rootUriFlow.value = rootUri

        val col1 = BookmarkEntity(id = 1L, name = "Col 1")
        fakeRepo.collectionsFlow.value = listOf(col1)

        val comicA = ComicEntity(id = 10L, rootUriString = rootUri, relativePath = "Comics/A", title = "Attack on Titan", author = "Isayama", isBookmarked = true)
        val comicB = ComicEntity(id = 20L, rootUriString = rootUri, relativePath = "Comics/B", title = "Berserk", author = "Miura", isBookmarked = true)

        fakeRepo.allComicsById[10L] = comicA
        fakeRepo.allComicsById[20L] = comicB

        // Comic A is Completed (Ch1 10 pages, progress page 9)
        val ch1 = ChapterEntity(id = 101L, comicId = 10L, relativePath = "Ch1.cbz", chapterNumber = 1, displayName = "Ch1.cbz", pageCount = 10)
        fakeRepo.chaptersByComicId[10L] = listOf(ch1)
        fakeRepo.readingProgressByComicId[10L] = ReadingProgressEntity(comicId = 10L, lastReadChapterId = 101L, lastReadChapterNumber = 1, chapterRelativePath = "Ch1.cbz", pageIndex = 9, updatedAt = 1000L)

        // Comic B is Not Completed (no progress)
        fakeRepo.chaptersByComicId[20L] = listOf(ChapterEntity(id = 201L, comicId = 20L, relativePath = "Ch1.cbz", chapterNumber = 1, displayName = "Ch1.cbz", pageCount = 10))

        fakeRepo.comicsInCollectionFlow.value = mapOf(1L to listOf(comicA, comicB))

        val detailVm = CollectionDetailViewModel(1L, fakeRepo, fakeRootPrefs, fakeSettingsRepo)
        val job = launch(testDispatcher) { detailVm.uiState.collect() }

        // Filter Completed
        detailVm.setStatusFilter(BookmarkStatusFilter.COMPLETED)
        var state = detailVm.uiState.value as CollectionDetailUiState.Success
        assertEquals(1, state.comics.size)
        assertEquals("Attack on Titan", state.comics.first().title)

        // Filter Not Completed
        detailVm.setStatusFilter(BookmarkStatusFilter.NOT_COMPLETED)
        state = detailVm.uiState.value as CollectionDetailUiState.Success
        assertEquals(1, state.comics.size)
        assertEquals("Berserk", state.comics.first().title)

        // Search
        detailVm.setStatusFilter(BookmarkStatusFilter.ALL)
        detailVm.onSearchQueryChanged("Miura")
        state = detailVm.uiState.value as CollectionDetailUiState.Success
        assertEquals(1, state.comics.size)
        assertEquals("Berserk", state.comics.first().title)

        // Clear search
        detailVm.onSearchQueryChanged("")
        state = detailVm.uiState.value as CollectionDetailUiState.Success
        assertEquals(2, state.comics.size)

        job.cancel()
    }

    @Test
    fun collectionDetailViewModel_multiMembershipBulkRemoval_onlyRemovesFromActiveCollection() = runTest {
        val rootUri = "content://root"
        fakeRootPrefs.rootUriFlow.value = rootUri

        val col1 = BookmarkEntity(id = 1L, name = "Col 1")
        val col2 = BookmarkEntity(id = 2L, name = "Col 2")
        fakeRepo.collectionsFlow.value = listOf(col1, col2)

        val sharedComic = ComicEntity(id = 10L, rootUriString = rootUri, relativePath = "Comics/Shared", title = "Shared Comic", isBookmarked = true)
        fakeRepo.allComicsById[10L] = sharedComic

        fakeRepo.comicsInCollectionFlow.value = mapOf(
            1L to listOf(sharedComic),
            2L to listOf(sharedComic)
        )

        val detailVm1 = CollectionDetailViewModel(1L, fakeRepo, fakeRootPrefs, fakeSettingsRepo)
        val job1 = launch(testDispatcher) { detailVm1.uiState.collect() }

        // Select and bulk remove from Col 1
        detailVm1.toggleComicSelection(10L)
        assertTrue(detailVm1.isSelectionMode.value)
        assertEquals(1, detailVm1.selectedComicIds.value.size)

        detailVm1.bulkRemoveFromCollection()

        val afterStateCol1 = detailVm1.uiState.value as CollectionDetailUiState.Success
        assertEquals(0, afterStateCol1.comics.size)
        assertTrue("Col 1 list must be empty", fakeRepo.comicsInCollectionFlow.value[1L]?.isEmpty() == true)

        // Col 2 must still retain sharedComic!
        val detailVm2 = CollectionDetailViewModel(2L, fakeRepo, fakeRootPrefs, fakeSettingsRepo)
        val job2 = launch(testDispatcher) { detailVm2.uiState.collect() }

        val afterStateCol2 = detailVm2.uiState.value as CollectionDetailUiState.Success
        assertEquals(1, afterStateCol2.comics.size)
        assertEquals("Shared Comic", afterStateCol2.comics.first().title)

        job1.cancel()
        job2.cancel()
    }

    @Test
    fun collectionDetailViewModel_invalidOrDeletedCollection_emitsCollectionNotFound() = runTest {
        val rootUri = "content://root"
        fakeRootPrefs.rootUriFlow.value = rootUri
        fakeRepo.collectionsFlow.value = emptyList() // collection 999 does not exist

        val detailVm = CollectionDetailViewModel(999L, fakeRepo, fakeRootPrefs, fakeSettingsRepo)
        val job = launch(testDispatcher) { detailVm.uiState.collect() }

        assertEquals(CollectionDetailUiState.CollectionNotFound, detailVm.uiState.value)

        job.cancel()
    }

    @Test
    fun setGridColumns_updatesSettingsRepository() = runTest {
        val rootUri = "content://root"
        fakeRootPrefs.rootUriFlow.value = rootUri
        fakeRepo.collectionsFlow.value = listOf(BookmarkEntity(id = 1L, name = "Manga"))

        val detailVm = CollectionDetailViewModel(1L, fakeRepo, fakeRootPrefs, fakeSettingsRepo)
        val collectJob = launch(testDispatcher) { detailVm.uiState.collect() }

        detailVm.setGridColumns(4)

        val currentState = detailVm.uiState.value as CollectionDetailUiState.Success
        assertEquals(4, currentState.gridColumns)

        collectJob.cancel()
    }
}
