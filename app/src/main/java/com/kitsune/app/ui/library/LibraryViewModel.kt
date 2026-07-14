package com.kitsune.app.ui.library

import androidx.core.net.toUri
import androidx.lifecycle.viewModelScope
import com.kitsune.app.data.repository.BookmarkRepository
import com.kitsune.app.data.repository.PlaylistRepository
import com.kitsune.app.data.repository.ScannerRepository
import com.kitsune.app.data.repository.SettingsRepository
import com.kitsune.app.domain.model.Comic
import com.kitsune.app.database.entity.BookmarkEntity
import com.kitsune.app.database.entity.PlaylistEntity
import com.kitsune.app.ui.library.base.BaseLibraryViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel untuk mengelola data pada layar Library Komik.
 * Menangani sinkronisasi antara Database dan Filesystem serta logika pencarian dan seleksi massal.
 */
class LibraryViewModel(
    private val scannerRepository: ScannerRepository,
    private val settingsRepository: SettingsRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val playlistRepository: PlaylistRepository
) : BaseLibraryViewModel() {

    private val _errorMessage = MutableStateFlow<String?>(null)

    // Selection State (Spesifik Komik saat ini)
    private val _selectedPaths = MutableStateFlow<Set<String>>(emptySet())
    val selectedPaths: StateFlow<Set<String>> = _selectedPaths.asStateFlow()

    val selectionMode: StateFlow<Boolean> = _selectedPaths
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /**
     * REVISION 6.7.8: Added distinctUntilChanged to ensure stable set references.
     */
    val bookmarkedPaths: StateFlow<Set<String>> = bookmarkRepository.getAllBookmarkedComics()
        .map { it.toSet() }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val playlistPaths: StateFlow<Set<String>> = playlistRepository.getAllPlaylistComics()
        .map { it.toSet() }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val allBookmarks: StateFlow<List<BookmarkEntity>> = bookmarkRepository.getAllBookmarksWithCount()
        .map { list -> list.map { it.bookmark } }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allPlaylists: StateFlow<List<PlaylistEntity>> = playlistRepository.getAllPlaylistsWithCount()
        .map { list -> list.map { it.playlist } }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Tahap 1: Filtering Komik.
     * Menggunakan debouncedSearchQuery dari Base.
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
     * Tahap 2: Mapping Status Visual.
     */
    private val comicStatuses = combine(
        filteredComics,
        bookmarkedPaths,
        playlistPaths
    ) { comics, bookmarks, playlists ->
        comics.associate { comic ->
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
    }.distinctUntilChanged()

    /**
     * Tahap 3: Perakitan Final UI State.
     */
    val uiState: StateFlow<LibraryUiState> = combine(
        filteredComics,
        comicStatuses,
        settingsRepository.settings.distinctUntilChanged(),
        _isRefreshing,
        _errorMessage
    ) { comics, statuses, settings, refreshing, error ->
        val gridSize = settings?.gridSize ?: 3
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

    // Selection Methods
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
        if (state is LibraryUiState.Success) {
            _selectedPaths.value = state.comics.map { it.relativePath }.toSet()
        }
    }

    fun clearSelection() {
        _selectedPaths.value = emptySet()
    }

    // Bulk Operations
    fun addSelectedToBookmarks(bookmarkIds: List<Long>) {
        val paths = _selectedPaths.value.toList()
        if (paths.isEmpty() || bookmarkIds.isEmpty()) return
        
        viewModelScope.launch {
            bookmarkRepository.addComicsToBookmarks(bookmarkIds, paths)
            _snackbarMessage.emit("Added ${paths.size} comics to ${bookmarkIds.size} bookmarks.")
            clearSelection()
        }
    }

    fun addSelectedToPlaylists(playlistIds: List<Long>) {
        val paths = _selectedPaths.value.toList()
        if (paths.isEmpty() || playlistIds.isEmpty()) return
        
        viewModelScope.launch {
            playlistRepository.addComicsToPlaylists(playlistIds, paths)
            _snackbarMessage.emit("Added ${paths.size} comics to ${playlistIds.size} playlists.")
            clearSelection()
        }
    }

    suspend fun createBookmark(name: String): Long {
        return bookmarkRepository.createBookmark(name)
    }

    suspend fun createPlaylist(name: String): Long {
        return playlistRepository.createPlaylist(name)
    }

    /**
     * Implementasi refresh library untuk komik.
     */
    override fun refreshLibrary() {
        if (_isRefreshing.value) return

        viewModelScope.launch {
            _isRefreshing.value = true
            _errorMessage.value = null
            try {
                val settings = settingsRepository.settings.first()
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

/**
 * Representasi State UI untuk layar Library.
 */
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
