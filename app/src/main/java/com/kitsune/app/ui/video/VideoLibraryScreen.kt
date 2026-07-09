package com.kitsune.app.ui.video

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kitsune.app.domain.model.Video
import com.kitsune.app.ui.library.EmptyLibraryState
import com.kitsune.app.ui.library.SearchTopAppBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoLibraryScreen(
    viewModel: VideoLibraryViewModel,
    onVideoClick: (Video) -> Unit,
    onBackClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()

    var isSearchActive by remember { mutableStateOf(false) }

    // OPTIMIZATION: Stable handlers
    val onSearchQueryChange = remember(viewModel) { { query: String -> viewModel.onSearchQueryChange(query) } }
    val onCloseSearch = remember(viewModel) {
        {
            isSearchActive = false
            viewModel.onSearchQueryChange("")
        }
    }

    Scaffold(
        topBar = {
            if (isSearchActive) {
                SearchTopAppBar(
                    query = searchQuery,
                    onQueryChange = onSearchQueryChange,
                    onCloseClick = onCloseSearch
                )
            } else {
                TopAppBar(
                    title = { Text("Video Library") },
                    actions = {
                        IconButton(onClick = { isSearchActive = true }) {
                            Icon(Icons.Default.Search, contentDescription = "Search")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Black,
                        titleContentColor = Color.White,
                        actionIconContentColor = Color.White
                    )
                )
            }
        },
        containerColor = Color.Black
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
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
}
