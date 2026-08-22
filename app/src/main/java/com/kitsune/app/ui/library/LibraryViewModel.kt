package com.kitsune.app.ui.library

import androidx.core.net.toUri
import androidx.lifecycle.viewModelScope
import com.kitsune.app.data.repository.BookmarkRepository
import com.kitsune.app.data.repository.ScannerRepository
import com.kitsune.app.data.repository.SettingsRepository
import com.kitsune.app.domain.model.Comic
import com.kitsune.app.database.entity.BookmarkEntity
import com.kitsune.app.ui.library.base.BaseLibraryViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel untuk mengelola data pada layar Library Komik.
 * Menangani sinkronisasi antara Database dan Filesystem serta logika pencarian dan seleksi massal.
 * 
 * REVISION 10.5.6: Optimized settings retrieval and Flow pipeline stability.
 * REVISION 11.1.6: Implemented metadata tag search support.
 */
class LibraryViewModel(
    scannerRepository: ScannerRepository,
    private val settingsRepository: SettingsRepository,
    private val bookmarkRepository: BookmarkRepository
) : BaseLibraryViewModel(scannerRepository) {

    private val _errorMessage = MutableStateFlow<String?>(null)

    private val _sortOrder = MutableStateFlow(ComicSortOrder.TITLE_ASC)
    val sortOrder: StateFlow<ComicSortOrder> = _sortOrder.asStateFlow()

    /**
     * Observable set of bookmarked paths.
     */
    val bookmarkedPaths: StateFlow<Set<String>> = bookmarkRepository.getAllBookmarkedComics()
        .map { it.toSet() }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    /**
     * Observable list of all bookmarks.
     */
    val allBookmarks: StateFlow<List<BookmarkEntity>> = bookmarkRepository.getAllBookmarksWithCount()
        .map { list -> list.map { it.bookmark } }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Stage 1: Filtering & Sorting.
     * REVISION 11.3.2: Search now checks clean title, author, and language.
     * REVISION 11.3.3: Applied ComicSortOrder.
     */
    private val filteredComics = combine(
        scannerRepository.allComics,
        debouncedSearchQuery,
        _sortOrder
    ) { comics, query, order ->
        val filtered = if (query.isBlank()) {
            comics
        } else {
            val trimmedQuery = query.trim()
            comics.filter { comic ->
                comic.displayTitle.contains(trimmedQuery, ignoreCase = true) ||
                comic.author?.contains(trimmedQuery, ignoreCase = true) == true ||
                comic.language?.contains(trimmedQuery, ignoreCase = true) == true ||
                comic.type?.contains(trimmedQuery, ignoreCase = true) == true ||
                comic.searchTags?.contains(trimmedQuery, ignoreCase = true) == true
            }
        }

        when (order) {
            ComicSortOrder.TITLE_ASC -> filtered.sortedBy { it.displayTitle.lowercase() }
            ComicSortOrder.TITLE_DESC -> filtered.sortedByDescending { it.displayTitle.lowercase() }
            ComicSortOrder.AUTHOR_ASC -> filtered.sortedBy { (it.author ?: "").lowercase() }
            ComicSortOrder.AUTHOR_DESC -> filtered.sortedByDescending { (it.author ?: "").lowercase() }
            ComicSortOrder.DATE_ADDED_DESC -> filtered.sortedByDescending { it.lastModified }
        }
    }.distinctUntilChanged()

    /**
     * Stage 2: Visual Status Mapping.
     */
    private val comicStatuses = combine(
        filteredComics,
        bookmarkedPaths
    ) { comics, bookmarks ->
        comics.associate { comic ->
            val path = comic.relativePath
            val hasBookmark = bookmarks.contains(path)
            
            val statuses = when {
                hasBookmark -> ComicStatusSets.BOOKMARKED
                else -> ComicStatusSets.EMPTY
            }
            path to statuses
        }
    }.distinctUntilChanged()

    /**
     * Stage 3: Final UI State assembly.
     */
    val uiState: StateFlow<LibraryUiState> = combine(
        filteredComics,
        comicStatuses,
        settingsRepository.settings.map { it?.gridSize ?: 3 }.distinctUntilChanged(),
        isRefreshing,
        _errorMessage
    ) { comics, statuses, gridSize, refreshing, error ->
        val query = _searchQuery.value

        when {
            error != null -> LibraryUiState.Error(error)
            refreshing && comics.isEmpty() && query.isBlank() -> LibraryUiState.Loading
            comics.isEmpty() -> LibraryUiState.Empty
            else -> LibraryUiState.Success(
                comics = comics,
                comicStatuses = statuses,
                isRefreshing = refreshing,
                gridSize = gridSize
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = LibraryUiState.Loading
    )

    init {
        refreshLibraryInternal(force = false) // Auto-scan on init with cooldown guard
    }

    fun selectAll() {
        val state = uiState.value
        if (state is LibraryUiState.Success) {
            _selectedPaths.value = state.comics.map { it.relativePath }.toSet()
        }
    }

    fun addSelectedToBookmarks(bookmarkIds: List<Long>) {
        val paths = _selectedPaths.value.toList()
        if (paths.isEmpty() || bookmarkIds.isEmpty()) return
        
        viewModelScope.launch {
            bookmarkRepository.addComicsToBookmarks(bookmarkIds, paths)
            _snackbarMessage.emit("Added ${paths.size} comics to ${bookmarkIds.size} bookmarks.")
            clearSelection()
        }
    }

    suspend fun createBookmark(name: String): Long {
        return bookmarkRepository.createBookmark(name)
    }

    fun setSortOrder(order: ComicSortOrder) {
        _sortOrder.value = order
    }

    override fun refreshLibrary() {
        refreshLibraryInternal(force = true) // Manual refresh bypasses cooldown
    }

    private fun refreshLibraryInternal(force: Boolean) {
        viewModelScope.launch {
            _errorMessage.value = null
            try {
                // REVISION 12.2.2: Added cooldown and empty check guards
                val settings = settingsRepository.getSettingsCached()
                val rootUriString = settings?.rootFolderUri
                
                if (rootUriString.isNullOrEmpty()) {
                    _errorMessage.value = "Root folder not configured"
                    return@launch
                }

                val lastScan = settings?.lastScanTime ?: 0L
                val now = System.currentTimeMillis()
                val cooldownMs = 30 * 60 * 1000L
                val currentComics = scannerRepository.allComics.first()
                val isLibraryEmpty = currentComics.isEmpty()

                // Migration Auto-Scan Guard (REVISION 12.5.1)
                if (lastScan == 0L && !isLibraryEmpty && !force) {
                    settingsRepository.updateLastScanTime(now)
                    return@launch
                }

                if (force || isLibraryEmpty || (now - lastScan > cooldownMs)) {
                    scannerRepository.performIncrementalScan(rootUriString.toUri())
                }
            } catch (e: Exception) {
                _errorMessage.value = "Failed to scan library: ${e.message}"
            }
        }
    }
}

sealed class LibraryUiState {
    data object Loading : LibraryUiState()
    data object Empty : LibraryUiState()
    data class Success(
        val comics: List<Comic>,
        val comicStatuses: Map<String, Set<ComicStatus>>,
        val isRefreshing: Boolean,
        val gridSize: Int
    ) : LibraryUiState()
    data class Error(val message: String) : LibraryUiState()
}
