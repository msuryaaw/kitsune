package com.example.kitsune.ui.screens.bookmark

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.example.kitsune.data.CoverCacheResult
import com.example.kitsune.data.entity.ComicEntity
import com.example.kitsune.ui.components.KitsuneEmptyState
import com.example.kitsune.ui.components.KitsunePrimaryButton
import com.example.kitsune.ui.components.KitsuneSearchField
import com.example.kitsune.ui.components.KitsuneSectionHeading
import com.example.kitsune.ui.screens.library.CoverUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun BookmarkScreen(
    viewModel: BookmarkViewModel,
    onComicClick: (Long) -> Unit,
    onNavigateToSettings: () -> Unit,
    onOpenLibraryClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showCreateDialog by remember { mutableStateOf(false) }

    if (showCreateDialog) {
        CreateCollectionDialog(
            onConfirm = { name ->
                viewModel.createCollection(name)
                showCreateDialog = false
            },
            onDismiss = { showCreateDialog = false }
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        when (val state = uiState) {
            is BookmarkUiState.Loading -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
            is BookmarkUiState.RootNotConfigured -> {
                BookmarkRootNotConfiguredView(
                    onNavigateToSettings = onNavigateToSettings,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            is BookmarkUiState.PermissionDenied -> {
                BookmarkPermissionDeniedView(
                    message = state.message,
                    onNavigateToSettings = onNavigateToSettings,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            is BookmarkUiState.EmptyNoCollections -> {
                BookmarkEmptyNoCollectionsView(
                    onCreateCollectionClick = { showCreateDialog = true },
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            is BookmarkUiState.Success -> {
                BookmarkSuccessView(
                    state = state,
                    viewModel = viewModel,
                    onComicClick = onComicClick,
                    onCreateCollectionClick = { showCreateDialog = true },
                    onOpenLibraryClick = onOpenLibraryClick,
                    onGridColumnsChange = { columns -> viewModel.setGridColumns(columns) }
                )
            }
            is BookmarkUiState.Error -> {
                BookmarkErrorView(
                    message = state.message,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookmarkSuccessView(
    state: BookmarkUiState.Success,
    viewModel: BookmarkViewModel,
    onComicClick: (Long) -> Unit,
    onCreateCollectionClick: () -> Unit,
    onOpenLibraryClick: () -> Unit,
    onGridColumnsChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var showRemoveConfirmationDialog by remember { mutableStateOf(false) }
    var showSortAndGridSheet by remember { mutableStateOf(false) }

    if (showRemoveConfirmationDialog && state.selectedCollection != null) {
        RemoveFromBookmarkConfirmationDialog(
            count = state.selectedComicIds.size,
            collectionName = state.selectedCollection.name,
            onConfirm = {
                viewModel.bulkRemoveFromCollection(state.selectedCollection.id)
                showRemoveConfirmationDialog = false
            },
            onDismiss = { showRemoveConfirmationDialog = false }
        )
    }

    if (showSortAndGridSheet) {
        SortAndGridBottomSheet(
            currentSort = state.sortOption,
            currentGrid = state.gridColumns,
            onSortSelected = { sort ->
                viewModel.setSortOption(sort)
                showSortAndGridSheet = false
            },
            onGridSelected = { cols ->
                onGridColumnsChange(cols)
                showSortAndGridSheet = false
            },
            onDismiss = { showSortAndGridSheet = false }
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (state.isSelectionMode) {
            BookmarkSelectionActionBar(
                selectedCount = state.selectedComicIds.size,
                allCount = state.bookmarkedComics.size,
                onSelectAll = { viewModel.selectAllComics(state.bookmarkedComics.map { it.id }) },
                onClear = { viewModel.clearSelection() },
                onRemoveFromCollection = { showRemoveConfirmationDialog = true }
            )
            Spacer(modifier = Modifier.height(8.dp))
        } else {
            // Header Row with Title & Create Collection Action
            KitsuneSectionHeading(
                title = "Bookmarks",
                modifier = Modifier.padding(bottom = 8.dp),
                action = {
                    IconButton(onClick = onCreateCollectionClick) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Create Collection"
                        )
                    }
                }
            )

            // Search Bar
            KitsuneSearchField(
                value = state.searchQuery,
                onValueChange = { viewModel.onSearchQueryChanged(it) },
                placeholder = "Search titles or authors",
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // User Collections Selector Row (LazyRow)
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(state.collections, key = { it.id }) { collection ->
                    FilterChip(
                        selected = state.selectedCollection?.id == collection.id,
                        onClick = { viewModel.selectCollection(collection.id) },
                        label = { Text(collection.name) }
                    )
                }
            }

            // Status Filters Row (All, Completed, Not Completed) per Decision K-26
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BookmarkStatusFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = state.statusFilter == filter,
                        onClick = { viewModel.setStatusFilter(filter) },
                        label = { Text(filter.displayName) }
                    )
                }
            }

            // Controls & Count Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${state.bookmarkedComics.size} manga in ${state.selectedCollection?.name ?: "Collection"}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                OutlinedButton(
                    onClick = { showSortAndGridSheet = true },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.GridView,
                        contentDescription = "Sort and Grid Settings",
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    Text("Sort & Grid")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }

        if (state.bookmarkedComics.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                if (state.searchQuery.isNotBlank() || state.statusFilter != BookmarkStatusFilter.ALL) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (state.searchQuery.isNotBlank()) {
                                "No manga matching \"${state.searchQuery}\""
                            } else {
                                "No ${state.statusFilter.displayName.lowercase()} manga in ${state.selectedCollection?.name ?: "collection"}"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    KitsuneEmptyState(
                        icon = Icons.AutoMirrored.Filled.MenuBook,
                        title = "Collection is empty",
                        message = "This collection does not contain any manga yet. Add manga to '${state.selectedCollection?.name ?: "Collection"}' from your Library or Comic Detail.",
                        actionText = "Open Library",
                        onAction = onOpenLibraryClick,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(state.gridColumns.coerceIn(2, 4)),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(
                    items = state.bookmarkedComics,
                    key = { it.id }
                ) { comic ->
                    BookmarkComicGridCard(
                        comic = comic,
                        isSelected = state.selectedComicIds.contains(comic.id),
                        isSelectionMode = state.isSelectionMode,
                        viewModel = viewModel,
                        onClick = { onComicClick(comic.id) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortAndGridBottomSheet(
    currentSort: BookmarkSortOption,
    currentGrid: Int,
    onSortSelected: (BookmarkSortOption) -> Unit,
    onGridSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Sort & grid",
                style = MaterialTheme.typography.titleLarge
            )

            Text(
                text = "Sort by",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BookmarkSortOption.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSortSelected(option) }
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = option.displayName, style = MaterialTheme.typography.bodyLarge)
                        RadioButton(
                            selected = currentSort == option,
                            onClick = { onSortSelected(option) }
                        )
                    }
                }
            }

            Text(
                text = "Columns",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(2, 3, 4).forEach { cols ->
                    FilterChip(
                        selected = currentGrid == cols,
                        onClick = { onGridSelected(cols) },
                        label = { Text("${cols}x") }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun CreateCollectionDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var nameText by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Collection") },
        text = {
            Column {
                OutlinedTextField(
                    value = nameText,
                    onValueChange = {
                        nameText = it
                        if (it.isNotBlank()) isError = false
                    },
                    label = { Text("Collection Name") },
                    isError = isError,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (isError) {
                    Text(
                        text = "Collection name cannot be empty",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (nameText.isBlank()) {
                        isError = true
                    } else {
                        onConfirm(nameText.trim())
                    }
                }
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookmarkComicGridCard(
    comic: ComicEntity,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    viewModel: BookmarkViewModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    if (isSelectionMode) {
                        viewModel.toggleComicSelection(comic.id)
                    } else {
                        onClick()
                    }
                },
                onLongClick = {
                    viewModel.toggleComicSelection(comic.id)
                }
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box {
            Column {
                BookmarkCoverImage(
                    comic = comic,
                    viewModel = viewModel
                )
                Column(modifier = Modifier.padding(8.dp)) {
                    Text(
                        text = comic.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                )
            }
        }
    }
}

@Composable
private fun BookmarkSelectionActionBar(
    selectedCount: Int,
    allCount: Int,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onRemoveFromCollection: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$selectedCount / $allCount Selected",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.material3.TextButton(onClick = onSelectAll) {
                    Text("Select All")
                }
                Button(
                    onClick = onRemoveFromCollection,
                    enabled = selectedCount > 0,
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Remove from Collection")
                }
                IconButton(onClick = onClear) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Clear Selection",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun RemoveFromBookmarkConfirmationDialog(
    count: Int,
    collectionName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove from Collection") },
        text = {
            Text("Are you sure you want to remove $count selected comic(s) from collection '$collectionName'? Comics will NOT be deleted from your device storage.")
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Remove")
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun BookmarkCoverImage(
    comic: ComicEntity,
    viewModel: BookmarkViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coverState by produceState<CoverUiState>(
        initialValue = CoverUiState.Loading,
        key1 = comic.id,
        key2 = comic.coverEntryPath,
        key3 = comic.lastScannedAt
    ) {
        var acquiredFile: File? = null
        try {
            val cacheResult = viewModel.getCoverFile(comic.id)
            val file = when (cacheResult) {
                is CoverCacheResult.Hit -> cacheResult.file
                is CoverCacheResult.Success -> cacheResult.file
                else -> null
            }

            if (file == null || !file.isFile || !file.exists()) {
                value = CoverUiState.Unavailable
                return@produceState
            }

            acquiredFile = file

            val request = ImageRequest.Builder(context)
                .data(file)
                .size(300, 420)
                .build()

            val imageLoader = context.imageLoader
            val result = imageLoader.execute(request)

            if (result is SuccessResult) {
                val drawable = result.drawable
                val bitmap = when (drawable) {
                    is BitmapDrawable -> drawable.bitmap
                    else -> {
                        val width = drawable.intrinsicWidth.coerceAtLeast(1)
                        val height = drawable.intrinsicHeight.coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bmp)
                        drawable.setBounds(0, 0, width, height)
                        drawable.draw(canvas)
                        bmp
                    }
                }
                value = CoverUiState.Loaded(bitmap.asImageBitmap())
            } else {
                value = CoverUiState.Error
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            value = CoverUiState.Error
        } finally {
            acquiredFile?.let { file ->
                withContext(NonCancellable) {
                    viewModel.releaseCoverFile(file)
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(0.7f),
        contentAlignment = Alignment.Center
    ) {
        when (val state = coverState) {
            is CoverUiState.Loaded -> {
                Image(
                    painter = BitmapPainter(state.imageBitmap),
                    contentDescription = "Cover for ${comic.title}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            else -> {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.MenuBook,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BookmarkEmptyNoCollectionsView(
    onCreateCollectionClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        KitsuneEmptyState(
            icon = Icons.Default.BookmarkBorder,
            title = "Keep your next story close",
            message = "Bookmark manga from your library. Your saved stories will appear here.",
            actionText = "Create Collection",
            onAction = onCreateCollectionClick,
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Composable
private fun BookmarkRootNotConfiguredView(
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.FolderOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            Text(
                text = "Root Folder Not Configured",
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Select a 'Kitsune' folder in device storage to load bookmarks.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onNavigateToSettings) {
                Text("Open Settings")
            }
        }
    }
}

@Composable
private fun BookmarkPermissionDeniedView(
    message: String,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            Text(
                text = "Folder Access Disconnected",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Folder read permission lost: $message",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onNavigateToSettings) {
                Text("Recheck Access")
            }
        }
    }
}

@Composable
private fun BookmarkErrorView(
    message: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            Text(
                text = "Failed to Load Bookmarks",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}
