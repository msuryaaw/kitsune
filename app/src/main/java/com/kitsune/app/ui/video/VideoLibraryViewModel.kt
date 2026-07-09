package com.kitsune.app.ui.video

import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kitsune.app.data.repository.SettingsRepository
import com.kitsune.app.data.repository.VideoRepository
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel untuk mengelola data pada layar Video Library.
 * Mengikuti pola arsitektur yang konsisten dengan Comic Library.
 */
class VideoLibraryViewModel(
    private val videoRepository: VideoRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _isRefreshing = MutableStateFlow(false)
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    /**
     * Optimasi pencarian di memori untuk mencegah recomputation berlebih.
     */
    @OptIn(FlowPreview::class)
    private val debouncedSearchQuery = _searchQuery
        .debounce(300)
        .distinctUntilChanged()

    /**
     * Alur pengolahan data UI State:
     * 1. Ambil data video dari Repository.
     * 2. Gabungkan dengan query pencarian (debounce).
     * 3. Lakukan filtering dan sorting di memori.
     * 4. Gabungkan dengan pengaturan grid size.
     */
    val uiState: StateFlow<VideoLibraryUiState> = combine(
        videoRepository.allVideos,
        debouncedSearchQuery,
        settingsRepository.settings.map { it?.gridSize ?: 3 }.distinctUntilChanged(),
        _isRefreshing
    ) { videos, query, gridSize, refreshing ->
        
        // 1. Filtering di memori
        val filteredVideos = if (query.isBlank()) {
            videos
        } else {
            videos.filter { it.title.contains(query, ignoreCase = true) }
        }

        // 2. Sorting di memori (Alfabet)
        val sortedVideos = filteredVideos.sortedBy { it.title.lowercase() }

        // 3. Penentuan State
        when {
            refreshing && sortedVideos.isEmpty() && query.isBlank() -> VideoLibraryUiState.Loading
            sortedVideos.isEmpty() -> VideoLibraryUiState.Empty
            else -> VideoLibraryUiState.Success(
                videos = sortedVideos,
                isRefreshing = refreshing,
                gridSize = gridSize
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = VideoLibraryUiState.Loading
    )

    init {
        refreshLibrary()
    }

    fun onSearchQueryChange(newQuery: String) {
        _searchQuery.value = newQuery
    }

    /**
     * Memicu pemindaian ulang library melalui VideoRepository.
     */
    fun refreshLibrary() {
        if (_isRefreshing.value) return

        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                val settings = settingsRepository.settings.first()
                val rootUriString = settings?.rootFolderUri
                
                if (!rootUriString.isNullOrEmpty()) {
                    videoRepository.refreshLibrary(rootUriString.toUri())
                }
            } catch (e: Exception) {
                // Error handling minimal untuk fondasi ViewModel
            } finally {
                _isRefreshing.value = false
            }
        }
    }
}
