package com.kitsune.app.ui.bookmark

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kitsune.app.data.repository.BookmarkRepository
import com.kitsune.app.data.repository.ScannerRepository
import com.kitsune.app.data.repository.SettingsRepository
import com.kitsune.app.domain.model.Comic
import com.kitsune.app.ui.library.CollectionSortOrder
import com.kitsune.app.ui.library.ComicStatus
import com.kitsune.app.ui.library.ComicStatusSets
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel for Bookmark Category Detail.
 * REVISION 10.5.11: Optimized Flow pipeline to separate mapping from filtering.
 */
class BookmarkDetailViewModel(
    private val bookmarkId: Long,
    private val bookmarkRepository: BookmarkRepository,
    private val scannerRepository: ScannerRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    @OptIn(FlowPreview::class)
    private val debouncedSearchQuery = _searchQuery
        .debounce(300)
        .distinctUntilChanged()

    private val _sortOrder = MutableStateFlow(CollectionSortOrder.NAME_ASC)
    val sortOrder: StateFlow<CollectionSortOrder> = _sortOrder.asStateFlow()

    private val _uiState = MutableStateFlow<BookmarkDetailUiState>(BookmarkDetailUiState.Loading)
    val uiState: StateFlow<BookmarkDetailUiState> = _uiState.asStateFlow()

    private val _selectedPaths = MutableStateFlow<Set<String>>(emptySet())
    val selectedPaths: StateFlow<Set<String>> = _selectedPaths.asStateFlow()

    val selectionMode: StateFlow<Boolean> = _selectedPaths
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /**
     * Comic Index built once when library changes.
     */
    private val comicIndex = scannerRepository.allComics
        .map { list -> list.associateBy { it.relativePath } }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    init {
        loadBookmarkDetail()
    }

    private fun loadBookmarkDetail() {
        viewModelScope.launch {
            val bookmark = bookmarkRepository.getBookmarkById(bookmarkId)
            if (bookmark == null) {
                _uiState.value = BookmarkDetailUiState.Error("Bookmark not found")
                return@launch
            }

            // STAGE 1: Data Preparation (Heavier mapping only when membership or index changes)
            val mappedComicsFlow = combine(
                bookmarkRepository.getComicsInBookmark(bookmarkId),
                comicIndex,
                bookmarkRepository.getAllBookmarkedComics().map { it.toSet() }
            ) { paths, index, allBookmarks ->
                paths.mapNotNull { path ->
                    val comic = index[path] ?: return@mapNotNull null
                    val statuses = if (allBookmarks.contains(path)) ComicStatusSets.BOOKMARKED else ComicStatusSets.EMPTY
                    comic to statuses
                }
            }.distinctUntilChanged()

            // STAGE 2: UI Presentation (Lighter filtering/sorting)
            combine(
                mappedComicsFlow,
                debouncedSearchQuery,
                _sortOrder,
                settingsRepository.settings.map { it?.gridSize ?: 3 }.distinctUntilChanged()
            ) { items, query, order, gridSize ->
                
                var result = if (query.isBlank()) {
                    items
                } else {
                    items.filter { (comic, _) -> comic.title.contains(query, ignoreCase = true) }
                }

                result = when (order) {
                    CollectionSortOrder.NAME_ASC -> result.sortedBy { it.first.title.lowercase() }
                    CollectionSortOrder.NAME_DESC -> result.sortedByDescending { it.first.title.lowercase() }
                }

                if (result.isEmpty() && query.isBlank()) {
                    BookmarkDetailUiState.Empty(bookmark.name)
                } else {
                    BookmarkDetailUiState.Success(
                        bookmarkName = bookmark.name,
                        comics = result.map { it.first },
                        comicStatuses = result.associate { it.first.relativePath to it.second },
                        gridSize = gridSize
                    )
                }
            }.catch { e ->
                _uiState.value = BookmarkDetailUiState.Error(e.message ?: "Unknown error")
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    fun onSearchQueryChange(newQuery: String) {
        _searchQuery.value = newQuery
    }

    fun setSortOrder(order: CollectionSortOrder) {
        _sortOrder.value = order
    }

    fun toggleSelection(path: String) {
        val current = _selectedPaths.value
        if (current.contains(path)) {
            _selectedPaths.value = current - path
        } else {
            _selectedPaths.value = current + path
        }
    }

    fun selectAll() {
        val state = uiState.value
        if (state is BookmarkDetailUiState.Success) {
            _selectedPaths.value = state.comics.map { it.relativePath }.toSet()
        }
    }

    fun clearSelection() {
        _selectedPaths.value = emptySet()
    }

    fun removeSelected() {
        val paths = _selectedPaths.value.toList()
        if (paths.isEmpty()) return
        
        viewModelScope.launch {
            bookmarkRepository.removeComicsFromBookmark(bookmarkId, paths)
            clearSelection()
        }
    }

    fun renameBookmark(newName: String) {
        viewModelScope.launch {
            bookmarkRepository.renameBookmark(bookmarkId, newName)
        }
    }

    fun deleteBookmark() {
        viewModelScope.launch {
            bookmarkRepository.deleteBookmark(bookmarkId)
        }
    }
}

/**
 * REVISION 10.5.12: Explicit UI State definitions for Bookmark Detail.
 */
sealed class BookmarkDetailUiState {
    data object Loading : BookmarkDetailUiState()
    data class Empty(val bookmarkName: String) : BookmarkDetailUiState()
    data class Success(
        val bookmarkName: String,
        val comics: List<Comic>,
        val comicStatuses: Map<String, Set<ComicStatus>>,
        val gridSize: Int
    ) : BookmarkDetailUiState()
    data class Error(val message: String) : BookmarkDetailUiState()
}
