package com.kitsune.app.ui.comicdetail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kitsune.app.core.DateUtils
import com.kitsune.app.database.entity.ReadingProgressEntity
import com.kitsune.app.domain.model.Chapter
import com.kitsune.app.domain.model.Comic
import com.kitsune.app.ui.components.media.CollectionDialogItem
import com.kitsune.app.ui.components.media.MediaMultiSelectDialog
import com.kitsune.app.ui.components.media.MediaSingleSelectDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComicDetailScreen(
    viewModel: ComicDetailViewModel,
    onChapterClick: (Chapter) -> Unit,
    onContinueClick: (ReadingProgressEntity) -> Unit,
    onBackClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val availableBookmarks by viewModel.availableBookmarks.collectAsState()
    val availablePlaylists by viewModel.availablePlaylists.collectAsState()
    val isBookmarked by viewModel.isBookmarked.collectAsState()
    
    var showBookmarkDialog by remember { mutableStateOf(false) }
    var showPlaylistDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Comic Detail") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showBookmarkDialog = true }) {
                        Icon(
                            imageVector = Icons.Filled.Star,
                            contentDescription = "Bookmark",
                            tint = if (isBookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(onClick = { showPlaylistDialog = true }) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Add to Playlist")
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
                is ComicDetailUiState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                is ComicDetailUiState.Error -> {
                    Text(
                        text = state.message,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                is ComicDetailUiState.Success -> {
                    ComicDetailContent(
                        comic = state.comic,
                        chapters = state.chapters,
                        progress = state.progress,
                        onChapterClick = onChapterClick,
                        onContinueClick = onContinueClick
                    )
                }
            }
        }
    }

    if (showBookmarkDialog) {
        val dialogItems = remember(availableBookmarks) {
            availableBookmarks.map { 
                CollectionDialogItem(it.bookmark.bookmark.id, it.bookmark.bookmark.name, it.isMember) 
            }
        }
        MediaMultiSelectDialog(
            title = "Manage Bookmarks",
            items = dialogItems,
            onToggle = { id, isMember ->
                viewModel.toggleBookmarkMembership(id, isMember)
            },
            onDismiss = { showBookmarkDialog = false }
        )
    }

    if (showPlaylistDialog) {
        val dialogItems = remember(availablePlaylists) {
            availablePlaylists.map { 
                CollectionDialogItem(it.playlist.id, it.playlist.name) 
            }
        }
        MediaSingleSelectDialog(
            title = "Add to Playlist",
            items = dialogItems,
            onSelect = { id ->
                viewModel.addComicToPlaylist(id)
                showPlaylistDialog = false
            },
            onDismiss = { showPlaylistDialog = false }
        )
    }
}

@Composable
fun ComicDetailContent(
    comic: Comic,
    chapters: List<Chapter>,
    progress: ReadingProgressEntity?,
    onChapterClick: (Chapter) -> Unit,
    onContinueClick: (ReadingProgressEntity) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp)
    ) {
        item {
            ComicHeader(comic = comic)
            
            if (progress != null) {
                Spacer(modifier = Modifier.height(16.dp))
                ContinueReadingCard(progress = progress, onClick = { onContinueClick(progress) })
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Chapters (${chapters.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (chapters.isEmpty()) {
            item {
                Text(
                    text = "No chapters found",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            }
        } else {
            items(chapters) { chapter ->
                ChapterItem(chapter = chapter, onClick = { onChapterClick(chapter) })
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
fun ComicHeader(comic: Comic) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Card(
            modifier = Modifier
                .width(120.dp)
                .aspectRatio(5f / 7f),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            AsyncImage(
                model = comic.coverUri,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        
        Spacer(modifier = Modifier.width(16.dp))
        
        Column {
            Text(
                text = comic.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "Local Library",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = comic.relativePath,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun ContinueReadingCard(
    progress: ReadingProgressEntity,
    onClick: () -> Unit
) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "Continue Reading",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = progress.chapterRelativePath.substringAfterLast('/'),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Page ${progress.pageNumber} of ${progress.totalPages}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
fun ChapterItem(
    chapter: Chapter,
    onClick: () -> Unit
) {
    val formattedDate = remember(chapter.lastModified) {
        DateUtils.formatTimestamp(chapter.lastModified)
    }

    ListItem(
        headlineContent = { Text(chapter.name) },
        supportingContent = {
            if (formattedDate != null) {
                Text(
                    text = formattedDate,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = "CBZ File",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)
    )
}
