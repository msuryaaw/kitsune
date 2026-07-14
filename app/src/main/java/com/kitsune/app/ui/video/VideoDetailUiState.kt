package com.kitsune.app.ui.video

import androidx.compose.runtime.Immutable
import com.kitsune.app.domain.model.Episode
import com.kitsune.app.domain.model.Video

/**
 * Representasi State UI untuk sebuah item episode di list.
 * REVISION 7.7.3.4: Menyertakan data progres playback.
 */
@Immutable
data class EpisodeItemState(
    val episode: Episode,
    val watchedPercentage: Float = 0f,
    val isFinished: Boolean = false,
    val lastPositionMs: Long = 0L,
    val durationMs: Long = 0L
)

/**
 * Representasi State UI untuk layar Detail Video.
 */
sealed class VideoDetailUiState {
    data object Loading : VideoDetailUiState()
    data class Success(
        val video: Video,
        val episodes: List<EpisodeItemState>
    ) : VideoDetailUiState()
    data class Error(val message: String) : VideoDetailUiState()
}
