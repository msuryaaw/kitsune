package com.kitsune.app.ui.video

import androidx.core.net.toUri
import androidx.lifecycle.viewModelScope
import com.kitsune.app.data.repository.CollectionRepository
import com.kitsune.app.data.repository.SettingsRepository
import com.kitsune.app.data.repository.VideoRepository
import com.kitsune.app.domain.model.MediaType
import com.kitsune.app.ui.library.ComicStatus
import com.kitsune.app.ui.library.base.BaseLibraryViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel untuk mengelola data pada layar Video Library.
 * REVISION 7.8.7: Migrasi ke BaseLibraryViewModel untuk standarisasi logika pencarian dan penyegaran.
 * REVISION 7.8.11: Integrasi CollectionRepository untuk indikator Bookmark dan Playlist.
 * REVISION 8.3.3: Integrasi Selection Mode dari BaseLibraryViewModel.
 */
class VideoLibraryViewModel(
    private val videoRepository: VideoRepository,
    private val settingsRepository: SettingsRepository,
    private val collectionRepository: CollectionRepository
) : BaseLibraryViewModel() {

    /**
     * Menggabungkan data inti library (Video, Progress, Collections).
     * Dibagi menjadi dua tahap untuk menangani limitasi combine() overload (> 5 flows).
     */
    private val videoItemsFlow: Flow<List<VideoItemState>> = combine(
        videoRepository.allVideos,
        videoRepository.getAllProgressMap(),
        collectionRepository.getBookmarkedPaths(MediaType.VIDEO),
        collectionRepository.getPlaylistPaths(MediaType.VIDEO)
    ) { videos, progressMap, bookmarkedPaths, playlistPaths ->
        
        // OPTIMIZATION: Group progress by video path once per emission to avoid O(N) filter in loop
        val groupedProgress = progressMap.values.groupBy { it.videoRelativePath }
        
        videos.map { video ->
            val path = video.relativePath
            
            val latestProgress = groupedProgress[path]?.maxByOrNull { it.lastWatchedAt }

            val percentage = if (latestProgress != null && latestProgress.durationMs > 0) {
                latestProgress.lastPositionMs.toFloat() / latestProgress.durationMs.toFloat()
            } else 0f
            
            // Build Statuses
            val statusSet = mutableSetOf<ComicStatus>()
            if (bookmarkedPaths.contains(path)) statusSet.add(ComicStatus.BOOKMARKED)
            if (playlistPaths.contains(path)) statusSet.add(ComicStatus.IN_PLAYLIST)

            VideoItemState(
                video = video,
                watchedPercentage = percentage,
                isFinished = percentage >= 0.95f,
                lastEpisodePath = latestProgress?.episodeRelativePath,
                statuses = statusSet
            )
        }
    }

    /**
     * Perakitan final UI State dengan Filtering, Sorting, dan Settings.
     */
    val uiState: StateFlow<VideoLibraryUiState> = combine(
        videoItemsFlow,
        debouncedSearchQuery,
        settingsRepository.settings.map { it?.gridSize ?: 3 }.distinctUntilChanged(),
        _isRefreshing
    ) { videoItems, query, gridSize, refreshing ->

        // Filtering & Sorting
        val filteredItems = if (query.isBlank()) {
            videoItems
        } else {
            videoItems.filter { it.video.title.contains(query, ignoreCase = true) }
        }
        val sortedItems = filteredItems.sortedBy { it.video.title.lowercase() }

        when {
            refreshing && sortedItems.isEmpty() && query.isBlank() -> VideoLibraryUiState.Loading
            sortedItems.isEmpty() -> VideoLibraryUiState.Empty
            else -> VideoLibraryUiState.Success(
                videos = sortedItems,
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

    /**
     * Implementasi refresh library untuk video.
     */
    override fun refreshLibrary() {
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
                e.printStackTrace()
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    // --- Selection Methods (Specific implementation) ---

    fun selectAll() {
        val state = uiState.value
        if (state is VideoLibraryUiState.Success) {
            _selectedPaths.value = state.videos.map { it.video.relativePath }.toSet()
        }
    }
}
