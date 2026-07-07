package com.kitsune.app.ui.library

import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kitsune.app.data.repository.BookmarkRepository
import com.kitsune.app.data.repository.PlaylistRepository
import com.kitsune.app.data.repository.ScannerRepository
import com.kitsune.app.data.repository.SettingsRepository
import com.kitsune.app.domain.model.Comic
import com.kitsune.app.database.entity.BookmarkEntity
import com.kitsune.app.database.entity.PlaylistEntity
import kotlinx.coroutines.FlowPreview
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
) : ViewModel() {

    private val _isRefreshing = MutableStateFlow(false)
    private val _errorMessage = MutableStateFlow<String?>(null)
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    /**
     * Optimasi pencarian untuk mencegah recomputation berlebih saat user mengetik.
     */
    @OptIn(FlowPreview::class)
    private val debouncedSearchQuery = _searchQuery
        .debounce(300)
        .distinctUntilChanged()

    // Selection State
    private val _selectedPaths = MutableStateFlow<Set<String>>(emptySet())
    val selectedPaths: StateFlow<Set<String>> = _selectedPaths.asStateFlow()

    val selectionMode: StateFlow<Boolean> = _selectedPaths
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /**
     * Snackbar Feedback State
     */
    private val _snackbarMessage = MutableSharedFlow<String>()
    val snackbarMessage = _snackbarMessage.asSharedFlow()

    /**
     * StateFlow untuk mendapatkan seluruh path komik yang dibookmark secara batch.
     */
    val bookmarkedPaths: StateFlow<Set<String>> = bookmarkRepository.getAllBookmarkedComics()
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    /**
     * StateFlow untuk mendapatkan seluruh path komik yang masuk playlist secara batch.
     */
    val playlistPaths: StateFlow<Set<String>> = playlistRepository.getAllPlaylistComics()
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val allBookmarks: StateFlow<List<BookmarkEntity>> = bookmarkRepository.getAllBookmarksWithCount()
        .map { list -> list.map { it.bookmark } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allPlaylists: StateFlow<List<PlaylistEntity>> = playlistRepository.getAllPlaylistsWithCount()
        .map { list -> list.map { it.playlist } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Tahap 1: Filtering Komik.
     * Hanya dijalankan jika daftar komik atau query pencarian berubah.
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
     * Hanya dijalankan jika hasil filter berubah atau status keanggotaan koleksi berubah.
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
     * Tahap 3: Perakitan Final UI State.
     * Memisahkan logic berat (filter/map) dari logic UI ringan (gridSize, refreshing).
     */
    val uiState: StateFlow<LibraryUiState> = combine(
        filteredComics,
        comicStatuses,
        settingsRepository.settings,
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

    fun onSearchQueryChange(newQuery: String) {
        _searchQuery.value = newQuery
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
     * REVISION 6.7.6: Ditambahkan guard agar refresh tidak berjalan paralel.
     */
    fun refreshLibrary() {
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
