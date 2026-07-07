package com.kitsune.app.ui.bookmark

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kitsune.app.domain.model.Comic
import com.kitsune.app.ui.library.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarkDetailScreen(
    viewModel: BookmarkDetailViewModel,
    onComicClick: (Comic) -> Unit,
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
                            label = "Remove from Bookmark",
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
                            is BookmarkDetailUiState.Success -> state.bookmarkName
                            is BookmarkDetailUiState.Empty -> state.bookmarkName
                            else -> "Bookmark"
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
                        
                        // REVISION 6.7.7: Sort Menu
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
                                    text = { Text("Rename Bookmark") },
                                    onClick = {
                                        showMenu = false
                                        showRenameDialog = true
                                    },
                                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Delete Bookmark") },
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
                        containerColor = Color.Black,
                        titleContentColor = Color.White,
                        navigationIconContentColor = Color.White,
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
                is BookmarkDetailUiState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                is BookmarkDetailUiState.Empty -> {
                     EmptyLibraryState(
                        message = if (searchQuery.isNotEmpty()) "No results for \"$searchQuery\"" else "No comics in this bookmark",
                        icon = if (searchQuery.isNotEmpty()) Icons.Default.SearchOff else Icons.Default.Star
                    )
                }
                is BookmarkDetailUiState.Error -> {
                    EmptyLibraryState(message = state.message, icon = Icons.Default.Error)
                }
                is BookmarkDetailUiState.Success -> {
                    ComicGrid(
                        comics = state.comics,
                        gridSize = state.gridSize,
                        comicStatuses = state.comicStatuses,
                        selectedPaths = selectedPaths,
                        onComicClick = { comic ->
                            if (selectionMode) {
                                viewModel.toggleSelection(comic.relativePath)
                            } else {
                                onComicClick(comic)
                            }
                        },
                        onComicLongClick = { comic ->
                            viewModel.toggleSelection(comic.relativePath)
                        }
                    )
                }
            }
        }
    }

    // Dialogs
    if (showRenameDialog) {
        val currentName = when (val state = uiState) {
            is BookmarkDetailUiState.Success -> state.bookmarkName
            is BookmarkDetailUiState.Empty -> state.bookmarkName
            else -> ""
        }
        var newName by remember(currentName) { mutableStateOf(currentName) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename Bookmark") },
            text = {
                TextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newName.isNotBlank()) {
                        viewModel.renameBookmark(newName)
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
            title = { Text("Delete Bookmark") },
            text = { Text("Are you sure you want to delete this bookmark category? The comics won't be deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteBookmark()
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
            title = { Text("Remove Comics") },
            text = { Text("Remove ${selectedPaths.size} comics from this bookmark?") },
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
