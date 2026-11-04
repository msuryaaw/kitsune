package com.example.kitsune.ui.screens.reader

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    viewModel: ReaderViewModel,
    onBackClick: () -> Unit,
    onSettingsClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showChapterSelector by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    // Keep Screen Awake DisposableEffect
    val state = uiState
    if (state is ReaderUiState.Success && state.keepScreenAwake) {
        val activity = context as? Activity
        DisposableEffect(activity) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            onDispose {
                activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    if (showChapterSelector && uiState is ReaderUiState.Success) {
        val successState = uiState as ReaderUiState.Success
        ChapterSelectorDialog(
            chapters = successState.chapters,
            currentChapterId = successState.chapterId,
            onChapterSelect = { targetChapterId ->
                viewModel.navigateToChapter(targetChapterId)
                showChapterSelector = false
            },
            onDismiss = { showChapterSelector = false }
        )
    }

    if (showSettingsDialog && uiState is ReaderUiState.Success) {
        val successState = uiState as ReaderUiState.Success
        ReaderSettingsDialog(
            currentMode = successState.readerMode,
            currentDirection = successState.readingDirection,
            currentFit = successState.pageFit,
            keepScreenAwake = successState.keepScreenAwake,
            onModeSelected = { newMode -> viewModel.setReaderMode(newMode) },
            onDirectionSelected = { newDirection -> viewModel.setReadingDirection(newDirection) },
            onFitSelected = { newFit -> viewModel.setPageFit(newFit) },
            onKeepScreenAwakeChanged = { awake -> viewModel.setKeepScreenAwake(awake) },
            onDismiss = { showSettingsDialog = false }
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    val currState = uiState
                    if (currState is ReaderUiState.Success) {
                        Text("Page ${currState.currentPageIndex + 1} / ${currState.pageIndex.totalPages}")
                    } else {
                        Text("Reader")
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0),
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    val currState = uiState
                    if (currState is ReaderUiState.Success) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = { viewModel.navigateToPreviousChapter() },
                                enabled = currState.hasPreviousChapter
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.NavigateBefore,
                                    contentDescription = "Previous Chapter"
                                )
                            }
                            IconButton(
                                onClick = { showChapterSelector = true },
                                enabled = currState.chapters.isNotEmpty()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FormatListBulleted,
                                    contentDescription = "Select Chapter"
                                )
                            }
                            IconButton(
                                onClick = { viewModel.navigateToNextChapter() },
                                enabled = currState.hasNextChapter
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.NavigateNext,
                                    contentDescription = "Next Chapter"
                                )
                            }
                            IconButton(
                                onClick = { showSettingsDialog = true }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = "Reader Settings"
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (val currentState = uiState) {
                is ReaderUiState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                is ReaderUiState.Success -> {
                    if (currentState.readerMode == "vertical_continuous" || currentState.readerMode == "vertical") {
                        ContinuousVerticalView(
                            chapterPageIndex = currentState.pageIndex,
                            initialPageIndex = currentState.currentPageIndex,
                            initialScrollOffset = currentState.scrollOffset,
                            pageFit = currentState.pageFit,
                            viewModel = viewModel,
                            onPositionChanged = { firstVisible, offset ->
                                viewModel.setCurrentPageIndexAndOffset(firstVisible, offset)
                            }
                        )
                    } else {
                        SinglePageView(
                            currentPageIndex = currentState.currentPageIndex,
                            readingDirection = currentState.readingDirection,
                            pageFit = currentState.pageFit,
                            viewModel = viewModel
                        )
                    }
                }
                is ReaderUiState.Error -> {
                    ReaderErrorView(
                        errorState = currentState,
                        onBackClick = onBackClick,
                        onRetryClick = { viewModel.retryLoadChapter() },
                        onSettingsClick = onSettingsClick ?: onBackClick,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
        }
    }
}

@Composable
private fun ReaderSettingsDialog(
    currentMode: String,
    currentDirection: String,
    currentFit: String,
    keepScreenAwake: Boolean,
    onModeSelected: (String) -> Unit,
    onDirectionSelected: (String) -> Unit,
    onFitSelected: (String) -> Unit,
    onKeepScreenAwakeChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reader Settings") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Reading Mode
                Column {
                    Text(
                        text = "Reading Mode",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = currentMode == "single_page",
                            onClick = { onModeSelected("single_page") },
                            label = { Text("Single Page") }
                        )
                        FilterChip(
                            selected = currentMode == "vertical_continuous" || currentMode == "vertical",
                            onClick = { onModeSelected("vertical_continuous") },
                            label = { Text("Vertical Continuous") }
                        )
                    }
                }

                // Reading Direction
                Column {
                    Text(
                        text = "Reading Direction",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = currentDirection == "ltr",
                            onClick = { onDirectionSelected("ltr") },
                            label = { Text("LTR") }
                        )
                        FilterChip(
                            selected = currentDirection == "rtl",
                            onClick = { onDirectionSelected("rtl") },
                            label = { Text("RTL") }
                        )
                    }
                }

                // Page Fit
                Column {
                    Text(
                        text = "Page Fit",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("fit_screen" to "Fit Screen", "fit_width" to "Fit Width", "fit_height" to "Fit Height").forEach { (fit, label) ->
                            FilterChip(
                                selected = currentFit == fit,
                                onClick = { onFitSelected(fit) },
                                label = { Text(label) }
                            )
                        }
                    }
                }

                // Keep Screen Awake
                Column {
                    Text(
                        text = "Keep Screen Awake",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = keepScreenAwake,
                            onClick = { onKeepScreenAwakeChanged(!keepScreenAwake) },
                            label = { Text(if (keepScreenAwake) "Enabled" else "Disabled") }
                        )
                    }
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
private fun ReaderErrorView(
    errorState: ReaderUiState.Error,
    onBackClick: () -> Unit,
    onRetryClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.padding(24.dp),
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
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = when (errorState.errorType) {
                    ReaderErrorType.PERMISSION_DENIED -> "Access Permission Lost"
                    ReaderErrorType.ROOT_NOT_CONFIGURED -> "Root SAF Not Configured"
                    ReaderErrorType.CORRUPT_ARCHIVE -> "Corrupt CBZ Archive"
                    ReaderErrorType.UNSUPPORTED_ARCHIVE -> "Unsupported Format / Limit Exceeded"
                    ReaderErrorType.NO_IMAGES -> "No Image Pages Found"
                    ReaderErrorType.CHAPTER_MISSING -> "Chapter Not Found"
                    else -> "Failed to Open Reader"
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = errorState.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (errorState.errorType) {
                    ReaderErrorType.PERMISSION_DENIED, ReaderErrorType.ROOT_NOT_CONFIGURED -> {
                        Button(onClick = onSettingsClick) {
                            Text("Configure Root SAF")
                        }
                    }
                    ReaderErrorType.STORAGE_ERROR -> {
                        Button(onClick = onRetryClick) {
                            Text("Retry")
                        }
                    }
                    else -> {}
                }
                Button(onClick = onBackClick) {
                    Text("Back")
                }
            }
        }
    }
}

@Composable
private fun ChapterSelectorDialog(
    chapters: List<com.example.kitsune.data.entity.ChapterEntity>,
    currentChapterId: Long,
    onChapterSelect: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Chapter") },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(chapters, key = { it.id }) { chapter ->
                    val isSelected = chapter.id == currentChapterId
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onChapterSelect(chapter.id)
                            },
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = chapter.displayName.removeSuffixCaseInsensitive(".cbz"),
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                            )
                            if (isSelected) {
                                Text(
                                    text = "Active",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
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

fun String.removeSuffixCaseInsensitive(suffix: String): String {
    return if (this.endsWith(suffix, ignoreCase = true)) {
        this.substring(0, this.length - suffix.length)
    } else {
        this
    }
}
