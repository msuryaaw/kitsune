package com.kitsune.app.ui.history

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kitsune.app.core.HistoryTimeGroup
import com.kitsune.app.domain.model.LastReadComic
import com.kitsune.app.domain.model.LastWatchedVideo
import com.kitsune.app.ui.components.media.ContinueWatchingCard
import com.kitsune.app.ui.components.media.LastReadCard
import com.kitsune.app.ui.library.EmptyLibraryState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadHistoryScreen(
    viewModel: ReadHistoryViewModel,
    onContinueReading: (LastReadComic) -> Unit,
    onBackClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Read History") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (val state = uiState) {
                is ReadHistoryUiState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                is ReadHistoryUiState.Error -> {
                    Text(
                        text = state.message,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                is ReadHistoryUiState.Success -> {
                    if (state.history.isEmpty()) {
                        EmptyLibraryState(
                            message = "No reading history found",
                            icon = Icons.Default.History
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            state.groupedHistory.forEach { (group, items) ->
                                item(key = "header_read_${group.name}") {
                                    Text(
                                        text = group.label,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                                    )
                                }
                                items(
                                    items = items,
                                    key = { "item_read_${it.comic.relativePath}" }
                                ) { item ->
                                    LastReadCard(
                                        lastRead = item,
                                        onClick = { onContinueReading(item) },
                                        onDelete = { viewModel.deleteReadHistory(item.comic.relativePath) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchHistoryScreen(
    viewModel: WatchHistoryViewModel,
    onContinueWatching: (LastWatchedVideo) -> Unit,
    onBackClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Watch History") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (val state = uiState) {
                is WatchHistoryUiState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                is WatchHistoryUiState.Error -> {
                    Text(
                        text = state.message,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                is WatchHistoryUiState.Success -> {
                    if (state.history.isEmpty()) {
                        EmptyLibraryState(
                            message = "No watching history found",
                            icon = Icons.Default.History
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            state.groupedHistory.forEach { (group, items) ->
                                item(key = "header_watch_${group.name}") {
                                    Text(
                                        text = group.label,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                                    )
                                }
                                items(
                                    items = items,
                                    key = { "item_watch_${it.video.relativePath}" }
                                ) { item ->
                                    ContinueWatchingCard(
                                        lastWatched = item,
                                        onClick = { onContinueWatching(item) },
                                        onDelete = { viewModel.deleteWatchHistory(item.video.relativePath) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
