package com.example.kitsune.ui.screens.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showChapterSelector by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    if (showChapterSelector && uiState is ReaderUiState.Success) {
        val state = uiState as ReaderUiState.Success
        ChapterSelectorDialog(
            chapters = state.chapters,
            currentChapterId = state.chapterId,
            onChapterSelect = { targetChapterId ->
                viewModel.navigateToChapter(targetChapterId)
                showChapterSelector = false
            },
            onDismiss = { showChapterSelector = false }
        )
    }

    if (showSettingsDialog && uiState is ReaderUiState.Success) {
        val state = uiState as ReaderUiState.Success
        ReaderSettingsDialog(
            currentMode = state.effectiveReadingMode,
            currentFit = state.pageFit,
            onModeSelected = { newMode -> viewModel.setReadingMode(newMode) },
            onFitSelected = { newFit -> viewModel.setPageFit(newFit) },
            onDismiss = { showSettingsDialog = false }
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    val state = uiState
                    if (state is ReaderUiState.Success) {
                        Text("Halaman ${state.currentPageIndex + 1} / ${state.pageIndex.totalPages}")
                    } else {
                        Text("Reader")
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Kembali ke Detail Komik"
                        )
                    }
                },
                actions = {
                    val state = uiState
                    if (state is ReaderUiState.Success) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = { viewModel.navigateToPreviousChapter() },
                                enabled = state.hasPreviousChapter
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.NavigateBefore,
                                    contentDescription = "Chapter Sebelumnya"
                                )
                            }
                            IconButton(
                                onClick = { showChapterSelector = true },
                                enabled = state.chapters.isNotEmpty()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Menu,
                                    contentDescription = "Pilih Chapter"
                                )
                            }
                            IconButton(
                                onClick = { viewModel.navigateToNextChapter() },
                                enabled = state.hasNextChapter
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.NavigateNext,
                                    contentDescription = "Chapter Berikutnya"
                                )
                            }
                            IconButton(
                                onClick = { showSettingsDialog = true }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Tune,
                                    contentDescription = "Pengaturan Reader"
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
            when (val state = uiState) {
                is ReaderUiState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                is ReaderUiState.Success -> {
                    if (state.effectiveReadingMode == "vertical") {
                        ContinuousVerticalView(
                            chapterPageIndex = state.pageIndex,
                            initialPageIndex = state.currentPageIndex,
                            initialScrollOffset = state.scrollOffset,
                            pageFit = state.pageFit,
                            viewModel = viewModel,
                            onPositionChanged = { firstVisible, offset ->
                                viewModel.setCurrentPageIndexAndOffset(firstVisible, offset)
                            }
                        )
                    } else {
                        SinglePageView(
                            currentPageIndex = state.currentPageIndex,
                            readingDirection = state.effectiveReadingMode,
                            pageFit = state.pageFit,
                            viewModel = viewModel
                        )
                    }
                }
                is ReaderUiState.Error -> {
                    ReaderErrorView(
                        errorState = state,
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
    currentFit: String,
    onModeSelected: (String) -> Unit,
    onFitSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pengaturan Reader") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column {
                    Text(
                        text = "Mode Baca",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("rtl" to "RTL", "ltr" to "LTR", "vertical" to "Vertical").forEach { (mode, label) ->
                            FilterChip(
                                selected = currentMode == mode,
                                onClick = { onModeSelected(mode) },
                                label = { Text(label) }
                            )
                        }
                    }
                }

                Column {
                    Text(
                        text = "Tampilan Halaman (Fit)",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("fit_screen" to "Fit", "fit_width" to "Width", "fit_height" to "Height").forEach { (fit, label) ->
                            FilterChip(
                                selected = currentFit == fit,
                                onClick = { onFitSelected(fit) },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Tutup")
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
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
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
                text = when (errorState.errorType) {
                    ReaderErrorType.PERMISSION_DENIED -> "Izin Akses Terputus"
                    ReaderErrorType.ROOT_NOT_CONFIGURED -> "Root SAF Belum Dipilih"
                    ReaderErrorType.CORRUPT_ARCHIVE -> "Arsip CBZ Rusak"
                    ReaderErrorType.UNSUPPORTED_ARCHIVE -> "Format Tidak Didukung / Melebihi Batas"
                    ReaderErrorType.NO_IMAGES -> "Tidak Ada Gambar"
                    ReaderErrorType.CHAPTER_MISSING -> "Chapter Tidak Ditemukan"
                    else -> "Gagal Membuka Reader"
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
                            Text("Atur Root SAF")
                        }
                    }
                    ReaderErrorType.STORAGE_ERROR -> {
                        Button(onClick = onRetryClick) {
                            Text("Coba Lagi")
                        }
                    }
                    else -> {}
                }
                Button(onClick = onBackClick) {
                    Text("Kembali")
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
        title = { Text("Pilih Chapter") },
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
                                    text = "Aktif",
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
                Text("Tutup")
            }
        }
    )
}

private fun String.removeSuffixCaseInsensitive(suffix: String): String {
    return if (this.endsWith(suffix, ignoreCase = true)) {
        this.substring(0, this.length - suffix.length)
    } else {
        this
    }
}
