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
 */
class LibraryViewModel(
    private val scannerRepository: ScannerRepository,
    private val settingsRepository: SettingsRepository,
    private val bookmarkRepository: BookmarkRepository
) : BaseLibraryViewModel() {

    private val _errorMessage = MutableStateFlow<String?>(null)

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
     * Stage 1: Filtering.
     */
    private val filteredComics = combine(
        scannerRepository.allComics,
        debouncedSearchQuery
    ) { comics, query ->
        if (query.isBlank()) {
            comics
        } else {
            comics.filter { comic ->
                comic.title.contains(query, ignoreCase = true)
            }
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
        _isRefreshing,
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
        refreshLibrary()
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

    override fun refreshLibrary() {
        if (_isRefreshing.value) return

        viewModelScope.launch {
            _isRefreshing.value = true
            _errorMessage.value = null
            try {
                // REVISION 10.5.7: Use cached settings
                val settings = settingsRepository.getSettingsCached()
                val rootUriString = settings?.rootFolderUri
                
                if (!rootUriString.isNullOrEmpty()) {
                    scannerRepository.performIncrementalScan(rootUriString.toUri())
                } else {
                    _errorMessage.value = "Root folder not configured"
                }
            } catch (e: Exception) {
                _errorMessage.value = "Failed to scan library: ${e.message}"
            } finally {
                _isRefreshing.value = false
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
