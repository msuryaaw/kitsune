package com.kitsune.app.ui.video

import androidx.core.net.toUri
import androidx.lifecycle.viewModelScope
import com.kitsune.app.data.repository.CollectionRepository
import com.kitsune.app.data.repository.PlaylistRepository
import com.kitsune.app.data.repository.SettingsRepository
import com.kitsune.app.data.repository.VideoRepository
import com.kitsune.app.database.entity.PlaylistEntity
import com.kitsune.app.domain.model.MediaType
import com.kitsune.app.ui.library.ComicStatus
import com.kitsune.app.ui.library.base.BaseLibraryViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel for managing data on the Video Library screen.
 * REVISION 10.5.3: Optimized Flow pipeline to reduce redundant mappings and emissions.
 */
class VideoLibraryViewModel(
    private val videoRepository: VideoRepository,
    private val settingsRepository: SettingsRepository,
    private val collectionRepository: CollectionRepository,
    private val playlistRepository: PlaylistRepository
) : BaseLibraryViewModel() {

    /**
     * Observable list of available playlists.
     */
    val allPlaylists: StateFlow<List<PlaylistEntity>> = playlistRepository.getAllPlaylistsWithCount()
        .map { list -> list.map { it.playlist } }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * OPTIMIZATION: Divided the flow pipeline into smaller chunks to improve stability.
     * videoItemsFlow combines core video data and collection status.
     */
    private val videoItemsFlow: Flow<List<VideoItemState>> = combine(
        videoRepository.allVideos,
        videoRepository.getAllProgressMap(),
        collectionRepository.getBookmarkedPaths(MediaType.VIDEO),
        collectionRepository.getPlaylistPaths(MediaType.VIDEO)
    ) { videos, progressMap, bookmarkedPaths, playlistPaths ->
        
        // Group progress once per emission
        val groupedProgress = progressMap.values.groupBy { it.videoRelativePath }
        
        videos.map { video ->
            val path = video.relativePath
            val latestProgress = groupedProgress[path]?.maxByOrNull { it.lastWatchedAt }

            val percentage = if (latestProgress != null && latestProgress.durationMs > 0) {
                latestProgress.lastPositionMs.toFloat() / latestProgress.durationMs.toFloat()
            } else 0f
            
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
    }.distinctUntilChanged()

    /**
     * Final UI State assembly with Filtering, Sorting, and Settings integration.
     */
    val uiState: StateFlow<VideoLibraryUiState> = combine(
        videoItemsFlow,
        debouncedSearchQuery,
        settingsRepository.settings.map { it?.gridSize ?: 3 }.distinctUntilChanged(),
        _isRefreshing
    ) { videoItems, query, gridSize, refreshing ->

        // Filtering & Sorting (performed only when necessary due to distinctUntilChanged upstream)
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

    override fun refreshLibrary() {
        if (_isRefreshing.value) return

        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                // REVISION 10.5.4: Use cached settings for one-shot retrieval
                val settings = settingsRepository.getSettingsCached()
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

    fun selectAll() {
        val state = uiState.value
        if (state is VideoLibraryUiState.Success) {
            _selectedPaths.value = state.videos.map { it.video.relativePath }.toSet()
        }
    }

    fun addSelectedToPlaylists(playlistIds: List<Long>) {
        val paths = _selectedPaths.value.toList()
        if (paths.isEmpty() || playlistIds.isEmpty()) return
        
        viewModelScope.launch {
            playlistRepository.addVideosToPlaylists(playlistIds, paths)
            _snackbarMessage.emit("Added ${paths.size} videos to ${playlistIds.size} playlists.")
            clearSelection()
        }
    }

    suspend fun createPlaylist(name: String): Long {
        return playlistRepository.createPlaylist(name)
    }
}
