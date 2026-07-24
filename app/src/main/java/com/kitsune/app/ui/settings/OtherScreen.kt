package com.kitsune.app.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
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
import com.kitsune.app.core.StorageHelper
import com.kitsune.app.database.entity.SettingsEntity
import com.kitsune.app.domain.model.VideoStatistics
import kotlinx.coroutines.flow.collectLatest

/**
 * Layar pengaturan aplikasi (Settings).
 * Menangani konfigurasi reader, library, storage, appearance, dan menampilkan statistik.
 * REVISION 8.3.4: Added Clear Watching History support.
 * REVISION 8.3.5: Integrated Video Statistics.
 * REVISION 8.3.6: Simplified Video Statistics to only show Total Videos.
 * REVISION 8.3.7: Theme Compliance - Removed hardcoded colors.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OtherScreen(
    viewModel: SettingsViewModel,
    storageHelper: StorageHelper
) {
    val uiState by viewModel.uiState.collectAsState()
    val isRescanning by viewModel.isRescanning.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    
    var showReadingModeDialog by remember { mutableStateOf(false) }
    var showGridSizeDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showClearHistoryDialog by remember { mutableStateOf(false) }
    var showClearWatchingHistoryDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.snackbarMessage.collectLatest { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            storageHelper.persistUriPermission(it)
            if (storageHelper.validateAndCreateStructure(it.toString())) {
                viewModel.updateRootFolder(it.toString())
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (val state = uiState) {
                is SettingsUiState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                is SettingsUiState.Error -> {
                    Text(
                        text = state.message,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                is SettingsUiState.Success -> {
                    SettingsContent(
                        settings = state.settings,
                        comicCount = state.comicCount,
                        bookmarkCount = state.bookmarkCount,
                        playlistCount = state.playlistCount,
                        videoStatistics = state.videoStatistics,
                        isRescanning = isRescanning,
                        onReadingModeClick = { showReadingModeDialog = true },
                        onGridSizeClick = { showGridSizeDialog = true },
                        onThemeClick = { showThemeDialog = true },
                        onChangeRootClick = { folderPickerLauncher.launch(null) },
                        onRescanClick = { viewModel.rescanLibrary() },
                        onClearHistoryClick = { showClearHistoryDialog = true },
                        onClearWatchingHistoryClick = { showClearWatchingHistoryDialog = true }
                    )

                    if (showReadingModeDialog) {
                        ReadingModeDialog(
                            currentMode = state.settings.readingMode,
                            onModeSelected = {
                                viewModel.updateReadingMode(it)
                                showReadingModeDialog = false
                            },
                            onDismiss = { showReadingModeDialog = false }
                        )
                    }

                    if (showGridSizeDialog) {
                        GridSizeDialog(
                            currentSize = state.settings.gridSize,
                            onSizeSelected = {
                                viewModel.updateGridSize(it)
                                showGridSizeDialog = false
                            },
                            onDismiss = { showGridSizeDialog = false }
                        )
                    }

                    if (showThemeDialog) {
                        ThemeSelectorDialog(
                            isOled = state.settings.oledBlack,
                            onThemeSelected = { oled ->
                                viewModel.updateOledBlack(oled)
                                showThemeDialog = false
                            },
                            onDismiss = { showThemeDialog = false }
                        )
                    }

                    if (showClearHistoryDialog) {
                        ClearHistoryDialog(
                            onConfirm = {
                                viewModel.clearReadingHistory()
                                showClearHistoryDialog = false
                            },
                            onDismiss = { showClearHistoryDialog = false }
                        )
                    }

                    if (showClearWatchingHistoryDialog) {
                        ClearWatchingHistoryDialog(
                            onConfirm = {
                                viewModel.clearWatchingHistory()
                                showClearWatchingHistoryDialog = false
                            },
                            onDismiss = { showClearWatchingHistoryDialog = false }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsContent(
    settings: SettingsEntity,
    comicCount: Int,
    bookmarkCount: Int,
    playlistCount: Int,
    videoStatistics: VideoStatistics,
    isRescanning: Boolean,
    onReadingModeClick: () -> Unit,
    onGridSizeClick: () -> Unit,
    onThemeClick: () -> Unit,
    onChangeRootClick: () -> Unit,
    onRescanClick: () -> Unit,
    onClearHistoryClick: () -> Unit,
    onClearWatchingHistoryClick: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { SettingsSectionHeader("Reader") }
        item {
            SettingsItem(
                icon = Icons.AutoMirrored.Filled.List,
                title = "Reading Mode",
                subtitle = settings.readingMode,
                onClick = onReadingModeClick
            )
        }

        item { SettingsSectionHeader("Library") }
        item {
            SettingsItem(
                icon = Icons.Default.Settings,
                title = "Grid Size",
                subtitle = "${settings.gridSize} Columns",
                onClick = onGridSizeClick
            )
        }

        item { SettingsSectionHeader("Storage") }
        item {
            val rootUri = settings.rootFolderUri
            val displayUri = if (rootUri != null) {
                try {
                    Uri.parse(rootUri).path?.substringAfterLast(':') ?: "Configured"
                } catch (e: Exception) { "Configured" }
            } else "Not Set"
            
            SettingsItem(
                icon = Icons.Default.Home,
                title = "Change Root Folder",
                subtitle = "Current: $displayUri",
                onClick = onChangeRootClick
            )
        }
        item {
            SettingsItem(
                icon = Icons.Default.Refresh,
                title = "Rescan Library",
                subtitle = if (isRescanning) "Scanning..." else "Check for new comics",
                onClick = onRescanClick,
                enabled = !isRescanning
            )
        }

        item { SettingsSectionHeader("Data Management") }
        item {
            SettingsItem(
                icon = Icons.Default.Delete,
                title = "Clear Reading History",
                subtitle = "Remove all progress and continue reading",
                onClick = onClearHistoryClick
            )
        }

        item {
            SettingsItem(
                icon = Icons.Default.DeleteSweep,
                title = "Clear Watching History",
                subtitle = "Remove all video progress and history",
                onClick = onClearWatchingHistoryClick
            )
        }
        
        item { SettingsSectionHeader("Statistics") }
        item {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 2.dp
            ) {
                Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                    Text("General", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(8.dp))
                    StatRow(label = "Total Bookmarks", value = bookmarkCount.toString())
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
                    StatRow(label = "Total Playlists", value = playlistCount.toString())
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Comic Statistics", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(8.dp))
                    StatRow(label = "Total Comics", value = comicCount.toString())
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Video Statistics", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(8.dp))
                    StatRow(label = "Total Videos", value = videoStatistics.totalVideos.toString())
                }
            }
        }

        item { SettingsSectionHeader("Appearance") }
        item {
            SettingsItem(
                icon = Icons.Default.Build,
                title = "Theme",
                subtitle = if (settings.oledBlack) "OLED Black" else "Dark Mode",
                onClick = onThemeClick
            )
        }

        item { SettingsSectionHeader("About") }
        item {
            SettingsItem(
                icon = Icons.Default.Info,
                title = "App Name",
                subtitle = "Kitsune"
            )
        }
        item {
            SettingsItem(
                icon = Icons.Default.Build,
                title = "Version",
                subtitle = "1.0.0 (MVP)"
            )
        }
    }
}

@Composable
fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Text(text = value, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit = {},
    enabled: Boolean = true
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp,
        modifier = Modifier.clickable(enabled = enabled) { onClick() }
    ) {
        ListItem(
            headlineContent = { Text(title, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)) },
            supportingContent = { Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant) },
            leadingContent = {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                )
            },
            colors = ListItemDefaults.colors(
                containerColor = Color.Transparent
            )
        )
    }
}

@Composable
fun ReadingModeDialog(
    currentMode: String,
    onModeSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val modes = listOf("Vertical", "LTR", "RTL")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Reading Mode") },
        text = {
            Column {
                modes.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onModeSelected(mode) }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = mode == currentMode, onClick = null)
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(mode)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
fun GridSizeDialog(
    currentSize: Int,
    onSizeSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val sizes = listOf(2, 3, 4)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Grid Size") },
        text = {
            Column {
                sizes.forEach { size ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSizeSelected(size) }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = size == currentSize, onClick = null)
                        Spacer(modifier = Modifier.width(16.dp))
                        Text("$size Columns")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
fun ThemeSelectorDialog(
    isOled: Boolean,
    onThemeSelected: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Theme") },
        text = {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onThemeSelected(false) }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = !isOled, onClick = null)
                    Spacer(modifier = Modifier.width(16.dp))
                    Text("Dark Mode")
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onThemeSelected(true) }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = isOled, onClick = null)
                    Spacer(modifier = Modifier.width(16.dp))
                    Text("OLED Black")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
fun ClearHistoryDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clear Reading History?") },
        text = {
            Text("This will permanently remove all reading progress and Continue Reading history.\n\nBookmarks and Playlists will NOT be deleted.")
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Clear")
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
fun ClearWatchingHistoryDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clear Watching History?") },
        text = {
            Text("Seluruh riwayat menonton video akan dihapus.\nProgress video tidak dapat dikembalikan.\n\nBookmarks and Playlists will NOT be deleted.")
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
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
