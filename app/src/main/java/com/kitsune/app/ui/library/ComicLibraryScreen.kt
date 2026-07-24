package com.kitsune.app.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kitsune.app.domain.model.Comic
import com.kitsune.app.ui.components.media.MediaLibraryScaffold
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@Composable
fun ComicLibraryScreen(
    viewModel: LibraryViewModel,
    onComicClick: (Comic) -> Unit,
    onBackClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val selectionMode by viewModel.selectionMode.collectAsState()
    val selectedPaths by viewModel.selectedPaths.collectAsState()
    
    val allBookmarks by viewModel.allBookmarks.collectAsState()

    var isSearchActive by remember { mutableStateOf(false) }
    
    // Picker Visibility
    var showBookmarkPicker by remember { mutableStateOf(false) }
    
    // Create Category Visibility
    var showCreateBookmarkDialog by remember { mutableStateOf(false) }

    // Selection States for Dialogs
    var selectedBookmarkIds by remember { mutableStateOf(setOf<Long>()) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.snackbarMessage.collectLatest { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    // Reset picker selections when opening pickers
    LaunchedEffect(showBookmarkPicker) {
        if (showBookmarkPicker) selectedBookmarkIds = emptySet()
    }

    // OPTIMIZATION: Remember selection actions
    val selectionActions = remember {
        listOf(
            SelectionAction(
                icon = Icons.Default.BookmarkAdd,
                label = "Add to Bookmark",
                onClick = { showBookmarkPicker = true }
            )
        )
    }

    MediaLibraryScaffold(
        title = "Comic Library",
        searchQuery = searchQuery,
        onQueryChange = viewModel::onSearchQueryChange,
        isSearchActive = isSearchActive,
        onSearchActiveChange = { isSearchActive = it },
        onBackClick = null, // Visual Identical: Comic Library originally had no back button
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
            is LibraryUiState.Loading -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
            is LibraryUiState.Empty -> {
                EmptyLibraryState(
                    message = if (searchQuery.isNotEmpty()) "No results for \"$searchQuery\"" else "No Comics Found",
                    icon = Icons.Default.SearchOff
                )
            }
            is LibraryUiState.Error -> {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(text = state.message, color = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { viewModel.refreshLibrary() }) {
                        Text("Retry")
                    }
                }
            }
            is LibraryUiState.Success -> {
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

    // Generic Collection Pickers
    if (showBookmarkPicker) {
        val bookmarkCollections = remember(allBookmarks) { allBookmarks.map { it.id to it.name } }
        CollectionPickerDialog(
            title = "Add to Bookmark",
            collections = bookmarkCollections,
            selectedIds = selectedBookmarkIds,
            onSelectionChanged = { selectedBookmarkIds = it },
            onConfirm = {
                viewModel.addSelectedToBookmarks(selectedBookmarkIds.toList())
                showBookmarkPicker = false
            },
            onDismiss = { showBookmarkPicker = false },
            onCreateNew = { showCreateBookmarkDialog = true }
        )
    }

    // Reusable Create Dialogs
    if (showCreateBookmarkDialog) {
        GenericCreateDialog(
            title = "New Bookmark Category",
            hint = "Category name",
            onConfirm = { name ->
                scope.launch {
                    val newId = viewModel.createBookmark(name)
                    selectedBookmarkIds = selectedBookmarkIds + newId
                    showCreateBookmarkDialog = false
                }
            },
            onDismiss = { showCreateBookmarkDialog = false }
        )
    }
}
