package com.example.kitsune.ui.screens.bookmark

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.kitsune.data.entity.BookmarkEntity
import com.example.kitsune.ui.components.KitsuneEmptyState
import com.example.kitsune.ui.components.KitsuneSectionHeading
import com.example.kitsune.ui.theme.ComponentShapes

/**
 * Root Bookmarks Landing Screen (Task 12.7 / K-36).
 * Displays all user-created collections as cards.
 * When a card is clicked, navigates to Collection Detail.
 */
@Composable
fun BookmarkScreen(
    viewModel: BookmarkViewModel,
    onCollectionClick: (Long) -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showCreateDialog by remember { mutableStateOf(false) }

    if (showCreateDialog) {
        CreateBookmarkCollectionDialog(
            onConfirm = { name ->
                viewModel.createCollection(name) { newId ->
                    onCollectionClick(newId)
                }
                showCreateDialog = false
            },
            onDismiss = { showCreateDialog = false }
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        when (val state = uiState) {
            is BookmarkLandingUiState.Loading -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
            is BookmarkLandingUiState.RootNotConfigured -> {
                BookmarkRootNotConfiguredView(
                    onNavigateToSettings = onNavigateToSettings,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            is BookmarkLandingUiState.PermissionDenied -> {
                BookmarkPermissionDeniedView(
                    message = state.message,
                    onNavigateToSettings = onNavigateToSettings,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            is BookmarkLandingUiState.EmptyNoCollections -> {
                BookmarkEmptyNoCollectionsView(
                    onCreateCollectionClick = { showCreateDialog = true },
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            is BookmarkLandingUiState.Success -> {
                BookmarkLandingSuccessView(
                    collections = state.collections,
                    viewModel = viewModel,
                    onCollectionClick = onCollectionClick,
                    onCreateCollectionClick = { showCreateDialog = true }
                )
            }
            is BookmarkLandingUiState.Error -> {
                BookmarkErrorView(
                    message = state.message,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    }
}

@Composable
private fun BookmarkLandingSuccessView(
    collections: List<BookmarkEntity>,
    viewModel: BookmarkViewModel,
    onCollectionClick: (Long) -> Unit,
    onCreateCollectionClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var collectionToRename by remember { mutableStateOf<BookmarkEntity?>(null) }
    var collectionToDelete by remember { mutableStateOf<BookmarkEntity?>(null) }

    collectionToRename?.let { col ->
        RenameBookmarkCollectionDialog(
            collection = col,
            onConfirm = { newName ->
                viewModel.renameCollection(col.id, newName)
                collectionToRename = null
            },
            onDismiss = { collectionToRename = null }
        )
    }

    collectionToDelete?.let { col ->
        DeleteBookmarkCollectionConfirmationDialog(
            collection = col,
            onConfirm = {
                viewModel.deleteCollection(col.id)
                collectionToDelete = null
            },
            onDismiss = { collectionToDelete = null }
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        KitsuneSectionHeading(
            title = "Bookmarks",
            modifier = Modifier.padding(bottom = 12.dp),
            action = {
                IconButton(onClick = onCreateCollectionClick) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Create Collection"
                    )
                }
            }
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            itemsIndexed(collections, key = { _, col -> col.id }) { index, col ->
                BookmarkCollectionCard(
                    collection = col,
                    canMoveUp = index > 0,
                    canMoveDown = index < collections.size - 1,
                    onClick = { onCollectionClick(col.id) },
                    onMoveUp = {
                        val mutable = collections.toMutableList()
                        val temp = mutable[index]
                        mutable[index] = mutable[index - 1]
                        mutable[index - 1] = temp
                        viewModel.reorderCollections(mutable)
                    },
                    onMoveDown = {
                        val mutable = collections.toMutableList()
                        val temp = mutable[index]
                        mutable[index] = mutable[index + 1]
                        mutable[index + 1] = temp
                        viewModel.reorderCollections(mutable)
                    },
                    onRename = { collectionToRename = col },
                    onDelete = { collectionToDelete = col }
                )
            }
        }
    }
}

@Composable
fun BookmarkCollectionCard(
    collection: BookmarkEntity,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onClick: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = ComponentShapes.card,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.BookmarkBorder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = collection.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onMoveUp,
                    enabled = canMoveUp
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowUp,
                        contentDescription = "Move Up"
                    )
                }

                IconButton(
                    onClick = onMoveDown,
                    enabled = canMoveDown
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Move Down"
                    )
                }

                IconButton(onClick = onRename) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Rename Collection"
                    )
                }

                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete Collection",
                        tint = MaterialTheme.colorScheme.error
                    )
                }

                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                    contentDescription = "Open Collection",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }
    }
}

@Composable
fun CreateBookmarkCollectionDialog(
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

@Composable
fun RenameBookmarkCollectionDialog(
    collection: BookmarkEntity,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var nameText by remember { mutableStateOf(collection.name) }
    var isError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Collection") },
        text = {
            Column {
                OutlinedTextField(
                    value = nameText,
                    onValueChange = {
                        nameText = it
                        if (it.isNotBlank()) isError = false
                    },
                    label = { Text("New Name") },
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

@Composable
fun DeleteBookmarkCollectionConfirmationDialog(
    collection: BookmarkEntity,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete Collection") },
        text = {
            Text("Are you sure you want to delete collection '${collection.name}'? Items in this collection will NOT be deleted from device storage.")
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
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
            message = "Bookmark items from your library. Your saved stories will appear here.",
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
