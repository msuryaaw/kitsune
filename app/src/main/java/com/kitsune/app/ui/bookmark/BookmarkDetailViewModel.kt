package com.kitsune.app.ui.bookmark

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kitsune.app.data.repository.BookmarkRepository
import com.kitsune.app.data.repository.PlaylistRepository
import com.kitsune.app.data.repository.ScannerRepository
import com.kitsune.app.data.repository.SettingsRepository
import com.kitsune.app.domain.model.Comic
import com.kitsune.app.ui.library.CollectionSortOrder
import com.kitsune.app.ui.library.ComicStatus
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class BookmarkDetailViewModel(
    private val bookmarkId: Long,
    private val bookmarkRepository: BookmarkRepository,
    private val scannerRepository: ScannerRepository,
    private val settingsRepository: SettingsRepository,
    private val playlistRepository: PlaylistRepository
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _sortOrder = MutableStateFlow(CollectionSortOrder.NAME_ASC)
    val sortOrder: StateFlow<CollectionSortOrder> = _sortOrder.asStateFlow()

    private val _uiState = MutableStateFlow<BookmarkDetailUiState>(BookmarkDetailUiState.Loading)
    val uiState: StateFlow<BookmarkDetailUiState> = _uiState.asStateFlow()

    // Selection Mode State
    private val _selectedPaths = MutableStateFlow<Set<String>>(emptySet())
    val selectedPaths: StateFlow<Set<String>> = _selectedPaths.asStateFlow()

    val selectionMode: StateFlow<Boolean> = _selectedPaths
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

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

            // REVISION 6.7.8: Pre-process flows with distinctUntilChanged to ensure stable references
            val bookmarkedInThisCategoryFlow = bookmarkRepository.getComicsInBookmark(bookmarkId).distinctUntilChanged()
            val allComicsFlow = scannerRepository.allComics.distinctUntilChanged()
            val settingsFlow = settingsRepository.settings.distinctUntilChanged()
            val allBookmarksFlow = bookmarkRepository.getAllBookmarkedComics().map { it.toSet() }.distinctUntilChanged()
            val allPlaylistsFlow = playlistRepository.getAllPlaylistComics().map { it.toSet() }.distinctUntilChanged()

            combine(
                bookmarkedInThisCategoryFlow,
                allComicsFlow,
                settingsFlow,
                _searchQuery,
                _sortOrder,
                allBookmarksFlow,
                allPlaylistsFlow
            ) { array ->
                @Suppress("UNCHECKED_CAST")
                val bookmarkedInThisCategory = array[0] as List<String>
                @Suppress("UNCHECKED_CAST")
                val allComics = array[1] as List<Comic>
                val settings = array[2] as com.kitsune.app.database.entity.SettingsEntity?
                val query = array[3] as String
                val order = array[4] as CollectionSortOrder
                @Suppress("UNCHECKED_CAST")
                val allBookmarks = array[5] as Set<String>
                @Suppress("UNCHECKED_CAST")
                val allPlaylists = array[6] as Set<String>

                val gridSize = settings?.gridSize ?: 3
                val comicMap = allComics.associateBy { it.relativePath }
                val comicsInBookmark = bookmarkedInThisCategory.mapNotNull { comicMap[it] }
                
                // 1. Filtering
                var result = if (query.isBlank()) {
                    comicsInBookmark
                } else {
                    comicsInBookmark.filter { it.title.contains(query, ignoreCase = true) }
                }

                // 2. Sorting
                result = when (order) {
                    CollectionSortOrder.NAME_ASC -> result.sortedBy { it.title.lowercase() }
                    CollectionSortOrder.NAME_DESC -> result.sortedByDescending { it.title.lowercase() }
                }

                // 3. Optimized Status Mapping (REVISION 6.7.8)
                val statusMap = result.associate { comic ->
                    val path = comic.relativePath
                    val hasBookmark = allBookmarks.contains(path)
                    val hasPlaylist = allPlaylists.contains(path)
                    
                    val statuses = when {
                        hasBookmark && hasPlaylist -> setOf(ComicStatus.BOOKMARKED, ComicStatus.IN_PLAYLIST)
                        hasBookmark -> setOf(ComicStatus.BOOKMARKED)
                        hasPlaylist -> setOf(ComicStatus.IN_PLAYLIST)
                        else -> emptySet()
                    }
                    path to statuses
                }

                if (result.isEmpty()) {
                    BookmarkDetailUiState.Empty(bookmark.name)
                } else {
                    BookmarkDetailUiState.Success(
                        bookmarkName = bookmark.name,
                        comics = result,
                        comicStatuses = statusMap,
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
        val state = _uiState.value
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
