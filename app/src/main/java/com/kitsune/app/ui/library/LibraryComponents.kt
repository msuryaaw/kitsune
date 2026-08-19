package com.kitsune.app.ui.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kitsune.app.domain.model.Comic
import com.kitsune.app.ui.components.media.*
import com.kitsune.app.ui.components.media.mapper.toMediaUiModel

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

/**
 * REVISION 7.8.3: Migrated to Unified Media Foundation.
 * REVISION 7.9.2: Migrated to Unified Media Badges.
 * REVISION 8.3.6: Theme Compliance - Removed hardcoded colors.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ComicCard(
    comic: Comic,
    state: ComicCardState,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    showDimmedEffect: Boolean = true
) {
    val mediaUiModel = remember(comic) { comic.toMediaUiModel() }
    val isBookmarked = remember(state.statuses) { state.statuses.contains(ComicStatus.BOOKMARKED) }

    MediaCardContainer(
        onClick = onClick,
        onLongClick = onLongClick,
        isSelected = state.isSelected
    ) {
        Box {
            MediaThumbnail(
                thumbnailUri = mediaUiModel.thumbnailUri,
                mediaType = mediaUiModel.mediaType,
                modifier = if (state.isSelected) Modifier.border(
                    2.dp,
                    MaterialTheme.colorScheme.primary,
                    MaterialTheme.shapes.medium
                ) else Modifier
            )

            // Mihon-style Dimmed Overlay (Poin 2)
            if (isBookmarked && showDimmedEffect && !state.isSelected) {
                Surface(
                    modifier = Modifier.matchParentSize(),
                    color = Color.Black.copy(alpha = 0.4f),
                    shape = MaterialTheme.shapes.medium
                ) {}
            }

            // Collection Badges (Top Start) - Unified 7.9.2
            // REVISION: Hapus badge bookmark jika dimmed effect aktif (Poin 2.1)
            if (!state.isSelected) {
                val filteredStatuses = if (showDimmedEffect) {
                    state.statuses.filter { it != ComicStatus.BOOKMARKED }.toSet()
                } else {
                    state.statuses
                }
                
                if (filteredStatuses.isNotEmpty()) {
                    MediaCollectionBadges(
                        statuses = filteredStatuses,
                        modifier = Modifier.align(Alignment.TopStart)
                    )
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
        
        MediaTitle(
            title = mediaUiModel.displayTitle,
            color = if (state.isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
            fontWeight = if (state.isSelected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

/**
 * Grid Komik Reusable dengan optimasi performa tinggi.
 * REVISION 7.8.5: Migrated to generic MediaGrid.
 */
@Composable
fun ComicGrid(
    comics: List<Comic>,
    gridSize: Int,
    comicStatuses: Map<String, Set<ComicStatus>> = emptyMap(),
    selectedPaths: Set<String> = emptySet(),
    state: LazyGridState = rememberLazyGridState(),
    showDimmedEffect: Boolean = true,
    onComicClick: (Comic) -> Unit,
    onComicLongClick: (Comic) -> Unit = {}
) {
    MediaGrid(
        items = comics,
        gridSize = gridSize,
        keySelector = { it.relativePath },
        state = state,
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
            onLongClick = onLongClick,
            showDimmedEffect = showDimmedEffect
        )
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
                    focusedTextColor = MaterialTheme.colorScheme.onBackground,
                    unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
                    focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                singleLine = true
            )
        },
        navigationIcon = {
            IconButton(onClick = onCloseClick) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack, 
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
        },
        actions = {
            AnimatedVisibility(
                visible = query.isNotEmpty(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Default.Close, 
                        contentDescription = "Clear",
                        tint = MaterialTheme.colorScheme.onBackground
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
 * REVISION 8.3.6: Theme Compliance.
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
                    Text("No categories found", color = MaterialTheme.colorScheme.onSurfaceVariant)
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

/**
 * REVISION 8.3.6: Theme Compliance.
 */
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
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
