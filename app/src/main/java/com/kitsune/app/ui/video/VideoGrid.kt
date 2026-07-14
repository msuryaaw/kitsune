package com.kitsune.app.ui.video

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.kitsune.app.domain.model.Video
import com.kitsune.app.ui.components.media.MediaGrid

/**
 * Grid Video Reusable dengan optimasi performa tinggi.
 * REVISION 7.8.5: Migrated to generic MediaGrid.
 */
@Composable
fun VideoGrid(
    videos: List<VideoItemState>,
    gridSize: Int,
    state: LazyGridState = rememberLazyGridState(),
    onVideoClick: (Video) -> Unit
) {
    MediaGrid(
        items = videos,
        gridSize = gridSize,
        keySelector = { it.video.relativePath },
        state = state,
        contentType = { "video" }
    ) { videoState ->
        // Stable lambda to prevent redundant card recompositions
        val currentOnVideoClick by rememberUpdatedState(onVideoClick)
        val onClick = remember(videoState.video.relativePath) { 
            { currentOnVideoClick(videoState.video) } 
        }

        VideoCard(
            state = videoState,
            onClick = onClick
        )
    }
}
