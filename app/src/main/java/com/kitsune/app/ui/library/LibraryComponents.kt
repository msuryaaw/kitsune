package com.kitsune.app.ui.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.kitsune.app.domain.model.Comic

/**
 * Representasi status komik untuk indikator visual.
 */
enum class ComicStatus {
    BOOKMARKED,
    IN_PLAYLIST
}

/**
 * REVISION 6.7.8: Constants for common sets to avoid allocations in ViewModel associate blocks.
 */
object ComicStatusSets {
    val EMPTY = emptySet<ComicStatus>()
    val BOOKMARKED = setOf(ComicStatus.BOOKMARKED)
    val IN_PLAYLIST = setOf(ComicStatus.IN_PLAYLIST)
    val BOTH = setOf(ComicStatus.BOOKMARKED, ComicStatus.IN_PLAYLIST)
}

/**
 * Metadata UI untuk sebuah kartu komik.
 * Anotasi Immutable memastikan Compose dapat melewati recomposition jika data identik.
 */
@Immutable
data class ComicCardState(
    val isSelected: Boolean = false,
    val statuses: Set<ComicStatus> = ComicStatusSets.EMPTY
)

/**
 * Representasi aksi pada Selection Mode yang reusable.
 */
@Immutable
data class SelectionAction(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ComicCard(
    comic: Comic,
    state: ComicCardState,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    // REVISION 6.7.8: Optimized ImageRequest with remember and sizing
    val context = LocalContext.current
    val imageRequest = remember(comic.coverUri) {
        ImageRequest.Builder(context)
            .data(comic.coverUri)
            .crossfade(true)
            // Target size for grid (approx 5:7 aspect ratio)
            // This prevents Coil from keeping full-size bitmaps in memory
            .size(300, 420) 
            .build()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Box {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(5f / 7f)
                    .then(
                        if (state.isSelected) Modifier.border(
                            2.dp,
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.shapes.medium
                        ) else Modifier
                    ),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
            ) {
                AsyncImage(
                    model = imageRequest,
                    contentDescription = null, // Optimized: fixed/null description
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }

            // Status Badges (Top Start)
            if (state.statuses.isNotEmpty() && !state.isSelected) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    state.statuses.forEach { status ->
                        StatusBadgeIcon(status = status)
                    }
                }
            }

            // Selection Indicator (Top End)
            if (state.isSelected) {
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
        Text(
            text = comic.title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (state.isSelected) FontWeight.Bold else FontWeight.Normal,
            color = if (state.isSelected) MaterialTheme.colorScheme.primary else Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

/**
 * Mapping internal ComicStatus ke Icon, Tint, dan ContentDescription.
 */
@Composable
private fun StatusBadgeIcon(status: ComicStatus) {
    // Avoid object allocation (Triple) by using local constants
    val icon: ImageVector
    val tint: Color
    val description: String

    when (status) {
        ComicStatus.BOOKMARKED -> {
            icon = Icons.Default.Bookmark
            tint = MaterialTheme.colorScheme.primary
            description = "Bookmarked"
        }
        ComicStatus.IN_PLAYLIST -> {
            icon = Icons.AutoMirrored.Filled.List
            tint = Color(0xFF4CAF50)
            description = "In Playlist"
        }
    }

    Surface(
        shape = RoundedCornerShape(4.dp),
        color = Color.Black.copy(alpha = 0.7f),
        modifier = Modifier.size(20.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.padding(3.dp)
        )
    }
}

/**
 * Grid Komik Reusable dengan optimasi performa tinggi.
 */
@Composable
fun ComicGrid(
    comics: List<Comic>,
    gridSize: Int,
    comicStatuses: Map<String, Set<ComicStatus>> = emptyMap(),
    selectedPaths: Set<String> = emptySet(),
    state: LazyGridState = rememberLazyGridState(),
    onComicClick: (Comic) -> Unit,
    onComicLongClick: (Comic) -> Unit = {}
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(gridSize),
        state = state,
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(
            items = comics, 
            key = { it.relativePath },
            contentType = { "comic" }
        ) { comic ->
            // Optimize lookup and state allocation
            val isSelected = remember(selectedPaths, comic.relativePath) {
                selectedPaths.contains(comic.relativePath)
            }
            val statuses = remember(comicStatuses, comic.relativePath) {
                comicStatuses[comic.relativePath] ?: ComicStatusSets.EMPTY
            }
            val cardState = remember(isSelected, statuses) {
                ComicCardState(isSelected, statuses)
            }

            // Stable lambdas to prevent redundant card recompositions
            val currentOnComicClick by rememberUpdatedState(onComicClick)
            val currentOnComicLongClick by rememberUpdatedState(onComicLongClick)
            
            val onClick = remember(comic) { { currentOnComicClick(comic) } }
            val onLongClick = remember(comic) { { currentOnComicLongClick(comic) } }

            ComicCard(
                comic = comic,
                state = cardState,
                onClick = onClick,
                onLongClick = onLongClick
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchTopAppBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onCloseClick: () -> Unit
) {
    TopAppBar(
        title = {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text("Search title...") },
                modifier = Modifier.fillMaxWidth(),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                singleLine = true
            )
        },
        navigationIcon = {
            IconButton(onClick = onCloseClick) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
        actions = {
            AnimatedVisibility(
                visible = query.isNotEmpty(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Close, contentDescription = "Clear")
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionTopAppBar(
    selectedCount: Int,
    onCancel: () -> Unit,
    onSelectAll: () -> Unit,
    actions: List<SelectionAction>
) {
    TopAppBar(
        title = { Text("$selectedCount Selected") },
        navigationIcon = {
            IconButton(onClick = onCancel) {
                Icon(Icons.Default.Close, contentDescription = "Cancel")
            }
        },
        actions = {
            IconButton(onClick = onSelectAll) {
                Icon(Icons.Default.CheckCircle, contentDescription = "Select All")
            }
            actions.forEach { action ->
                IconButton(onClick = action.onClick) {
                    Icon(action.icon, contentDescription = action.label)
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    )
}

/**
 * Dialog pemilihan kategori generic.
 */
@Composable
fun CollectionPickerDialog(
    title: String,
    collections: List<Pair<Long, String>>,
    selectedIds: Set<Long>,
    onSelectionChanged: (Set<Long>) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onCreateNew: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title)
                IconButton(onClick = onCreateNew) {
                    Icon(Icons.Default.Add, contentDescription = "Create New")
                }
            }
        },
        text = {
            if (collections.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                    Text("No categories found", color = Color.Gray)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(
                        items = collections,
                        key = { it.first },
                        contentType = { "category" }
                    ) { (id, name) ->
                        val isSelected = selectedIds.contains(id)
                        ListItem(
                            headlineContent = { Text(name) },
                            trailingContent = {
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = { checked ->
                                        if (checked) onSelectionChanged(selectedIds + id)
                                        else onSelectionChanged(selectedIds - id)
                                    }
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (isSelected) onSelectionChanged(selectedIds - id)
                                    else onSelectionChanged(selectedIds + id)
                                }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = selectedIds.isNotEmpty()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Dialog input generic untuk membuat kategori baru.
 */
@Composable
fun GenericCreateDialog(
    title: String,
    hint: String,
    confirmLabel: String = "Create",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            TextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text(hint) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name) },
                enabled = name.isNotBlank()
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun EmptyLibraryState(
    message: String,
    icon: ImageVector
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = Color.Gray
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.Gray,
            textAlign = TextAlign.Center
        )
    }
}
