package com.kitsune.app.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.SortByAlpha
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
    val sortOrder by viewModel.sortOrder.collectAsState()
    val selectionMode by viewModel.selectionMode.collectAsState()
    val selectedPaths by viewModel.selectedPaths.collectAsState()
    
    val allBookmarks by viewModel.allBookmarks.collectAsState()

    var isSearchActive by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    
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

    val selectedStatus by viewModel.selectedStatusFilter.collectAsState()
    val selectedType by viewModel.selectedTypeFilter.collectAsState()
    val selectedTag by viewModel.selectedTagFilter.collectAsState()
    val popularTags by viewModel.popularTags.collectAsState()

    MediaLibraryScaffold(
        title = "Comic Library",
        searchQuery = searchQuery,
        onQueryChange = viewModel::onSearchQueryChange,
        isSearchActive = isSearchActive,
        onSearchActiveChange = { isSearchActive = it },
        onBackClick = null, // Visual Identical: Comic Library originally had no back button
        snackbarHostState = snackbarHostState,
        topBarActions = {
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
                    ComicSortOrder.entries.forEach { order ->
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
        },
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
        Column(modifier = Modifier.fillMaxSize()) {
            FilterChipsRow(
                selectedStatus = selectedStatus,
                selectedType = selectedType,
                selectedTag = selectedTag,
                popularTags = popularTags,
                onStatusSelected = viewModel::setStatusFilter,
                onTypeSelected = viewModel::setTypeFilter,
                onTagSelected = viewModel::setTagFilter
            )

            Box(modifier = Modifier.weight(1f)) {
                when (val state = uiState) {
                    is LibraryUiState.Loading -> {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }
                    is LibraryUiState.Empty -> {
                        val isFiltered = searchQuery.isNotEmpty() || selectedStatus != ComicStatusFilter.ALL || selectedType != null || selectedTag != null
                        EmptyLibraryState(
                            message = if (isFiltered) "No results matching selected filters" else "No Comics Found",
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

/**
 * Interactive Filter Chips Row for Comic Library (TASK-03).
 */
@Composable
fun FilterChipsRow(
    selectedStatus: ComicStatusFilter,
    selectedType: String?,
    selectedTag: String?,
    popularTags: List<String>,
    onStatusSelected: (ComicStatusFilter) -> Unit,
    onTypeSelected: (String?) -> Unit,
    onTagSelected: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Status Filter Chips
        items(ComicStatusFilter.entries.toTypedArray()) { status ->
            val isSelected = selectedStatus == status
            FilterChip(
                selected = isSelected,
                onClick = { onStatusSelected(status) },
                label = { Text(status.label, style = MaterialTheme.typography.labelSmall) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                border = null,
                modifier = Modifier.height(32.dp)
            )
        }

        // Divider
        item {
            VerticalDivider(
                modifier = Modifier
                    .height(20.dp)
                    .padding(horizontal = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }

        // Type Filter Chips
        val types = listOf("Manga", "Manhwa", "Manhua")
        items(types.toTypedArray()) { type ->
            val isSelected = selectedType?.equals(type, ignoreCase = true) == true
            FilterChip(
                selected = isSelected,
                onClick = { onTypeSelected(type) },
                label = { Text(type, style = MaterialTheme.typography.labelSmall) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer
                ),
                border = null,
                modifier = Modifier.height(32.dp)
            )
        }

        // Popular Tags Chips (If available)
        if (popularTags.isNotEmpty()) {
            item {
                VerticalDivider(
                    modifier = Modifier
                        .height(20.dp)
                        .padding(horizontal = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }

            items(popularTags.toTypedArray()) { tag ->
                val isSelected = selectedTag?.equals(tag, ignoreCase = true) == true
                FilterChip(
                    selected = isSelected,
                    onClick = { onTagSelected(tag) },
                    label = { Text("#$tag", style = MaterialTheme.typography.labelSmall) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onTertiaryContainer
                    ),
                    border = null,
                    modifier = Modifier.height(32.dp)
                )
            }
        }
    }
}
