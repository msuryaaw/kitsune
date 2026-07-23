package com.kitsune.app.ui.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kitsune.app.data.repository.BookmarkRepository
import com.kitsune.app.data.repository.PlaylistRepository
import com.kitsune.app.data.repository.ScannerRepository
import com.kitsune.app.data.repository.SettingsRepository
import com.kitsune.app.data.repository.VideoRepository
import com.kitsune.app.domain.model.Comic
import com.kitsune.app.domain.model.Video
import com.kitsune.app.ui.components.media.MediaUiModel
import com.kitsune.app.ui.components.media.mapper.toMediaUiModel
import com.kitsune.app.ui.library.CollectionSortOrder
import com.kitsune.app.ui.library.ComicStatusSets
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel untuk menampilkan detail Playlist (Unified Media).
 * Mampu menampilkan Komik dan Video secara bersamaan.
 * 
 * REVISION 8.4.1: Unified Media Indexing (Comics + Videos).
 */
class PlaylistDetailViewModel(
    private val playlistId: Long,
    private val playlistRepository: PlaylistRepository,
    private val scannerRepository: ScannerRepository,
    private val videoRepository: VideoRepository,
    private val settingsRepository: SettingsRepository,
    private val bookmarkRepository: BookmarkRepository
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    @OptIn(FlowPreview::class)
    private val debouncedSearchQuery = _searchQuery
        .debounce(300)
        .distinctUntilChanged()

    private val _sortOrder = MutableStateFlow(CollectionSortOrder.NAME_ASC)
    val sortOrder: StateFlow<CollectionSortOrder> = _sortOrder.asStateFlow()

    private val _uiState = MutableStateFlow<PlaylistDetailUiState>(PlaylistDetailUiState.Loading)
    val uiState: StateFlow<PlaylistDetailUiState> = _uiState.asStateFlow()

    private val _selectedPaths = MutableStateFlow<Set<String>>(emptySet())
    val selectedPaths: StateFlow<Set<String>> = _selectedPaths.asStateFlow()

    val selectionMode: StateFlow<Boolean> = _selectedPaths
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /**
     * UNIFIED MEDIA INDEX (REVISION 8.4.1)
     * Menggabungkan Komik dan Video dalam satu lookup map O(1).
     */
    private val mediaIndex = combine(
        scannerRepository.allComics,
        videoRepository.allVideos
    ) { comics, videos ->
        val index = mutableMapOf<String, Any>()
        comics.forEach { index[it.relativePath] = it }
        videos.forEach { index[it.relativePath] = it }
        index
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    init {
        loadPlaylistDetail()
    }

    private fun loadPlaylistDetail() {
        viewModelScope.launch {
            val playlist = playlistRepository.getPlaylistById(playlistId)
            if (playlist == null) {
                _uiState.value = PlaylistDetailUiState.Error("Playlist not found")
                return@launch
            }

            val pathsInPlaylistFlow = playlistRepository.getComicsInPlaylist(playlistId).distinctUntilChanged()
            val settingsFlow = settingsRepository.settings.distinctUntilChanged()
            val allBookmarksFlow = bookmarkRepository.getAllBookmarkedComics().map { it.toSet() }.distinctUntilChanged()
            val allPlaylistsFlow = playlistRepository.getAllPlaylistComics().map { it.toSet() }.distinctUntilChanged()

            combine(
                pathsInPlaylistFlow,
                mediaIndex,
                settingsFlow,
                debouncedSearchQuery,
                _sortOrder,
                allBookmarksFlow,
                allPlaylistsFlow
            ) { array ->
                @Suppress("UNCHECKED_CAST")
                val pathsInThisPlaylist = array[0] as List<String>
                @Suppress("UNCHECKED_CAST")
                val mediaMap = array[1] as Map<String, Any>
                val settings = array[2] as com.kitsune.app.database.entity.SettingsEntity?
                val query = array[3] as String
                val order = array[4] as CollectionSortOrder
                @Suppress("UNCHECKED_CAST")
                val allBookmarks = array[5] as Set<String>
                @Suppress("UNCHECKED_CAST")
                val allPlaylists = array[6] as Set<String>

                val gridSize = settings?.gridSize ?: 3

                // 1. Unified Mapping (REVISION 8.4.1)
                val items = pathsInThisPlaylist.mapNotNull { path ->
                    val media = mediaMap[path] ?: return@mapNotNull null
                    
                    val hasBookmark = allBookmarks.contains(path)
                    val hasPlaylist = allPlaylists.contains(path)
                    val statuses = when {
                        hasBookmark && hasPlaylist -> ComicStatusSets.BOTH
                        hasBookmark -> ComicStatusSets.BOOKMARKED
                        hasPlaylist -> ComicStatusSets.IN_PLAYLIST
                        else -> ComicStatusSets.EMPTY
                    }

                    when (media) {
                        is Comic -> media.toMediaUiModel(statuses)
                        is Video -> media.toMediaUiModel(statuses)
                        else -> null
                    }
                }
                
                // 2. Filtering
                var result = if (query.isBlank()) {
                    items
                } else {
                    items.filter { it.title.contains(query, ignoreCase = true) }
                }

                // 3. Sorting
                result = when (order) {
                    CollectionSortOrder.NAME_ASC -> result.sortedBy { it.title.lowercase() }
                    CollectionSortOrder.NAME_DESC -> result.sortedByDescending { it.title.lowercase() }
                }

                if (result.isEmpty() && query.isBlank()) {
                    PlaylistDetailUiState.Empty(playlist.name)
                } else {
                    PlaylistDetailUiState.Success(
                        playlistName = playlist.name,
                        items = result,
                        gridSize = gridSize
                    )
                }
            }.catch { e ->
                _uiState.value = PlaylistDetailUiState.Error(e.message ?: "Unknown error")
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
        if (state is PlaylistDetailUiState.Success) {
            _selectedPaths.value = state.items.map { it.id }.toSet()
        }
    }

    fun clearSelection() {
        _selectedPaths.value = emptySet()
    }

    fun removeSelected() {
        val paths = _selectedPaths.value.toList()
        if (paths.isEmpty()) return
        
        viewModelScope.launch {
            playlistRepository.removeComicsFromPlaylist(playlistId, paths)
            clearSelection()
        }
    }

    fun renamePlaylist(newName: String) {
        viewModelScope.launch {
            playlistRepository.renamePlaylist(playlistId, newName)
        }
    }

    fun deletePlaylist() {
        viewModelScope.launch {
            playlistRepository.deletePlaylist(playlistId)
        }
    }
}

sealed class PlaylistDetailUiState {
    data object Loading : PlaylistDetailUiState()
    data class Empty(val playlistName: String) : PlaylistDetailUiState()
    data class Success(
        val playlistName: String,
        val items: List<MediaUiModel>,
        val gridSize: Int
    ) : PlaylistDetailUiState()
    data class Error(val message: String) : PlaylistDetailUiState()
}
