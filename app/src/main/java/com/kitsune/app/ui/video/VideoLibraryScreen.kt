package com.kitsune.app.ui.video

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kitsune.app.domain.model.Video
import com.kitsune.app.ui.components.media.MediaLibraryScaffold
import com.kitsune.app.ui.library.EmptyLibraryState

@Composable
fun VideoLibraryScreen(
    viewModel: VideoLibraryViewModel,
    onVideoClick: (Video) -> Unit,
    onBackClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()

    var isSearchActive by remember { mutableStateOf(false) }

    MediaLibraryScaffold(
        title = "Video Library",
        searchQuery = searchQuery,
        onQueryChange = viewModel::onSearchQueryChange,
        isSearchActive = isSearchActive,
        onSearchActiveChange = { isSearchActive = it },
        onBackClick = onBackClick
    ) {
        when (val state = uiState) {
            is VideoLibraryUiState.Loading -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
            is VideoLibraryUiState.Empty -> {
                EmptyLibraryState(
                    message = if (searchQuery.isNotEmpty()) "No results for \"$searchQuery\"" else "No Videos Found",
                    icon = Icons.Default.SearchOff
                )
            }
            is VideoLibraryUiState.Success -> {
                VideoGrid(
                    videos = state.videos,
                    gridSize = state.gridSize,
                    onVideoClick = onVideoClick
                )
            }
        }
    }
}
