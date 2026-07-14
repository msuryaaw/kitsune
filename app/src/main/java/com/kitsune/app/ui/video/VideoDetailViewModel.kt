package com.kitsune.app.ui.video

import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kitsune.app.data.repository.CollectionRepository
import com.kitsune.app.data.repository.PlaylistWithCount
import com.kitsune.app.data.repository.SettingsRepository
import com.kitsune.app.data.repository.VideoRepository
import com.kitsune.app.domain.model.Episode
import com.kitsune.app.domain.model.Video
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel untuk mengelola data detail sebuah video.
 * REVISION 7.7.3.4: Integrasi indikator progres playback pada setiap episode.
 * REVISION 7.8.10: Integrasi CollectionRepository untuk Bookmark dan Playlist.
 */
class VideoDetailViewModel(
    private val videoRelativePath: String,
    private val videoRepository: VideoRepository,
    private val settingsRepository: SettingsRepository,
    private val collectionRepository: CollectionRepository
) : ViewModel() {

    private val _episodes = MutableStateFlow<List<Episode>>(emptyList())

    private val _availableBookmarks = MutableStateFlow<List<VideoBookmarkWithMembership>>(emptyList())
    val availableBookmarks: StateFlow<List<VideoBookmarkWithMembership>> = _availableBookmarks.asStateFlow()

    private val _availablePlaylists = MutableStateFlow<List<PlaylistWithCount>>(emptyList())
    val availablePlaylists: StateFlow<List<PlaylistWithCount>> = _availablePlaylists.asStateFlow()

    val isBookmarked: StateFlow<Boolean> = collectionRepository.getBookmarkIdsForMedia(videoRelativePath)
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /**
     * Alur pengolahan data UI State:
     * 1. Gabungkan metadata video (reaktif) dengan daftar episode dan map progres.
     * 2. Transformasi menjadi EpisodeItemState.
     */
    val uiState: StateFlow<VideoDetailUiState> = combine(
        videoRepository.getVideoFlow(videoRelativePath),
        _episodes,
        videoRepository.getAllProgressMap()
    ) { video, episodes, progressMap ->
        
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
                episodes = episodeStates
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
                val settings = settingsRepository.settings.first()
                val rootUriString = settings?.rootFolderUri ?: return@launch
                val episodes = videoRepository.getEpisodes(rootUriString.toUri(), videoRelativePath)
                _episodes.value = episodes
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun loadCollectionData() {
        // Observe bookmarks and membership status
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

        // Observe playlists
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

/**
 * Model data untuk menampilkan status keanggotaan bookmark video dalam dialog.
 */
data class VideoBookmarkWithMembership(
    val bookmark: com.kitsune.app.data.repository.BookmarkWithCount,
    val isMember: Boolean
)
