package com.kitsune.app.ui.video

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kitsune.app.core.DateUtils
import com.kitsune.app.domain.model.Episode
import com.kitsune.app.domain.model.Video
import com.kitsune.app.ui.components.media.CollectionDialogItem
import com.kitsune.app.ui.components.media.MediaMultiSelectDialog
import com.kitsune.app.ui.components.media.MediaSingleSelectDialog

/**
 * Screen untuk menampilkan detail video dan daftar episode dengan indikator progres.
 * REVISION 7.7.4: Integrasi Finished Badge pada EpisodeItem.
 * REVISION 7.8.10: Integrasi Bookmark dan Playlist Actions.
 * REVISION 7.9.1: Migrated to Unified Collection Dialogs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoDetailScreen(
    viewModel: VideoDetailViewModel,
    onEpisodeClick: (Episode) -> Unit,
    onBackClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val isBookmarked by viewModel.isBookmarked.collectAsState()
    val availableBookmarks by viewModel.availableBookmarks.collectAsState()
    val availablePlaylists by viewModel.availablePlaylists.collectAsState()

    var showBookmarkDialog by remember { mutableStateOf(false) }
    var showPlaylistDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Video Detail") },
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
                            tint = if (isBookmarked) MaterialTheme.colorScheme.primary else Color.White
                        )
                    }
                    IconButton(onClick = { showPlaylistDialog = true }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.List,
                            contentDescription = "Add to Playlist",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
                )
            )
        },
        containerColor = Color.Black
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (val state = uiState) {
                is VideoDetailUiState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                is VideoDetailUiState.Error -> {
                    Text(
                        text = state.message,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                is VideoDetailUiState.Success -> {
                    VideoDetailContent(
                        video = state.video,
                        episodes = state.episodes,
                        onEpisodeClick = onEpisodeClick
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
            onToggle = { bookmarkId, isMember ->
                viewModel.toggleBookmarkMembership(bookmarkId, isMember)
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
            onSelect = { playlistId ->
                viewModel.addVideoToPlaylist(playlistId)
                showPlaylistDialog = false
            },
            onDismiss = { showPlaylistDialog = false }
        )
    }
}

@Composable
fun VideoDetailContent(
    video: Video,
    episodes: List<EpisodeItemState>,
    onEpisodeClick: (Episode) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp)
    ) {
        item {
            VideoHeader(video = video)
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Episodes (${episodes.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (episodes.isEmpty()) {
            item {
                Text(
                    text = "No episodes found",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            }
        } else {
            items(
                items = episodes,
                key = { it.episode.relativePath }
            ) { episodeState ->
                EpisodeItem(
                    state = episodeState, 
                    onClick = { onEpisodeClick(episodeState.episode) }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
            }
        }
    }
}

@Composable
fun VideoHeader(video: Video) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Card(
            modifier = Modifier
                .width(120.dp)
                .aspectRatio(5f / 7f),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
        ) {
            if (video.coverUri != null) {
                AsyncImage(
                    model = video.coverUri,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Movie,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = Color.Gray
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.width(16.dp))
        
        Column {
            Text(
                text = video.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "Video Library",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray
            )
            Text(
                text = "${video.episodeCount} Episodes",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
fun EpisodeItem(
    state: EpisodeItemState,
    onClick: () -> Unit
) {
    val episode = state.episode
    
    ListItem(
        headlineContent = { 
            Text(
                text = episode.name,
                color = if (state.isFinished) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f) else Color.White,
                fontWeight = if (state.watchedPercentage > 0) FontWeight.Bold else FontWeight.Normal
            ) 
        },
        supportingContent = {
            Column {
                if (state.watchedPercentage > 0) {
                    val pos = DateUtils.formatDuration(state.lastPositionMs)
                    val dur = DateUtils.formatDuration(state.durationMs)
                    
                    Text(
                        text = "$pos / $dur",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    LinearProgressIndicator(
                        progress = { state.watchedPercentage },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = Color.White.copy(alpha = 0.1f),
                        strokeCap = StrokeCap.Round
                    )
                } else {
                    Text(
                        text = "Video File",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
            }
        },
        trailingContent = {
            if (state.isFinished) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Finished",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}
