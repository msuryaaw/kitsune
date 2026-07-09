package com.kitsune.app.ui.video

import com.kitsune.app.domain.model.Video

/**
 * Representasi State UI untuk layar Video Library.
 */
sealed class VideoLibraryUiState {
    data object Loading : VideoLibraryUiState()
    data object Empty : VideoLibraryUiState()
    data class Success(
        val videos: List<Video>,
        val isRefreshing: Boolean,
        val gridSize: Int
    ) : VideoLibraryUiState()
}
