package com.example.kitsune.ui.screens.home

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenLibraryClick: () -> Unit,
    onOpenHistoryClick: () -> Unit,
    onContinueReadingClick: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Box(modifier = modifier.fillMaxSize()) {
        when (val state = uiState) {
            is HomeUiState.Loading -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
            is HomeUiState.Error -> {
                HomeErrorView(
                    message = state.message,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            is HomeUiState.Success -> {
                HomeSuccessContent(
                    state = state,
                    onOpenLibraryClick = onOpenLibraryClick,
                    onOpenHistoryClick = onOpenHistoryClick,
                    onContinueReadingClick = onContinueReadingClick
                )
            }
        }
    }
}

@Composable
private fun HomeSuccessContent(
    state: HomeUiState.Success,
    onOpenLibraryClick: () -> Unit,
    onOpenHistoryClick: () -> Unit,
    onContinueReadingClick: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // App Header
        Column {
            Text(
                text = "Kitsune",
                style = MaterialTheme.typography.displayLarge
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Your offline manga library",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Last Read Section
        Text(
            text = "Last Read",
            style = MaterialTheme.typography.titleLarge
        )

        val lastRead = state.lastReadItem
        if (lastRead == null) {
            HomeLastReadEmptyCard(onOpenLibraryClick = onOpenLibraryClick)
        } else {
            HomeLastReadPopulatedCard(
                lastRead = lastRead,
                onContinueReadingClick = onContinueReadingClick
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Shortcuts Section
        Text(
            text = "Shortcuts",
            style = MaterialTheme.typography.titleLarge
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            HomeShortcutCard(
                title = "Library",
                subtitle = "Browse ${state.libraryCount} local manga",
                icon = Icons.AutoMirrored.Filled.MenuBook,
                onClick = onOpenLibraryClick,
                modifier = Modifier.weight(1f)
            )

            HomeShortcutCard(
                title = "History",
                subtitle = "View ${state.historyCount} recent items",
                icon = Icons.Default.History,
                onClick = onOpenHistoryClick,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun HomeLastReadEmptyCard(
    onOpenLibraryClick: () -> Unit,
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
                imageVector = Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.height(40.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Your first chapter awaits",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Explore your Library to start reading your favorite manga.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onOpenLibraryClick) {
                Text("Open Library")
            }
        }
    }
}

@Composable
private fun HomeLastReadPopulatedCard(
    lastRead: LastReadInfo,
    onContinueReadingClick: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val progress = lastRead.progress
    val pageDisplay = progress.pageIndex + 1
    val totalPages = lastRead.totalPages
    val chapterId = progress.lastReadChapterId ?: 0L

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "PICK UP YOUR STORY",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = lastRead.comic.title,
                style = MaterialTheme.typography.titleLarge
            )
            if (!lastRead.comic.author.isNullOrBlank()) {
                Text(
                    text = lastRead.comic.author ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(12.dp))

            val chapterName = lastRead.chapter?.displayName?.removeSuffix(".cbz")
                ?: "Chapter ${progress.lastReadChapterNumber}"

            Text(
                text = chapterName,
                style = MaterialTheme.typography.bodyMedium
            )

            if (totalPages > 0) {
                Spacer(modifier = Modifier.height(6.dp))
                val progressFloat = (pageDisplay.toFloat() / totalPages.toFloat()).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { progressFloat },
                    modifier = Modifier.fillMaxWidth().height(6.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Page $pageDisplay / $totalPages (${(progressFloat * 100).toInt()}%)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = {
                    if (chapterId > 0L) {
                        onContinueReadingClick(chapterId)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Continue Reading")
            }
        }
    }
}

@Composable
private fun HomeShortcutCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HomeErrorView(
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
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Failed to load Home data",
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
