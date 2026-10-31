package com.example.kitsune.ui.screens.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ViewArray
import androidx.compose.material.icons.filled.ViewStream
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ReaderDirectionToggleSelector(
                                currentDirection = state.readingDirection,
                                onDirectionSelected = { newDirection ->
                                    viewModel.setReadingDirection(newDirection)
                                }
                            )
                            ReaderModeToggleSelector(
                                currentMode = state.readerMode,
                                onModeSelected = { newMode ->
                                    viewModel.setReaderMode(newMode)
                                }
                            )
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
                    if (state.readerMode == "vertical_continuous") {
                        ContinuousVerticalView(
                            chapterPageIndex = state.pageIndex,
                            initialPageIndex = state.currentPageIndex,
                            initialScrollOffset = state.scrollOffset,
                            viewModel = viewModel,
                            onPositionChanged = { firstVisible, offset ->
                                viewModel.setCurrentPageIndexAndOffset(firstVisible, offset)
                            }
                        )
                    } else {
                        SinglePageView(
                            currentPageIndex = state.currentPageIndex,
                            readingDirection = state.readingDirection,
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
private fun ReaderDirectionToggleSelector(
    currentDirection: String,
    onDirectionSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    FilterChip(
        selected = currentDirection == "rtl",
        onClick = {
            val nextDir = if (currentDirection == "rtl") "ltr" else "rtl"
            onDirectionSelected(nextDir)
        },
        label = { Text(if (currentDirection == "rtl") "RTL" else "LTR") },
        modifier = modifier.padding(end = 4.dp)
    )
}

@Composable
private fun ReaderModeToggleSelector(
    currentMode: String,
    onModeSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilterChip(
            selected = currentMode == "single_page",
            onClick = { onModeSelected("single_page") },
            label = { Text("Single") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.ViewArray,
                    contentDescription = "Single Page Mode"
                )
            },
            modifier = Modifier.padding(end = 4.dp)
        )
        FilterChip(
            selected = currentMode == "vertical_continuous",
            onClick = { onModeSelected("vertical_continuous") },
            label = { Text("Vertical") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.ViewStream,
                    contentDescription = "Continuous Vertical Mode"
                )
            }
        )
    }
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
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
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
