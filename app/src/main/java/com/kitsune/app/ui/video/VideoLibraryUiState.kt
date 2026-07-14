package com.kitsune.app.ui.video

import androidx.compose.runtime.Immutable
import com.kitsune.app.domain.model.Video
import com.kitsune.app.ui.library.ComicStatus

/**
 * Representasi State UI untuk sebuah item video di grid.
 * REVISION 7.7.3.3: Menyertakan data progres untuk indikator visual.
 * REVISION 7.8.11: Menambahkan statuses untuk indikator Bookmark dan Playlist.
 */
@Immutable
data class VideoItemState(
    val video: Video,
    val watchedPercentage: Float = 0f,
    val isFinished: Boolean = false,
    val lastEpisodePath: String? = null,
    val statuses: Set<ComicStatus> = emptySet()
)

/**
 * Representasi State UI untuk layar Video Library.
 */
sealed class VideoLibraryUiState {
    data object Loading : VideoLibraryUiState()
    data object Empty : VideoLibraryUiState()
    data class Success(
        val videos: List<VideoItemState>,
        val isRefreshing: Boolean,
        val gridSize: Int
    ) : VideoLibraryUiState()
}
