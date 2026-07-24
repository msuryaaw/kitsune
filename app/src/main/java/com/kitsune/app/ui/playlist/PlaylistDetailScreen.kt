package com.kitsune.app.ui.playlist

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kitsune.app.ui.components.media.*
import com.kitsune.app.ui.library.*

/**
 * Screen detail playlist yang menampilkan daftar media dalam kategori tertentu.
 * REVISION 8.3.7: Theme Compliance - Removed hardcoded colors.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    viewModel: PlaylistDetailViewModel,
    onItemClick: (MediaUiModel) -> Unit,
    onBackClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val sortOrder by viewModel.sortOrder.collectAsState()
    val selectionMode by viewModel.selectionMode.collectAsState()
    val selectedPaths by viewModel.selectedPaths.collectAsState()

    var isSearchActive by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showBulkRemoveConfirm by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }

    BackHandler(enabled = selectionMode || isSearchActive) {
        if (selectionMode) {
            viewModel.clearSelection()
        } else {
            isSearchActive = false
            viewModel.onSearchQueryChange("")
        }
    }

    Scaffold(
        topBar = {
            if (selectionMode) {
                SelectionTopAppBar(
                    selectedCount = selectedPaths.size,
                    onCancel = { viewModel.clearSelection() },
                    onSelectAll = { viewModel.selectAll() },
                    actions = listOf(
                        SelectionAction(
                            icon = Icons.Default.Delete,
                            label = "Remove from Playlist",
                            onClick = { showBulkRemoveConfirm = true }
                        )
                    )
                )
            } else if (isSearchActive) {
                SearchTopAppBar(
                    query = searchQuery,
                    onQueryChange = { viewModel.onSearchQueryChange(it) },
                    onCloseClick = {
                        isSearchActive = false
                        viewModel.onSearchQueryChange("")
                    }
                )
            } else {
                TopAppBar(
                    title = {
                        val title = when (val state = uiState) {
                            is PlaylistDetailUiState.Success -> state.playlistName
                            is PlaylistDetailUiState.Empty -> state.playlistName
                            else -> "Playlist"
                        }
                        Text(title)
                    },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { isSearchActive = true }) {
                            Icon(Icons.Default.Search, contentDescription = "Search")
                        }

                        Box {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(Icons.Default.SortByAlpha, contentDescription = "Sort")
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false }
                            ) {
                                CollectionSortOrder.entries.forEach { order ->
                                    DropdownMenuItem(
                                        text = { Text(order.label) },
                                        onClick = {
                                            viewModel.setSortOrder(order)
                                            showSortMenu = false
                                        },
                                        trailingIcon = {
                                            if (sortOrder == order) {
                                                Icon(Icons.Default.Check, contentDescription = null)
                                            }
                                        }
                                    )
                                }
                            }
                        }

                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                            }
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Rename Playlist") },
                                    onClick = {
                                        showMenu = false
                                        showRenameDialog = true
                                    },
                                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Delete Playlist") },
                                    onClick = {
                                        showMenu = false
                                        showDeleteConfirm = true
                                    },
                                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                    colors = MenuDefaults.itemColors(
                                        textColor = MaterialTheme.colorScheme.error,
                                        leadingIconColor = MaterialTheme.colorScheme.error
                                    )
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                        navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                        actionIconContentColor = MaterialTheme.colorScheme.onBackground
                    )
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (val state = uiState) {
                is PlaylistDetailUiState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                is PlaylistDetailUiState.Empty -> {
                    EmptyLibraryState(
                        message = if (searchQuery.isNotEmpty()) "No results for \"$searchQuery\"" else "No items in this playlist",
                        icon = if (searchQuery.isNotEmpty()) Icons.Default.SearchOff else Icons.AutoMirrored.Filled.List
                    )
                }
                is PlaylistDetailUiState.Error -> {
                    EmptyLibraryState(message = state.message, icon = Icons.Default.Error)
                }
                is PlaylistDetailUiState.Success -> {
                    PlaylistMediaGrid(
                        items = state.items,
                        gridSize = state.gridSize,
                        selectedPaths = selectedPaths,
                        onItemClick = { item ->
                            if (selectionMode) {
                                viewModel.toggleSelection(item.id)
                            } else {
                                onItemClick(item)
                            }
                        },
                        onItemLongClick = { item ->
                            viewModel.toggleSelection(item.id)
                        }
                    )
                }
            }
        }
    }

    // Dialogs
    if (showRenameDialog) {
        val currentName = when (val state = uiState) {
            is PlaylistDetailUiState.Success -> state.playlistName
            is PlaylistDetailUiState.Empty -> state.playlistName
            else -> ""
        }
        var newName by remember(currentName) { mutableStateOf(currentName) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename Playlist") },
            text = {
                TextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newName.isNotBlank()) {
                        viewModel.renamePlaylist(newName)
                        showRenameDialog = false
                    }
                }) {
                    Text("Rename")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Playlist") },
            text = { Text("Are you sure you want to delete this playlist category? The media won't be deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deletePlaylist()
                    showDeleteConfirm = false
                    onBackClick()
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showBulkRemoveConfirm) {
        AlertDialog(
            onDismissRequest = { showBulkRemoveConfirm = false },
            title = { Text("Remove Items") },
            text = { Text("Remove ${selectedPaths.size} items from this playlist?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeSelected()
                    showBulkRemoveConfirm = false
                }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBulkRemoveConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * REVISION 8.4.1: Unified Media Grid for Playlist.
 */
@Composable
fun PlaylistMediaGrid(
    items: List<MediaUiModel>,
    gridSize: Int,
    selectedPaths: Set<String> = emptySet(),
    state: LazyGridState = rememberLazyGridState(),
    onItemClick: (MediaUiModel) -> Unit,
    onItemLongClick: (MediaUiModel) -> Unit = {}
) {
    MediaGrid(
        items = items,
        gridSize = gridSize,
        keySelector = { it.id },
        state = state,
        contentType = { it.mediaType }
    ) { item ->
        val isSelected = remember(selectedPaths, item.id) {
            selectedPaths.contains(item.id)
        }

        MediaCardContainer(
            onClick = { onItemClick(item) },
            onLongClick = { onItemLongClick(item) },
            isSelected = isSelected
        ) {
            Box {
                MediaThumbnail(
                    thumbnailUri = item.thumbnailUri,
                    mediaType = item.mediaType,
                    modifier = if (isSelected) Modifier.border(
                        2.dp,
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.shapes.medium
                    ) else Modifier
                )

                if (!isSelected) {
                    MediaCollectionBadges(
                        statuses = item.statuses,
                        modifier = Modifier.align(Alignment.TopStart)
                    )
                }

                if (isSelected) {
                    Surface(
                        modifier = Modifier.matchParentSize(),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                        shape = MaterialTheme.shapes.medium
                    ) {}
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(8.dp)
                            .size(24.dp)
                            .align(Alignment.TopEnd)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(4.dp))
            
            MediaTitle(
                title = item.title,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}
