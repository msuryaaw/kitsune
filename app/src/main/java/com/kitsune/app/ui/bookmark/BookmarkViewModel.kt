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
import com.kitsune.app.ui.library.ComicStatusSets
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel untuk mengelola seluruh ekosistem Bookmark (Kategori & Konten).
 * REVISION 6.7.9: Ditambahkan mekanisme Multi-Category Cache dan Preloading untuk transisi instan.
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

    @OptIn(FlowPreview::class)
    private val debouncedSearchQuery = _searchQuery
        .debounce(300)
        .distinctUntilChanged()

    private val _selectedPaths = MutableStateFlow<Set<String>>(emptySet())
    val selectedPaths: StateFlow<Set<String>> = _selectedPaths.asStateFlow()

    val selectionMode: StateFlow<Boolean> = _selectedPaths
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val bookmarkedPaths: StateFlow<Set<String>> = bookmarkRepository.getAllBookmarkedComics()
        .map { it.toSet() }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    private val playlistPaths: StateFlow<Set<String>> = playlistRepository.getAllPlaylistComics()
        .map { it.toSet() }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    private val _categories = combine(
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

    val categories: StateFlow<List<BookmarkEntity>> = _categories

    private val _selectedCategoryId = MutableStateFlow<Long?>(null)
    val selectedCategoryId: StateFlow<Long?> = _selectedCategoryId.asStateFlow()

    private val _categoryCache = MutableStateFlow<Map<Long, BookmarkUiState.Success>>(emptyMap())
    val categoryCache: StateFlow<Map<Long, BookmarkUiState.Success>> = _categoryCache.asStateFlow()

    val uiState: StateFlow<BookmarkUiState> = combine(
        _selectedCategoryId,
        _categoryCache
    ) { selectedId, cache ->
        if (selectedId == null) BookmarkUiState.Empty
        else cache[selectedId] ?: BookmarkUiState.Loading
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BookmarkUiState.Loading)

    init {
        setupStateLogic()
    }

    private fun setupStateLogic() {
        combine(
            scannerRepository.allComics.distinctUntilChanged(),
            settingsRepository.settings.distinctUntilChanged(),
            debouncedSearchQuery,
            bookmarkedPaths,
            playlistPaths
        ) { allComics, settings, query, bookmarks, playlists ->
            refreshAllCachedCategories(allComics, settings, query, bookmarks, playlists)
        }.launchIn(viewModelScope)

        _selectedCategoryId.onEach { id ->
            if (id != null) {
                loadAndPreload(id)
            }
        }.launchIn(viewModelScope)
    }

    private suspend fun refreshAllCachedCategories(
        allComics: List<Comic>,
        settings: com.kitsune.app.database.entity.SettingsEntity?,
        query: String,
        bookmarks: Set<String>,
        playlists: Set<String>
    ) {
        val currentCache = _categoryCache.value
        if (currentCache.isEmpty()) return

        val newCache = currentCache.toMutableMap()
        val comicMap = allComics.associateBy { it.relativePath }
        val gridSize = settings?.gridSize ?: 3
        val currentCats = _categories.value

        for (id in currentCache.keys) {
            val paths = bookmarkRepository.getComicsInBookmark(id).first()
            val catName = currentCats.find { it.id == id }?.name ?: ""
            newCache[id] = computeSuccessState(id, catName, paths, comicMap, query, bookmarks, playlists, gridSize)
        }
        _categoryCache.value = newCache
    }

    private fun loadAndPreload(selectedId: Long) {
        viewModelScope.launch {
            ensureCategoryInCache(selectedId)
            
            val allCats = _categories.value
            val currentIndex = allCats.indexOfFirst { it.id == selectedId }
            if (currentIndex >= 0) {
                val prev = allCats.getOrNull(currentIndex - 1)
                val next = allCats.getOrNull(currentIndex + 1)
                
                prev?.let { launch { ensureCategoryInCache(it.id) } }
                next?.let { launch { ensureCategoryInCache(it.id) } }
            }
        }
    }

    private suspend fun ensureCategoryInCache(categoryId: Long) {
        if (_categoryCache.value.containsKey(categoryId)) return

        val allComics = scannerRepository.allComics.first()
        val settings = settingsRepository.settings.first()
        val query = _searchQuery.value
        val bookmarks = bookmarkedPaths.value
        val playlists = playlistPaths.value
        
        val categoryPaths = bookmarkRepository.getComicsInBookmark(categoryId).first()
        val comicMap = allComics.associateBy { it.relativePath }
        val gridSize = settings?.gridSize ?: 3
        val catName = _categories.value.find { it.id == categoryId }?.name ?: ""

        val state = computeSuccessState(categoryId, catName, categoryPaths, comicMap, query, bookmarks, playlists, gridSize)
        
        val newMap = _categoryCache.value.toMutableMap()
        newMap[categoryId] = state
        _categoryCache.value = newMap
    }

    private fun computeSuccessState(
        id: Long,
        name: String,
        paths: List<String>,
        comicMap: Map<String, Comic>,
        query: String,
        bookmarks: Set<String>,
        playlists: Set<String>,
        gridSize: Int
    ): BookmarkUiState.Success {
        val comics = paths.mapNotNull { comicMap[it] }
        val filtered = if (query.isBlank()) comics else comics.filter { it.title.contains(query, ignoreCase = true) }
        
        val statusMap = filtered.associate { comic ->
            val path = comic.relativePath
            val hasBookmark = bookmarks.contains(path)
            val hasPlaylist = playlists.contains(path)
            val statuses = when {
                hasBookmark && hasPlaylist -> ComicStatusSets.BOTH
                hasBookmark -> ComicStatusSets.BOOKMARKED
                hasPlaylist -> ComicStatusSets.IN_PLAYLIST
                else -> ComicStatusSets.EMPTY
            }
            path to statuses
        }

        return BookmarkUiState.Success(
            categoryId = id,
            bookmarkName = name,
            comics = filtered,
            comicStatuses = statusMap,
            gridSize = gridSize
        )
    }

    fun onSearchQueryChange(newQuery: String) {
        _searchQuery.value = newQuery
    }

    fun setSortOrder(order: CollectionSortOrder) {
        _sortOrder.value = order
        _categoryCache.value = emptyMap()
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
            val newCache = _categoryCache.value.toMutableMap()
            newCache.remove(categoryId)
            _categoryCache.value = newCache
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
            _categoryCache.value[id]?.let {
                val newCache = _categoryCache.value.toMutableMap()
                newCache[id] = it.copy(bookmarkName = newName)
                _categoryCache.value = newCache
            }
        }
    }

    fun deleteBookmark(id: Long) {
        viewModelScope.launch {
            bookmarkRepository.deleteBookmark(id)
            val newCache = _categoryCache.value.toMutableMap()
            newCache.remove(id)
            _categoryCache.value = newCache
        }
    }
}

sealed class BookmarkUiState {
    data object Loading : BookmarkUiState()
    data object Empty : BookmarkUiState()
    data class Success(
        val categoryId: Long,
        val bookmarkName: String,
        val comics: List<Comic>,
        val comicStatuses: Map<String, Set<ComicStatus>>,
        val gridSize: Int
    ) : BookmarkUiState()
    data class Error(val message: String) : BookmarkUiState()
}
