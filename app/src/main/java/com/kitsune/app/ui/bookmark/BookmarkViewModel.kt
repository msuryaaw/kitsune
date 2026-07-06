package com.kitsune.app.ui.bookmark

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kitsune.app.data.repository.BookmarkRepository
import com.kitsune.app.data.repository.PlaylistRepository
import com.kitsune.app.data.repository.ScannerRepository
import com.kitsune.app.data.repository.SettingsRepository
import com.kitsune.app.domain.model.Comic
import com.kitsune.app.database.entity.BookmarkEntity
import com.kitsune.app.ui.library.CollectionSortOrder
import com.kitsune.app.ui.library.ComicStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel untuk mengelola seluruh ekosistem Bookmark (Kategori & Konten).
 * Mendukung in-memory sorting kategori (Phase 6.7.5).
 */
class BookmarkViewModel(
    private val bookmarkRepository: BookmarkRepository,
    private val scannerRepository: ScannerRepository,
    private val settingsRepository: SettingsRepository,
    private val playlistRepository: PlaylistRepository
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _sortOrder = MutableStateFlow(CollectionSortOrder.NAME_ASC)
    val sortOrder: StateFlow<CollectionSortOrder> = _sortOrder.asStateFlow()

    /**
     * Optimasi pencarian untuk mencegah recomputation berlebih saat user mengetik.
     */
    @OptIn(FlowPreview::class)
    private val debouncedSearchQuery = _searchQuery
        .debounce(300)
        .distinctUntilChanged()

    // Selection Mode State
    private val _selectedPaths = MutableStateFlow<Set<String>>(emptySet())
    val selectedPaths: StateFlow<Set<String>> = _selectedPaths.asStateFlow()

    val selectionMode: StateFlow<Boolean> = _selectedPaths
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /**
     * Daftar seluruh kategori bookmark yang ada, diurutkan di memory (Phase 6.7.5).
     */
    val categories: StateFlow<List<BookmarkEntity>> = combine(
        bookmarkRepository.getAllBookmarksWithCount(),
        _sortOrder
    ) { list, order ->
        val sorted = when (order) {
            CollectionSortOrder.NAME_ASC -> list.sortedBy { it.bookmark.name.lowercase() }
            CollectionSortOrder.NAME_DESC -> list.sortedByDescending { it.bookmark.name.lowercase() }
        }
        sorted.map { it.bookmark }
    }.onEach { list ->
        val currentId = _selectedCategoryId.value
        if (list.isNotEmpty()) {
            if (currentId == null || list.none { it.id == currentId }) {
                _selectedCategoryId.value = list.first().id
            }
        } else {
            _selectedCategoryId.value = null
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * StateFlow untuk mendapatkan seluruh path komik yang dibookmark.
     */
    val bookmarkedPaths: StateFlow<Set<String>> = bookmarkRepository.getAllBookmarkedComics()
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    /**
     * StateFlow untuk mendapatkan seluruh path komik yang masuk playlist.
     */
    val playlistPaths: StateFlow<Set<String>> = playlistRepository.getAllPlaylistComics()
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    private val _selectedCategoryId = MutableStateFlow<Long?>(null)
    val selectedCategoryId: StateFlow<Long?> = _selectedCategoryId.asStateFlow()

    /**
     * Tahap 1: Mendapatkan komik untuk kategori terpilih.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val currentCategoryComics = _selectedCategoryId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList())
        else bookmarkRepository.getComicsInBookmark(id)
    }.combine(scannerRepository.allComics) { categoryPaths, allComics ->
        val comicMap = allComics.associateBy { it.relativePath }
        categoryPaths.mapNotNull { comicMap[it] }
    }.distinctUntilChanged()

    /**
     * Tahap 2: Filtering komik berdasarkan search query.
     */
    private val filteredComics = combine(
        currentCategoryComics,
        debouncedSearchQuery
    ) { comics, query ->
        if (query.isBlank()) {
            comics
        } else {
            comics.filter { it.title.contains(query, ignoreCase = true) }
        }
    }.distinctUntilChanged()

    /**
     * Tahap 3: Mapping Status Visual.
     */
    private val comicStatuses = combine(
        filteredComics,
        bookmarkedPaths,
        playlistPaths
    ) { comics, bookmarks, playlists ->
        comics.associate { comic ->
            val path = comic.relativePath
            val statuses = mutableSetOf<ComicStatus>()
            if (bookmarks.contains(path)) statuses.add(ComicStatus.BOOKMARKED)
            if (playlists.contains(path)) statuses.add(ComicStatus.IN_PLAYLIST)
            path to statuses.toSet()
        }
    }.distinctUntilChanged()

    /**
     * Tahap 4: Perakitan Final UI State.
     */
    val uiState: StateFlow<BookmarkUiState> = combine(
        filteredComics,
        comicStatuses,
        settingsRepository.settings,
        _selectedCategoryId
    ) { comics, statuses, settings, selectedId ->
        val gridSize = settings?.gridSize ?: 3
        
        if (selectedId == null) {
            BookmarkUiState.Empty
        } else if (comics.isEmpty()) {
            BookmarkUiState.Empty
        } else {
            BookmarkUiState.Success(
                categoryId = selectedId,
                comics = comics,
                comicStatuses = statuses,
                gridSize = gridSize
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = BookmarkUiState.Loading
    )

    fun onSearchQueryChange(newQuery: String) {
        _searchQuery.value = newQuery
    }

    fun setSortOrder(order: CollectionSortOrder) {
        _sortOrder.value = order
    }

    fun selectCategory(id: Long?) {
        _selectedCategoryId.value = id
        clearSelection()
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
        val currentState = uiState.value
        if (currentState is BookmarkUiState.Success) {
            _selectedPaths.value = currentState.comics.map { it.relativePath }.toSet()
        }
    }

    fun clearSelection() {
        _selectedPaths.value = emptySet()
    }

    fun removeSelected() {
        val paths = _selectedPaths.value.toList()
        val categoryId = _selectedCategoryId.value
        if (paths.isEmpty() || categoryId == null) return
        
        viewModelScope.launch {
            bookmarkRepository.removeComicsFromBookmark(categoryId, paths)
            clearSelection()
        }
    }

    fun createBookmark(name: String) {
        viewModelScope.launch {
            val newId = bookmarkRepository.createBookmark(name)
            if (_selectedCategoryId.value == null) {
                _selectedCategoryId.value = newId
            }
        }
    }

    fun renameBookmark(id: Long, newName: String) {
        viewModelScope.launch {
            bookmarkRepository.renameBookmark(id, newName)
        }
    }

    fun deleteBookmark(id: Long) {
        viewModelScope.launch {
            bookmarkRepository.deleteBookmark(id)
        }
    }
}

sealed class BookmarkUiState {
    data object Loading : BookmarkUiState()
    data object Empty : BookmarkUiState()
    data class Success(
        val categoryId: Long,
        val comics: List<Comic>,
        val comicStatuses: Map<String, Set<ComicStatus>>,
        val gridSize: Int
    ) : BookmarkUiState()
    data class Error(val message: String) : BookmarkUiState()
}
