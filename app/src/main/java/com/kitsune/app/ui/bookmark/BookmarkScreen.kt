package com.kitsune.app.ui.bookmark

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import kotlinx.coroutines.launch

/**
 * BookmarkScreen dengan optimasi transisi kategori (Phase 6.7.3).
 * Menggunakan Pager pre-rendering dan local page caching untuk menghilangkan micro-stutter.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarkScreen(
    viewModel: BookmarkViewModel,
    onComicClick: (Comic) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val selectedCategoryId by viewModel.selectedCategoryId.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val selectionMode by viewModel.selectionMode.collectAsState()
    val selectedPaths by viewModel.selectedPaths.collectAsState()

    val scope = rememberCoroutineScope()
    var isSearchActive by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showBulkRemoveConfirm by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val pagerState = rememberPagerState(pageCount = { categories.size })
    
    // Maintain scroll states for each category to prevent reset during swipe
    val scrollStates = remember { mutableStateMapOf<Long, LazyGridState>() }

    // SYNC: ViewModel -> Pager
    // Only snap to page when not manually swiping to prevent jank
    LaunchedEffect(selectedCategoryId, categories) {
        if (!pagerState.isScrollInProgress) {
            val index = categories.indexOfFirst { it.id == selectedCategoryId }
            if (index >= 0 && pagerState.currentPage != index) {
                pagerState.scrollToPage(index)
            }
        }
    }

    // SYNC: Pager -> ViewModel
    // Trigger loading earlier by using targetPage instead of settledPage
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.targetPage }.collect { page ->
            if (categories.isNotEmpty() && page < categories.size) {
                val categoryId = categories[page].id
                if (viewModel.selectedCategoryId.value != categoryId) {
                    viewModel.selectCategory(categoryId)
                    viewModel.clearSelection()
                }
            }
        }
    }

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
            Column {
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
                        title = { Text("Bookmarks") },
                        actions = {
                            IconButton(onClick = { isSearchActive = true }) {
                                Icon(Icons.Default.Search, contentDescription = "Search")
                            }
                            if (selectedCategoryId != null) {
                                Box {
                                    IconButton(onClick = { showMenu = true }) {
                                        Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                                    }
                                    DropdownMenu(
                                        expanded = showMenu,
                                        onDismissRequest = { showMenu = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Rename Category") },
                                            onClick = {
                                                showMenu = false
                                                showRenameDialog = true
                                            },
                                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Delete Category") },
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
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Black,
                            titleContentColor = Color.White,
                            actionIconContentColor = Color.White
                        )
                    )
                }

                if (!selectionMode && categories.isNotEmpty()) {
                    ScrollableTabRow(
                        selectedTabIndex = pagerState.currentPage,
                        containerColor = Color.Black,
                        contentColor = MaterialTheme.colorScheme.primary,
                        edgePadding = 16.dp,
                        divider = {}
                    ) {
                        categories.forEachIndexed { index, category ->
                            Tab(
                                selected = pagerState.currentPage == index,
                                onClick = {
                                    scope.launch {
                                        pagerState.animateScrollToPage(index)
                                    }
                                },
                                text = { Text(category.name) }
                            )
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            if (!selectionMode && !isSearchActive) {
                FloatingActionButton(
                    onClick = { showAddDialog = true },
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add Category")
                }
            }
        },
        containerColor = Color.Black
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (categories.isEmpty()) {
                EmptyLibraryState(
                    message = "No categories yet. Click + to create one.",
                    icon = Icons.Default.Star
                )
            } else {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    key = { if (it < categories.size) categories[it].id else it },
                    beyondViewportPageCount = 1 // Pre-render adjacent pages
                ) { page ->
                    val category = categories.getOrNull(page)
                    val categoryId = category?.id ?: -1L
                    
                    // Logic to ensure the page shows its own data or stays in its last success state
                    // This is the key for smooth transitions without changing ViewModel architecture
                    var lastSuccess by remember(categoryId) { mutableStateOf<BookmarkUiState.Success?>(null) }
                    
                    val state = uiState
                    if (state is BookmarkUiState.Success && state.categoryId == categoryId) {
                        lastSuccess = state
                    }

                    val displayState = if (state is BookmarkUiState.Success && state.categoryId == categoryId) {
                        state
                    } else {
                        lastSuccess
                    }

                    // Pre-render content even when not the current active page
                    // This removes the "blank page during swipe" jank
                    if (displayState != null) {
                        val gridState = scrollStates.getOrPut(categoryId) { LazyGridState() }
                        
                        ComicGrid(
                            comics = displayState.comics,
                            gridSize = displayState.gridSize,
                            comicStatuses = displayState.comicStatuses,
                            selectedPaths = selectedPaths,
                            state = gridState,
                            onComicClick = { comic ->
                                if (selectionMode) viewModel.toggleSelection(comic.relativePath)
                                else onComicClick(comic)
                            },
                            onComicLongClick = { comic -> viewModel.toggleSelection(comic.relativePath) }
                        )
                    } else {
                        // Show loading only if we have never loaded this category yet
                        val isLoading = state is BookmarkUiState.Loading || (state is BookmarkUiState.Success && state.categoryId != categoryId)
                        if (isLoading) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        } else if (state is BookmarkUiState.Empty || state is BookmarkUiState.Error) {
                            // Render empty/error states if applicable
                            EmptyLibraryState(
                                message = if (state is BookmarkUiState.Error) state.message else "No comics in this category",
                                icon = if (state is BookmarkUiState.Error) Icons.Default.Error else Icons.Default.Star
                            )
                        }
                    }
                }
            }
        }
    }

    // Dialogs
    if (showAddDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("New Bookmark Category") },
            text = {
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text("Enter name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) {
                        viewModel.createBookmark(name)
                        showAddDialog = false
                    }
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showRenameDialog && selectedCategoryId != null) {
        val currentCategory = categories.find { it.id == selectedCategoryId }
        var newName by remember(currentCategory) { mutableStateOf(currentCategory?.name ?: "") }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename Category") },
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
                        viewModel.renameBookmark(selectedCategoryId!!, newName)
                        showRenameDialog = false
                    }
                }) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showDeleteConfirm && selectedCategoryId != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Category") },
            text = { Text("Are you sure you want to delete this category? Comics will not be deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteBookmark(selectedCategoryId!!)
                    showDeleteConfirm = false
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }

    if (showBulkRemoveConfirm) {
        AlertDialog(
            onDismissRequest = { showBulkRemoveConfirm = false },
            title = { Text("Remove Comics") },
            text = { Text("Remove ${selectedPaths.size} comics from this category?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeSelected()
                    showBulkRemoveConfirm = false
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showBulkRemoveConfirm = false }) { Text("Cancel") }
            }
        )
    }
}
