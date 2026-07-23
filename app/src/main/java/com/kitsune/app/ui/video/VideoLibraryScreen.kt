package com.kitsune.app.ui.video

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kitsune.app.domain.model.Video
import com.kitsune.app.ui.components.media.MediaLibraryScaffold
import com.kitsune.app.ui.library.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Screen untuk menampilkan daftar video dengan dukungan Selection Mode.
 * REVISION 8.3.3: Implementasi Multi Selection dan Selection Top Bar.
 * REVISION 8.3.6: Added Batch Add to Playlist support.
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
    val allPlaylists by viewModel.allPlaylists.collectAsState()

    var isSearchActive by remember { mutableStateOf(false) }

    // Picker Visibility
    var showPlaylistPicker by remember { mutableStateOf(false) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    
    // Selection States for Dialogs
    var selectedPlaylistIds by remember { mutableStateOf(setOf<Long>()) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.snackbarMessage.collectLatest { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    // Reset picker selections when opening picker
    LaunchedEffect(showPlaylistPicker) {
        if (showPlaylistPicker) selectedPlaylistIds = emptySet()
    }

    // Define selection actions
    val selectionActions = remember {
        listOf(
            SelectionAction(
                icon = Icons.AutoMirrored.Filled.List,
                label = "Add to Playlist",
                onClick = { showPlaylistPicker = true }
            )
        )
    }

    MediaLibraryScaffold(
        title = "Video Library",
        searchQuery = searchQuery,
        onQueryChange = viewModel::onSearchQueryChange,
        isSearchActive = isSearchActive,
        onSearchActiveChange = { isSearchActive = it },
        onBackClick = onBackClick,
        snackbarHostState = snackbarHostState,
        selectionTopBar = if (selectionMode) {
            {
                SelectionTopAppBar(
                    selectedCount = selectedPaths.size,
                    onCancel = { viewModel.clearSelection() },
                    onSelectAll = { viewModel.selectAll() },
                    actions = selectionActions
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

    // Playlist Picker Dialog (Reusable from LibraryComponents)
    if (showPlaylistPicker) {
        val playlistCollections = remember(allPlaylists) { allPlaylists.map { it.id to it.name } }
        CollectionPickerDialog(
            title = "Add to Playlist",
            collections = playlistCollections,
            selectedIds = selectedPlaylistIds,
            onSelectionChanged = { selectedPlaylistIds = it },
            onConfirm = {
                viewModel.addSelectedToPlaylists(selectedPlaylistIds.toList())
                showPlaylistPicker = false
            },
            onDismiss = { showPlaylistPicker = false },
            onCreateNew = { showCreatePlaylistDialog = true }
        )
    }

    // Create New Playlist Dialog
    if (showCreatePlaylistDialog) {
        GenericCreateDialog(
            title = "New Playlist",
            hint = "Playlist name",
            onConfirm = { name ->
                scope.launch {
                    val newId = viewModel.createPlaylist(name)
                    selectedPlaylistIds = selectedPlaylistIds + newId
                    showCreatePlaylistDialog = false
                }
            },
            onDismiss = { showCreatePlaylistDialog = false }
        )
    }
}
