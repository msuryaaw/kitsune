package com.kitsune.app.ui.video

import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kitsune.app.data.metadata.MediaMetadata
import com.kitsune.app.data.metadata.MetadataManager
import com.kitsune.app.data.repository.CollectionRepository
import com.kitsune.app.data.repository.PlaylistWithCount
import com.kitsune.app.data.repository.SettingsRepository
import com.kitsune.app.data.repository.VideoRepository
import com.kitsune.app.data.repository.ScannerRepository
import com.kitsune.app.data.repository.BookmarkWithCount
import com.kitsune.app.domain.model.Episode
import com.kitsune.app.domain.model.Video
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel for managing data of a video detail screen.
 * REVISION 10.5.9: Optimized settings retrieval and flow stability.
 * REVISION 11.1.11: Integrated Search Tag Index synchronization after metadata write.
 */
class VideoDetailViewModel(
    private val videoRelativePath: String,
    private val videoRepository: VideoRepository,
    private val scannerRepository: ScannerRepository,
    private val settingsRepository: SettingsRepository,
    private val collectionRepository: CollectionRepository,
    private val metadataManager: MetadataManager
) : ViewModel() {

    private val _episodes = MutableStateFlow<List<Episode>>(emptyList())
    private val _metadata = MutableStateFlow(MediaMetadata())

    private val _availableBookmarks = MutableStateFlow<List<VideoBookmarkWithMembership>>(emptyList())
    val availableBookmarks: StateFlow<List<VideoBookmarkWithMembership>> = _availableBookmarks.asStateFlow()

    private val _availablePlaylists = MutableStateFlow<List<PlaylistWithCount>>(emptyList())
    val availablePlaylists: StateFlow<List<PlaylistWithCount>> = _availablePlaylists.asStateFlow()

    private val _isEditMode = MutableStateFlow(false)
    val isEditMode: StateFlow<Boolean> = _isEditMode.asStateFlow()

    private val _snackbarMessage = MutableSharedFlow<String>()
    val snackbarMessage = _snackbarMessage.asSharedFlow()

    val isBookmarked: StateFlow<Boolean> = collectionRepository.getBookmarkIdsForMedia(videoRelativePath)
        .map { it.isNotEmpty() }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /**
     * UI State combining multiple sources.
     */
    val uiState: StateFlow<VideoDetailUiState> = combine(
        videoRepository.getVideoFlow(videoRelativePath),
        _episodes,
        videoRepository.getAllProgressMap(),
        _metadata
    ) { video, episodes, progressMap, meta ->
        
        if (video == null) {
            VideoDetailUiState.Error("Video not found")
        } else {
            val episodeStates = episodes.map { episode ->
                val progress = progressMap[episode.relativePath]
                val percentage = if (progress != null && progress.durationMs > 0) {
                    progress.lastPositionMs.toFloat() / progress.durationMs.toFloat()
                } else 0f

                EpisodeItemState(
                    episode = episode,
                    watchedPercentage = percentage,
                    isFinished = percentage >= 0.95f,
                    lastPositionMs = progress?.lastPositionMs ?: 0L,
                    durationMs = progress?.durationMs ?: 0L
                )
            }

            VideoDetailUiState.Success(
                video = video,
                episodes = episodeStates,
                metadata = meta
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = VideoDetailUiState.Loading
    )

    init {
        loadInitialData()
        loadCollectionData()
    }

    private fun loadInitialData() {
        viewModelScope.launch {
            try {
                // REVISION 10.5.9: Use cached settings for faster navigation
                val settings = settingsRepository.getSettingsCached()
                val rootUriString = settings?.rootFolderUri ?: return@launch
                val rootUri = rootUriString.toUri()
                
                _episodes.value = videoRepository.getEpisodes(rootUri, videoRelativePath)

                launch {
                    _metadata.value = metadataManager.readMetadata(rootUri, videoRelativePath)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun addTag(tagName: String) {
        viewModelScope.launch {
            val settings = settingsRepository.getSettingsCached()
            val rootUri = settings?.rootFolderUri?.toUri() ?: return@launch

            val updatedMetadata = _metadata.value.copy(
                tags = _metadata.value.tags + tagName
            )

            val result = metadataManager.writeMetadata(rootUri, videoRelativePath, updatedMetadata)
            if (result.isSuccess) {
                // SINKRONISASI SEARCH INDEX (REVISION 11.1.11)
                scannerRepository.updateVideoSearchTags(videoRelativePath, updatedMetadata.tags)
                _metadata.value = updatedMetadata
            } else {
                _snackbarMessage.emit("Failed to save tag")
            }
        }
    }

    fun removeTag(tagName: String) {
        viewModelScope.launch {
            val settings = settingsRepository.getSettingsCached()
            val rootUri = settings?.rootFolderUri?.toUri() ?: return@launch

            val updatedMetadata = _metadata.value.copy(
                tags = _metadata.value.tags.filter { it != tagName }
            )

            val result = metadataManager.writeMetadata(rootUri, videoRelativePath, updatedMetadata)
            if (result.isSuccess) {
                // SINKRONISASI SEARCH INDEX (REVISION 11.1.11)
                scannerRepository.updateVideoSearchTags(videoRelativePath, updatedMetadata.tags)
                _metadata.value = updatedMetadata
            } else {
                _snackbarMessage.emit("Failed to remove tag")
            }
        }
    }

    fun toggleEditMode() {
        _isEditMode.value = !_isEditMode.value
    }

    private fun loadCollectionData() {
        combine(
            collectionRepository.observeBookmarks(),
            collectionRepository.getBookmarkIdsForMedia(videoRelativePath)
        ) { allBookmarks, memberIds ->
            allBookmarks.map { bookmarkWithCount ->
                VideoBookmarkWithMembership(
                    bookmark = bookmarkWithCount,
                    isMember = memberIds.contains(bookmarkWithCount.bookmark.id)
                )
            }
        }.onEach { 
            _availableBookmarks.value = it 
        }.launchIn(viewModelScope)

        collectionRepository.observePlaylists().onEach { playlists ->
            _availablePlaylists.value = playlists
        }.launchIn(viewModelScope)
    }

    fun toggleBookmarkMembership(bookmarkId: Long, isMember: Boolean) {
        viewModelScope.launch {
            if (isMember) {
                collectionRepository.removeBookmark(videoRelativePath, bookmarkId)
            } else {
                collectionRepository.addBookmark(videoRelativePath, bookmarkId)
            }
        }
    }

    fun addVideoToPlaylist(playlistId: Long) {
        viewModelScope.launch {
            collectionRepository.addPlaylist(videoRelativePath, playlistId)
        }
    }
}

data class VideoBookmarkWithMembership(
    val bookmark: BookmarkWithCount,
    val isMember: Boolean
)
