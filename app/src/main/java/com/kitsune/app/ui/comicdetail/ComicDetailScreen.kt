package com.kitsune.app.ui.comicdetail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditOff
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
import com.kitsune.app.data.metadata.MediaMetadata
import com.kitsune.app.database.entity.ReadingProgressEntity
import com.kitsune.app.domain.model.Chapter
import com.kitsune.app.domain.model.Comic
import com.kitsune.app.ui.components.media.*
import kotlinx.coroutines.flow.collectLatest

/**
 * Screen untuk menampilkan detail komik dan daftar chapter.
 * REVISION 10.4.4: Added LazyColumn keys and optimized performance.
 */
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
    val isBookmarked by viewModel.isBookmarked.collectAsState()
    val isEditMode by viewModel.isEditMode.collectAsState()
    
    val snackbarHostState = remember { SnackbarHostState() }
    var showBookmarkDialog by remember { mutableStateOf(false) }
    var showTagDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.snackbarMessage.collectLatest { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Comic Detail") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.toggleEditMode() }) {
                        Icon(
                            imageVector = if (isEditMode) Icons.Default.EditOff else Icons.Default.Edit,
                            contentDescription = "Toggle Edit Mode",
                            tint = if (isEditMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(onClick = { showBookmarkDialog = true }) {
                        Icon(
                            imageVector = Icons.Filled.Star,
                            contentDescription = "Bookmark",
                            tint = if (isBookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
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
                        metadata = state.metadata,
                        isEditMode = isEditMode,
                        onChapterClick = onChapterClick,
                        onContinueClick = onContinueClick,
                        onAddTagClick = { showTagDialog = true },
                        onRemoveTag = { viewModel.removeTag(it) }
                    )

                    if (showTagDialog) {
                        TagInputDialog(
                            existingTags = state.metadata.tags,
                            onAdd = {
                                viewModel.addTag(it)
                                showTagDialog = false
                            },
                            onDismiss = { showTagDialog = false }
                        )
                    }
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
}

@Composable
fun ComicDetailContent(
    comic: Comic,
    chapters: List<Chapter>,
    progress: ReadingProgressEntity?,
    metadata: MediaMetadata,
    isEditMode: Boolean,
    onChapterClick: (Chapter) -> Unit,
    onContinueClick: (ReadingProgressEntity) -> Unit,
    onAddTagClick: () -> Unit,
    onRemoveTag: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp)
    ) {
        item {
            ComicHeader(comic = comic)
            
            Spacer(modifier = Modifier.height(16.dp))
            MediaTagsSection(
                tags = metadata.tags,
                isEditMode = isEditMode,
                onAddClick = onAddTagClick,
                onRemoveTag = onRemoveTag
            )
            
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
            // OPTIMIZATION: Use key for stable lists
            items(
                items = chapters,
                key = { it.relativePath }
            ) { chapter ->
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
