package com.kitsune.app.ui.video

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kitsune.app.domain.model.Video

/**
 * Grid Video Reusable dengan optimasi performa tinggi.
 * Mengikuti pola ComicGrid namun tanpa status badges dan selection mode.
 */
@Composable
fun VideoGrid(
    videos: List<Video>,
    gridSize: Int,
    state: LazyGridState = rememberLazyGridState(),
    onVideoClick: (Video) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(gridSize),
        state = state,
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(
            items = videos,
            key = { it.relativePath },
            contentType = { "video" }
        ) { video ->
            // Stable lambda to prevent redundant card recompositions
            val currentOnVideoClick by rememberUpdatedState(onVideoClick)
            val onClick = remember(video.relativePath) { { currentOnVideoClick(video) } }

            VideoCard(
                video = video,
                onClick = onClick
            )
        }
    }
}
