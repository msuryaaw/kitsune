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
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.kitsune.core.RootFolderPreferences
import com.example.kitsune.core.RootFolderState
import com.example.kitsune.core.SafStorageHelper
import com.example.kitsune.data.LibraryRepository
import com.example.kitsune.data.SettingsRepository
import com.example.kitsune.ui.components.KitsuneSectionHeading
import com.example.kitsune.ui.components.KitsuneSegmentedControl
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

            val takeResult = SafStorageHelper.takePersistablePermissions(
                resolver = context.contentResolver,
                uri = pickedUri,
                resultIntent = intent
            )

            takeResult.onSuccess {
                val verifiedAccess = SafStorageHelper.checkRootAccess(context, pickedUri)
                if (verifiedAccess is RootFolderState.Ready) {
                    rootFolderPreferences.setRootUriString(pickedUri.toString())
                    folderState = verifiedAccess
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
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineMedium
        )

        // Section 1 — Storage Management
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            KitsuneSectionHeading(title = "Storage Management")

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
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
        }

        // Section 2 — Reader Preferences
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            KitsuneSectionHeading(title = "Reader Preferences")

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Reading Mode
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Reading mode",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "How pages move while reading",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        KitsuneSegmentedControl(
                            options = listOf(
                                "single_page" to "Single Page",
                                "vertical_continuous" to "Vertical Continuous"
                            ),
                            selected = settingsState.readerMode,
                            onSelected = { mode ->
                                scope.launch { settingsRepository?.setReaderMode(mode) }
                            }
                        )
                    }

                    // Reading Direction
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Reading direction",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Page order and navigation",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        KitsuneSegmentedControl(
                            options = listOf("ltr" to "LTR", "rtl" to "RTL"),
                            selected = settingsState.readingDirection,
                            onSelected = { dir ->
                                scope.launch { settingsRepository?.setReadingDirection(dir) }
                            }
                        )
                    }

                    // Page Fit
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Page fit",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Default page scaling",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        KitsuneSegmentedControl(
                            options = listOf(
                                "fit_screen" to "Fit Screen",
                                "fit_width" to "Fit Width",
                                "fit_height" to "Fit Height"
                            ),
                            selected = settingsState.pageFit,
                            onSelected = { fit ->
                                scope.launch { settingsRepository?.setPageFit(fit) }
                            }
                        )
                    }

                    // Keep Screen Awake
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Keep screen awake",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Prevent sleep while reading",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settingsState.keepScreenAwake,
                            onCheckedChange = { checked ->
                                scope.launch { settingsRepository?.setKeepScreenAwake(checked) }
                            }
                        )
                    }
                }
            }
        }

        // Section 3 — Display & Grid
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            KitsuneSectionHeading(title = "Display & Grid")

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // App Theme (Dark & System ONLY per locked specs)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Theme",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Application appearance",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        KitsuneSegmentedControl(
                            options = listOf("dark" to "Dark", "system" to "System"),
                            selected = if (settingsState.theme == "light") "system" else settingsState.theme,
                            onSelected = { theme ->
                                scope.launch { settingsRepository?.setTheme(theme) }
                            }
                        )
                    }

                    // Library Grid Columns
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Library grid",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Default cover density",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        KitsuneSegmentedControl(
                            options = listOf(2 to "2 columns", 3 to "3 columns", 4 to "4 columns"),
                            selected = settingsState.gridColumns,
                            onSelected = { cols ->
                                scope.launch { settingsRepository?.setGridColumns(cols) }
                            }
                        )
                    }
                }
            }
        }

        // Section 4 — Bookmark Manager
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            KitsuneSectionHeading(title = "Bookmark Manager")

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Collection Management",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Manage, create, rename, and reorder your Bookmark collections.",
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
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = col.name,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
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