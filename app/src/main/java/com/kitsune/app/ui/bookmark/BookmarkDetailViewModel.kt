package com.kitsune.app.ui.bookmark

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kitsune.app.core.SearchUtils
import com.kitsune.app.data.repository.BookmarkRepository
import com.kitsune.app.data.repository.ScannerRepository
import com.kitsune.app.data.repository.SettingsRepository
import com.kitsune.app.domain.model.Comic
import com.kitsune.app.ui.library.ComicSortOrder
import com.kitsune.app.ui.library.ComicStatus
import com.kitsune.app.ui.library.ComicStatusSets
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel for Bookmark Category Detail.
 * REVISION 10.5.11: Optimized Flow pipeline to separate mapping from filtering.
 * REVISION Masalah 3: Using Multi-token AND search logic.
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

    private val _sortOrder = MutableStateFlow(ComicSortOrder.TITLE_ASC)
    val sortOrder: StateFlow<ComicSortOrder> = _sortOrder.asStateFlow()

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
                
                val result = if (query.isBlank()) {
                    items
                } else {
                    items.filter { (comic, _) -> 
                        SearchUtils.matches(
                            query = query,
                            searchableFields = listOf(
                                comic.displayTitle,
                                comic.author,
                                comic.language,
                                comic.type,
                                comic.searchTags
                            )
                        )
                    }
                }

                val sortedResult = when (order) {
                    ComicSortOrder.TITLE_ASC -> result.sortedBy { it.first.displayTitle.lowercase() }
                    ComicSortOrder.TITLE_DESC -> result.sortedByDescending { it.first.displayTitle.lowercase() }
                    ComicSortOrder.AUTHOR_ASC -> result.sortedBy { (it.first.author ?: "").lowercase() }
                    ComicSortOrder.AUTHOR_DESC -> result.sortedByDescending { (it.first.author ?: "").lowercase() }
                    ComicSortOrder.DATE_ADDED_DESC -> result.sortedByDescending { it.first.lastModified }
                }

                if (sortedResult.isEmpty() && query.isBlank()) {
                    BookmarkDetailUiState.Empty(bookmark.name)
                } else {
                    BookmarkDetailUiState.Success(
                        bookmarkName = bookmark.name,
                        comics = sortedResult.map { it.first },
                        comicStatuses = sortedResult.associate { it.first.relativePath to it.second },
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

    fun setSortOrder(order: ComicSortOrder) {
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
