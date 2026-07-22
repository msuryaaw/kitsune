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
import com.kitsune.app.ui.library.SelectionTopAppBar

/**
 * Screen untuk menampilkan daftar video dengan dukungan Selection Mode.
 * REVISION 8.3.3: Implementasi Multi Selection dan Selection Top Bar.
 */
@Composable
fun VideoLibraryScreen(
    viewModel: VideoLibraryViewModel,
    onVideoClick: (Video) -> Unit,
    onBackClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val selectionMode by viewModel.selectionMode.collectAsState()
    val selectedPaths by viewModel.selectedPaths.collectAsState()

    var isSearchActive by remember { mutableStateOf(false) }

    MediaLibraryScaffold(
        title = "Video Library",
        searchQuery = searchQuery,
        onQueryChange = viewModel::onSearchQueryChange,
        isSearchActive = isSearchActive,
        onSearchActiveChange = { isSearchActive = it },
        onBackClick = onBackClick,
        selectionTopBar = if (selectionMode) {
            {
                SelectionTopAppBar(
                    selectedCount = selectedPaths.size,
                    onCancel = { viewModel.clearSelection() },
                    onSelectAll = { viewModel.selectAll() },
                    actions = emptyList() // Batch actions akan ditambahkan pada phase berikutnya
                )
            }
        } else null
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
                    selectedPaths = selectedPaths,
                    onVideoClick = { video ->
                        if (selectionMode) {
                            viewModel.toggleSelection(video.relativePath)
                        } else {
                            onVideoClick(video)
                        }
                    },
                    onVideoLongClick = { video ->
                        viewModel.toggleSelection(video.relativePath)
                    }
                )
            }
        }
    }
}
