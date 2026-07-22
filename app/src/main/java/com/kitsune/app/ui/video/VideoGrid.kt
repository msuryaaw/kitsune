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
 * REVISION 8.3.3: Added Selection support.
 */
@Composable
fun VideoGrid(
    videos: List<VideoItemState>,
    gridSize: Int,
    selectedPaths: Set<String> = emptySet(),
    state: LazyGridState = rememberLazyGridState(),
    onVideoClick: (Video) -> Unit,
    onVideoLongClick: (Video) -> Unit = {}
) {
    MediaGrid(
        items = videos,
        gridSize = gridSize,
        keySelector = { it.video.relativePath },
        state = state,
        contentType = { "video" }
    ) { videoState ->
        val isSelected = remember(selectedPaths, videoState.video.relativePath) {
            selectedPaths.contains(videoState.video.relativePath)
        }

        // Stable lambdas to prevent redundant card recompositions
        val currentOnVideoClick by rememberUpdatedState(onVideoClick)
        val currentOnVideoLongClick by rememberUpdatedState(onVideoLongClick)
        
        val onClick = remember(videoState.video.relativePath) { 
            { currentOnVideoClick(videoState.video) } 
        }
        val onLongClick = remember(videoState.video.relativePath) {
            { currentOnVideoLongClick(videoState.video) }
        }

        VideoCard(
            state = videoState,
            isSelected = isSelected,
            onClick = onClick,
            onLongClick = onLongClick
        )
    }
}
