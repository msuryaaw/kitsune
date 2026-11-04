package com.example.kitsune.ui.screens

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.kitsune.core.RootFolderPreferences
import com.example.kitsune.core.RootFolderState
import com.example.kitsune.core.SafStorageHelper
import com.example.kitsune.data.LibraryRepository
import com.example.kitsune.data.SettingsRepository
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    rootFolderPreferences: RootFolderPreferences,
    settingsRepository: SettingsRepository? = null,
    libraryRepository: LibraryRepository? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var folderState by remember { mutableStateOf<RootFolderState>(RootFolderState.NotConfigured) }
    var operationError by remember { mutableStateOf<String?>(null) }
    var isVerifying by remember { mutableStateOf(false) }
    var showReleaseRootDialog by remember { mutableStateOf(false) }
    var showBookmarkManagerDialog by remember { mutableStateOf(false) }

    val settingsState by (settingsRepository?.observeSettings() ?: flowOf(com.example.kitsune.data.entity.SettingsEntity()))
        .collectAsStateWithLifecycle(initialValue = com.example.kitsune.data.entity.SettingsEntity())

    fun refreshStatus() {
        val uriStr = rootFolderPreferences.getRootUriString()
        if (uriStr == null) {
            folderState = RootFolderState.NotConfigured
        } else {
            val parsedUri = try {
                Uri.parse(uriStr)
            } catch (_: Exception) {
                null
            }

            if (parsedUri == null) {
                folderState = RootFolderState.Inaccessible(uriStr, "Invalid URI format.")
            } else {
                folderState = SafStorageHelper.checkRootAccess(context, parsedUri)
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshStatus()
    }

    // Custom contract to capture both Uri and Intent flags from ActivityResult
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        operationError = null
        if (result.resultCode == Activity.RESULT_OK) {
            val intent = result.data
            val pickedUri = intent?.data

            if (pickedUri == null) {
                operationError = "Failed to read selected folder: URI unavailable."
                return@rememberLauncherForActivityResult
            }

            // Attempt to persist permissions BEFORE overwriting existing root
            val takeResult = SafStorageHelper.takePersistablePermissions(
                resolver = context.contentResolver,
                uri = pickedUri,
                resultIntent = intent
            )

            takeResult.onSuccess {
                // Verify that permission is actually held
                val verifiedAccess = SafStorageHelper.checkRootAccess(context, pickedUri)
                if (verifiedAccess is RootFolderState.Ready) {
                    rootFolderPreferences.setRootUriString(pickedUri.toString())
                    folderState = verifiedAccess
                    // Ensure "Comics" directory exists and trigger initial scan
                    scope.launch {
                        try {
                            libraryRepository?.ensureComicsDirectory()
                            libraryRepository?.syncLibrary(pickedUri.toString())
                        } catch (_: Exception) {}
                    }
                } else {
                    operationError = "Permission granted but folder remains inaccessible."
                    folderState = verifiedAccess
                }
            }.onFailure { err ->
                operationError = "Failed to acquire folder read permission: ${err.localizedMessage ?: "Access denied"}. Active folder unchanged."
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Storage Management Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Storage Management",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))

                when (val state = folderState) {
                    is RootFolderState.NotConfigured -> {
                        Text(
                            text = "Status: Root folder not selected.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Select a 'Kitsune' folder containing a 'Comics' subfolder.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    is RootFolderState.PermissionLost -> {
                        Text(
                            text = "Status: Read permission lost or revoked.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = "Reason: ${state.reason}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = "Please select the folder again to update permission.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    is RootFolderState.Inaccessible -> {
                        Text(
                            text = "Status: Folder access error.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = "Reason: ${state.error}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    is RootFolderState.Ready -> {
                        Text(
                            text = "Status: Ready (Active access verified)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Folder configured in device storage",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }

                if (operationError != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = operationError ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            val openDocTreeIntent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                                val currentStr = rootFolderPreferences.getRootUriString()
                                if (!currentStr.isNullOrEmpty()) {
                                    try {
                                        val currentUri = Uri.parse(currentStr)
                                        putExtra("android.provider.extra.INITIAL_URI", currentUri)
                                    } catch (_: Exception) {}
                                }
                            }
                            launcher.launch(openDocTreeIntent)
                        }
                    ) {
                        Text(
                            text = if (folderState is RootFolderState.Ready) {
                                "Change Root Folder"
                            } else {
                                "Select Root Folder"
                            }
                        )
                    }

                    if (folderState !is RootFolderState.NotConfigured) {
                        OutlinedButton(
                            onClick = {
                                operationError = null
                                isVerifying = true
                                refreshStatus()
                                isVerifying = false
                            },
                            enabled = !isVerifying
                        ) {
                            if (isVerifying) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            Text("Recheck Access")
                        }
                    }
                }

                if (showReleaseRootDialog) {
                    ReleaseRootConfirmationDialog(
                        onConfirm = {
                            showReleaseRootDialog = false
                            val currentStr = rootFolderPreferences.getRootUriString()
                            var releaseFailed = false
                            if (!currentStr.isNullOrEmpty()) {
                                try {
                                    val uri = Uri.parse(currentStr)
                                    val releaseResult = SafStorageHelper.releasePersistablePermissions(
                                        resolver = context.contentResolver,
                                        uri = uri
                                    )
                                    releaseResult.onFailure { err ->
                                        releaseFailed = true
                                        operationError = "Warning: Failed to release system permission: ${err.localizedMessage ?: "Unknown error"}"
                                    }
                                } catch (_: Exception) {}
                            }

                            if (!releaseFailed) {
                                rootFolderPreferences.setRootUriString(null)
                                refreshStatus()
                            }
                        },
                        onDismiss = { showReleaseRootDialog = false }
                    )
                }

                if (folderState !is RootFolderState.NotConfigured) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { showReleaseRootDialog = true }
                    ) {
                        Text("Release Root Folder")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Reader Preferences Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Reader Preferences",
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Reading Mode
                Text(
                    text = "Reading Mode",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settingsState.readerMode == "single_page",
                        onClick = {
                            scope.launch { settingsRepository?.setReaderMode("single_page") }
                        },
                        label = { Text("Single Page") }
                    )
                    FilterChip(
                        selected = settingsState.readerMode == "vertical_continuous",
                        onClick = {
                            scope.launch { settingsRepository?.setReaderMode("vertical_continuous") }
                        },
                        label = { Text("Vertical Continuous") }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Reading Direction
                Text(
                    text = "Reading Direction",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settingsState.readingDirection == "ltr",
                        onClick = {
                            scope.launch { settingsRepository?.setReadingDirection("ltr") }
                        },
                        label = { Text("LTR") }
                    )
                    FilterChip(
                        selected = settingsState.readingDirection == "rtl",
                        onClick = {
                            scope.launch { settingsRepository?.setReadingDirection("rtl") }
                        },
                        label = { Text("RTL") }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Page Fit
                Text(
                    text = "Page Fit",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settingsState.pageFit == "fit_screen",
                        onClick = {
                            scope.launch { settingsRepository?.setPageFit("fit_screen") }
                        },
                        label = { Text("Fit Screen") }
                    )
                    FilterChip(
                        selected = settingsState.pageFit == "fit_width",
                        onClick = {
                            scope.launch { settingsRepository?.setPageFit("fit_width") }
                        },
                        label = { Text("Fit Width") }
                    )
                    FilterChip(
                        selected = settingsState.pageFit == "fit_height",
                        onClick = {
                            scope.launch { settingsRepository?.setPageFit("fit_height") }
                        },
                        label = { Text("Fit Height") }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Keep Screen Awake
                Text(
                    text = "Keep Screen Awake",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settingsState.keepScreenAwake,
                        onClick = {
                            scope.launch { settingsRepository?.setKeepScreenAwake(!settingsState.keepScreenAwake) }
                        },
                        label = { Text(if (settingsState.keepScreenAwake) "Enabled" else "Disabled") }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Appearance & Grid Preferences Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Display & Grid",
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(modifier = Modifier.height(12.dp))

                // App Theme
                Text(
                    text = "App Theme",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settingsState.theme == "system",
                        onClick = {
                            scope.launch { settingsRepository?.setTheme("system") }
                        },
                        label = { Text("System") }
                    )
                    FilterChip(
                        selected = settingsState.theme == "light",
                        onClick = {
                            scope.launch { settingsRepository?.setTheme("light") }
                        },
                        label = { Text("Light") }
                    )
                    FilterChip(
                        selected = settingsState.theme == "dark",
                        onClick = {
                            scope.launch { settingsRepository?.setTheme("dark") }
                        },
                        label = { Text("Dark") }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Library Grid Columns
                Text(
                    text = "Library Grid Columns",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(2, 3, 4).forEach { cols ->
                        FilterChip(
                            selected = settingsState.gridColumns == cols,
                            onClick = {
                                scope.launch { settingsRepository?.setGridColumns(cols) }
                            },
                            label = { Text("${cols}x") }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Bookmark Manager Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Bookmark Manager",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Manage, create, and reorder your Bookmark collections.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = { showBookmarkManagerDialog = true }
                ) {
                    Text("Open Bookmark Manager")
                }
            }
        }

        if (showBookmarkManagerDialog) {
            BookmarkManagerDialog(
                libraryRepository = libraryRepository,
                onDismiss = { showBookmarkManagerDialog = false }
            )
        }
    }
}

@Composable
private fun ReleaseRootConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Release Root Folder") },
        text = {
            Text("Are you sure you want to release the active root folder? Comics cannot be loaded until a root folder is selected again.")
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("Release")
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
private fun BookmarkManagerDialog(
    libraryRepository: LibraryRepository?,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val collections by (libraryRepository?.observeAllBookmarkCollections() ?: flowOf(emptyList()))
        .collectAsStateWithLifecycle(initialValue = emptyList())

    var showCreateDialog by remember { mutableStateOf(false) }
    var collectionToDelete by remember { mutableStateOf<com.example.kitsune.data.entity.BookmarkEntity?>(null) }
    var collectionToRename by remember { mutableStateOf<com.example.kitsune.data.entity.BookmarkEntity?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    if (showCreateDialog) {
        CreateBookmarkCollectionDialog(
            onConfirm = { name ->
                scope.launch {
                    try {
                        libraryRepository?.createBookmarkCollection(name)
                        showCreateDialog = false
                        errorMessage = null
                    } catch (e: Exception) {
                        errorMessage = e.localizedMessage ?: "Failed to create collection"
                    }
                }
            },
            onDismiss = { showCreateDialog = false }
        )
    }

    collectionToRename?.let { col ->
        RenameBookmarkCollectionDialog(
            collection = col,
            onConfirm = { newName ->
                scope.launch {
                    try {
                        libraryRepository?.renameBookmarkCollection(col.id, newName)
                        collectionToRename = null
                        errorMessage = null
                    } catch (e: Exception) {
                        errorMessage = e.localizedMessage ?: "Failed to rename collection"
                    }
                }
            },
            onDismiss = { collectionToRename = null }
        )
    }

    collectionToDelete?.let { col ->
        DeleteBookmarkCollectionConfirmationDialog(
            collection = col,
            onConfirm = {
                scope.launch {
                    try {
                        libraryRepository?.deleteBookmarkCollection(col.id)
                        collectionToDelete = null
                        errorMessage = null
                    } catch (e: Exception) {
                        errorMessage = e.localizedMessage ?: "Failed to delete collection"
                    }
                }
            },
            onDismiss = { collectionToDelete = null }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bookmark Manager") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (errorMessage != null) {
                    Text(
                        text = errorMessage ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                if (collections.isEmpty()) {
                    Text(
                        text = "No Bookmark collections created yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        itemsIndexed(collections, key = { _, col -> col.id }) { index, col ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = col.name,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }

                                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                        IconButton(
                                            onClick = {
                                                if (index > 0) {
                                                    val mutable = collections.toMutableList()
                                                    val temp = mutable[index]
                                                    mutable[index] = mutable[index - 1]
                                                    mutable[index - 1] = temp
                                                    scope.launch {
                                                        libraryRepository?.reorderBookmarkCollections(mutable)
                                                    }
                                                }
                                            },
                                            enabled = index > 0
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.KeyboardArrowUp,
                                                contentDescription = "Move Up"
                                            )
                                        }

                                        IconButton(
                                            onClick = {
                                                if (index < collections.size - 1) {
                                                    val mutable = collections.toMutableList()
                                                    val temp = mutable[index]
                                                    mutable[index] = mutable[index + 1]
                                                    mutable[index + 1] = temp
                                                    scope.launch {
                                                        libraryRepository?.reorderBookmarkCollections(mutable)
                                                    }
                                                }
                                            },
                                            enabled = index < collections.size - 1
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.KeyboardArrowDown,
                                                contentDescription = "Move Down"
                                            )
                                        }

                                        IconButton(
                                            onClick = { collectionToRename = col }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Edit,
                                                contentDescription = "Rename Collection"
                                            )
                                        }

                                        IconButton(
                                            onClick = { collectionToDelete = col }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = "Delete Collection",
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = { showCreateDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Create New Collection")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun CreateBookmarkCollectionDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var nameText by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Bookmark Collection") },
        text = {
            Column {
                OutlinedTextField(
                    value = nameText,
                    onValueChange = {
                        nameText = it
                        errorText = null
                    },
                    label = { Text("Collection Name") },
                    singleLine = true,
                    isError = errorText != null,
                    modifier = Modifier.fillMaxWidth()
                )
                if (errorText != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = errorText ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmed = nameText.trim()
                    if (trimmed.isBlank()) {
                        errorText = "Collection name cannot be empty"
                    } else {
                        onConfirm(trimmed)
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
private fun RenameBookmarkCollectionDialog(
    collection: com.example.kitsune.data.entity.BookmarkEntity,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var nameText by remember { mutableStateOf(collection.name) }
    var errorText by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Collection") },
        text = {
            Column {
                OutlinedTextField(
                    value = nameText,
                    onValueChange = {
                        nameText = it
                        errorText = null
                    },
                    label = { Text("New Collection Name") },
                    singleLine = true,
                    isError = errorText != null,
                    modifier = Modifier.fillMaxWidth()
                )
                if (errorText != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = errorText ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmed = nameText.trim()
                    if (trimmed.isBlank()) {
                        errorText = "Collection name cannot be empty"
                    } else {
                        onConfirm(trimmed)
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
private fun DeleteBookmarkCollectionConfirmationDialog(
    collection: com.example.kitsune.data.entity.BookmarkEntity,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete Collection") },
        text = {
            Text("Are you sure you want to delete collection '${collection.name}'? Comics inside this collection will NOT be deleted from your device storage.")
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
