package com.example.kitsune.ui.screens.library

import com.example.kitsune.core.FakeRootFolderPreferences
import com.example.kitsune.data.entity.ComicEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryUiImprovementTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val rootUri = "content://root"

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun library_bookmarkedComicIds_queriedFromCrossRefRelation_notLegacyBoolean() = runTest {
        val fakeRepo = FakeLibraryRepository()
        val fakePrefs = FakeRootFolderPreferences(rootUri)
        val fakeSettingsRepo = FakeSettingsRepository()
        val fakeDetector = FakeComicsDirectoryDetector()

        val comic1 = ComicEntity(id = 1L, rootUriString = rootUri, relativePath = "Comics/A", title = "Comic A", isBookmarked = true)
        val comic2 = ComicEntity(id = 2L, rootUriString = rootUri, relativePath = "Comics/B", title = "Comic B", isBookmarked = false)

        fakeRepo.comicsByRoot.value = mapOf(rootUri to listOf(comic1, comic2))

        val viewModel = LibraryViewModel(fakeRepo, fakePrefs, fakeSettingsRepo, fakeDetector)

        val state = viewModel.uiState.first()
        assertTrue("UI State MUST be Success", state is LibraryUiState.Success)
        val success = state as LibraryUiState.Success

        assertTrue("Bookmarked comic ID 1L MUST be present in bookmarkedComicIds set", success.bookmarkedComicIds.contains(1L))
        assertFalse("Unbookmarked comic ID 2L MUST NOT be present in bookmarkedComicIds set", success.bookmarkedComicIds.contains(2L))
    }

    @Test
    fun libraryViewModel_resetFiltersAndSorting_resetsState() = runTest {
        val fakeRepo = FakeLibraryRepository()
        val fakePrefs = FakeRootFolderPreferences(rootUri)
        val fakeSettingsRepo = FakeSettingsRepository()
        val fakeDetector = FakeComicsDirectoryDetector()

        val viewModel = LibraryViewModel(fakeRepo, fakePrefs, fakeSettingsRepo, fakeDetector)

        viewModel.onSearchQueryChanged("One Piece")
        viewModel.setBookmarkedFilter(true)
        viewModel.setSortOption(SortOption.LAST_READ_DESC)

        assertEquals("One Piece", viewModel.searchQuery.value)
        assertTrue(viewModel.isBookmarkedOnly.value)
        assertEquals(SortOption.LAST_READ_DESC, viewModel.sortOption.value)

        viewModel.resetFiltersAndSorting()

        assertEquals("", viewModel.searchQuery.value)
        assertFalse(viewModel.isBookmarkedOnly.value)
        assertEquals(SortOption.TITLE_ASC, viewModel.sortOption.value)
    }

    @Test
    fun library_searchQuery_filtersComicsAndSearchNoResultsView() = runTest {
        val fakeRepo = FakeLibraryRepository()
        val fakePrefs = FakeRootFolderPreferences(rootUri)
        val fakeSettingsRepo = FakeSettingsRepository()
        val fakeDetector = FakeComicsDirectoryDetector()

        val comic1 = ComicEntity(id = 1L, rootUriString = rootUri, relativePath = "Comics/Bleach", title = "Bleach")
        val comic2 = ComicEntity(id = 2L, rootUriString = rootUri, relativePath = "Comics/Naruto", title = "Naruto")

        fakeRepo.comicsByRoot.value = mapOf(rootUri to listOf(comic1, comic2))

        val viewModel = LibraryViewModel(fakeRepo, fakePrefs, fakeSettingsRepo, fakeDetector)

        viewModel.onSearchQueryChanged("Bleach")

        val state = viewModel.uiState.first { it is LibraryUiState.Success } as LibraryUiState.Success
        assertEquals(1, state.comics.size)
        assertEquals("Bleach", state.comics.first().title)

        viewModel.onSearchQueryChanged("NonExistent")

        val searchState = viewModel.uiState.first { it is LibraryUiState.SearchNoResults } as LibraryUiState.SearchNoResults
        assertEquals("NonExistent", searchState.searchQuery)
    }
}
