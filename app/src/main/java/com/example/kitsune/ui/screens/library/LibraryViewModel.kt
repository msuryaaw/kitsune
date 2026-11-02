package com.example.kitsune.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.kitsune.core.RootFolderPreferences
import com.example.kitsune.data.LibraryRepository
import com.example.kitsune.data.SettingsRepository
import com.example.kitsune.scanner.ComicsDirectoryDetector
import com.example.kitsune.scanner.ComicsDirectoryState
import com.example.kitsune.scanner.LibraryScanResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    private val libraryRepository: LibraryRepository,
    private val rootFolderPreferences: RootFolderPreferences,
    private val settingsRepository: SettingsRepository,
    private val comicsDirectoryDetector: ComicsDirectoryDetector
) : ViewModel() {

    private val isRefreshing = MutableStateFlow(false)
    val searchQuery = MutableStateFlow("")
    val sortOption = MutableStateFlow(SortOption.TITLE_ASC)
    val isBookmarkedOnly = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            rootFolderPreferences.observeRootUriString()
                .distinctUntilChanged()
                .collect { rootUri ->
                    if (!rootUri.isNullOrBlank()) {
                        performAutoScan(rootUri)
                    }
                }
        }
    }

    private suspend fun performAutoScan(rootUri: String) {
        if (isRefreshing.value) return
        isRefreshing.value = true
        try {
            libraryRepository.ensureComicsDirectory()
            libraryRepository.syncLibrary(rootUri)
        } catch (_: Exception) {
        } finally {
            isRefreshing.value = false
        }
    }

    val uiState: StateFlow<LibraryUiState> = rootFolderPreferences
        .observeRootUriString()
        .flatMapLatest { rootUri ->
            if (rootUri.isNullOrBlank()) {
                flowOf(LibraryUiState.RootNotConfigured)
            } else {
                combine(
                    libraryRepository.observeComicsByRoot(rootUri),
                    settingsRepository.observeSettings(),
                    libraryRepository.observeLastScanResult(),
                    isRefreshing,
                    searchQuery,
                    sortOption,
                    isBookmarkedOnly
                ) { flows: Array<Any?> ->
                    @Suppress("UNCHECKED_CAST")
                    val comics = flows[0] as List<com.example.kitsune.data.entity.ComicEntity>
                    val settings = flows[1] as com.example.kitsune.data.entity.SettingsEntity
                    val scanResult = flows[2] as LibraryScanResult?
                    val refreshing = flows[3] as Boolean
                    val query = flows[4] as String
                    val sort = flows[5] as SortOption
                    val bookmarkedOnly = flows[6] as Boolean

                    if (refreshing) {
                        LibraryUiState.Loading
                    } else if (comics.isNotEmpty()) {
                        val trimmedQuery = query.trim()

                        // 1. Search Filter
                        val searchFiltered = if (trimmedQuery.isBlank()) {
                            comics
                        } else {
                            comics.filter { it.title.contains(trimmedQuery, ignoreCase = true) }
                        }

                        // 2. Bookmark Filter
                        val bookmarkFiltered = if (bookmarkedOnly) {
                            searchFiltered.filter { it.isBookmarked }
                        } else {
                            searchFiltered
                        }

                        // 3. Natural Deterministic Sorting
                        val finalComics = when (sort) {
                            SortOption.TITLE_ASC ->
                                bookmarkFiltered.sortedWith(
                                    Comparator<com.example.kitsune.data.entity.ComicEntity> { c1, c2 ->
                                        com.example.kitsune.scanner.NaturalOrderComparator.compare(c1.title, c2.title)
                                    }.thenBy { it.relativePath }
                                )
                            SortOption.TITLE_DESC ->
                                bookmarkFiltered.sortedWith(
                                    Comparator<com.example.kitsune.data.entity.ComicEntity> { c1, c2 ->
                                        com.example.kitsune.scanner.NaturalOrderComparator.compare(c2.title, c1.title)
                                    }.thenBy { it.relativePath }
                                )
                            SortOption.LAST_READ_DESC ->
                                bookmarkFiltered.sortedWith(
                                    compareByDescending<com.example.kitsune.data.entity.ComicEntity> { it.lastReadAt ?: 0L }
                                        .thenComparing { c1, c2 ->
                                            com.example.kitsune.scanner.NaturalOrderComparator.compare(c1.title, c2.title)
                                        }
                                        .thenBy { it.relativePath }
                                )
                        }

                        if (finalComics.isNotEmpty()) {
                            val issuesCount = (scanResult as? LibraryScanResult.Success)?.issues?.size ?: 0
                            LibraryUiState.Success(
                                rootUriString = rootUri,
                                comics = finalComics,
                                gridColumns = settings.gridColumns.coerceIn(2, 4),
                                issuesCount = issuesCount,
                                searchQuery = query,
                                sortOption = sort,
                                isBookmarkedOnly = bookmarkedOnly
                            )
                        } else {
                            LibraryUiState.SearchNoResults(
                                rootUriString = rootUri,
                                searchQuery = query,
                                gridColumns = settings.gridColumns.coerceIn(2, 4),
                                sortOption = sort,
                                isBookmarkedOnly = bookmarkedOnly
                            )
                        }
                    } else {
                        mapEmptyComicsState(rootUri, scanResult)
                    }
                }
                .catch { throwable ->
                    emit(
                        LibraryUiState.Error(
                            throwable.localizedMessage
                                ?: "Error memuat daftar komik dari database."
                        )
                    )
                }
            }
        }
        .catch { throwable ->
            emit(
                LibraryUiState.Error(
                    throwable.localizedMessage ?: "Error tidak terduga saat memantau status root."
                )
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = LibraryUiState.Loading
        )

    private fun mapEmptyComicsState(rootUri: String, scanResult: LibraryScanResult?): LibraryUiState {
        return when (scanResult) {
            is LibraryScanResult.RootNotConfigured -> LibraryUiState.RootNotConfigured
            is LibraryScanResult.PermissionDenied -> LibraryUiState.PermissionDenied(scanResult.message)
            is LibraryScanResult.ComicsDirectoryNotFound -> LibraryUiState.ComicsDirectoryNotFound
            is LibraryScanResult.EmptyComicsDirectory -> LibraryUiState.EmptyComicsDirectory
            is LibraryScanResult.Error -> LibraryUiState.Error(scanResult.exception.localizedMessage ?: "Gagal memindai koleksi komik.")
            else -> {
                when (val dirState = comicsDirectoryDetector.detectComicsDirectory()) {
                    is ComicsDirectoryState.RootNotConfigured -> LibraryUiState.RootNotConfigured
                    is ComicsDirectoryState.PermissionDenied -> LibraryUiState.PermissionDenied(dirState.message)
                    is ComicsDirectoryState.DirectoryNotFound, is ComicsDirectoryState.NotADirectory -> LibraryUiState.ComicsDirectoryNotFound
                    is ComicsDirectoryState.Empty -> LibraryUiState.EmptyComicsDirectory
                    is ComicsDirectoryState.Ready -> LibraryUiState.NoValidChapters
                    is ComicsDirectoryState.Error -> LibraryUiState.Error(dirState.exception.localizedMessage ?: "Gagal memeriksa direktori Comics.")
                }
            }
        }
    }

    fun refreshLibrary() {
        viewModelScope.launch {
            val rootUri = rootFolderPreferences.getRootUriString()
            if (!rootUri.isNullOrBlank()) {
                isRefreshing.value = true
                try {
                    libraryRepository.ensureComicsDirectory()
                    libraryRepository.syncLibrary(rootUri)
                } finally {
                    isRefreshing.value = false
                }
            }
        }
    }

    fun createComicsDirectory() {
        viewModelScope.launch {
            val rootUri = rootFolderPreferences.getRootUriString()
            if (!rootUri.isNullOrBlank()) {
                isRefreshing.value = true
                try {
                    libraryRepository.ensureComicsDirectory()
                    libraryRepository.syncLibrary(rootUri)
                } finally {
                    isRefreshing.value = false
                }
            }
        }
    }

    fun onSearchQueryChanged(query: String) {
        searchQuery.value = query
    }

    fun setSortOption(option: SortOption) {
        sortOption.value = option
    }

    fun setBookmarkedFilter(bookmarkedOnly: Boolean) {
        isBookmarkedOnly.value = bookmarkedOnly
    }

    fun resetFiltersAndSorting() {
        searchQuery.value = ""
        isBookmarkedOnly.value = false
        sortOption.value = SortOption.TITLE_ASC
    }

    fun setGridColumns(columns: Int) {
        viewModelScope.launch {
            settingsRepository.setGridColumns(columns)
        }
    }

    fun toggleBookmark(comicId: Long, currentIsBookmarked: Boolean) {
        viewModelScope.launch {
            try {
                libraryRepository.setBookmarked(comicId, !currentIsBookmarked)
            } catch (_: Exception) {}
        }
    }

    suspend fun getCoverFile(comicId: Long): com.example.kitsune.data.CoverCacheResult {
        return libraryRepository.getCoverFile(comicId)
    }

    suspend fun releaseCoverFile(file: java.io.File) {
        libraryRepository.releaseCoverFile(file)
    }

    class Factory(
        private val libraryRepository: LibraryRepository,
        private val rootFolderPreferences: RootFolderPreferences,
        private val settingsRepository: SettingsRepository,
        private val comicsDirectoryDetector: ComicsDirectoryDetector
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(LibraryViewModel::class.java)) {
                return LibraryViewModel(libraryRepository, rootFolderPreferences, settingsRepository, comicsDirectoryDetector) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
