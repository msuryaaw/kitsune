package com.example.kitsune.ui.screens.history

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.example.kitsune.data.CoverCacheResult
import com.example.kitsune.data.entity.ComicEntity
import com.example.kitsune.data.model.ComicWithProgress
import com.example.kitsune.ui.components.KitsuneEmptyState
import com.example.kitsune.ui.components.KitsunePrimaryButton
import com.example.kitsune.ui.screens.library.CoverUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    onComicClick: (Long) -> Unit,
    onContinueReadingClick: (Long) -> Unit,
    onNavigateToSettings: () -> Unit,
    onOpenLibraryClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    onBackClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is HistoryEvent.NavigateToComicDetail -> {
                    onComicClick(event.comicId)
                }
                is HistoryEvent.NavigateToReader -> {
                    onContinueReadingClick(event.chapterId)
                }
                is HistoryEvent.ShowFeedback -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        if (onBackClick != null) {
            com.example.kitsune.ui.components.KitsuneBackToolbar(
                title = "History",
                onBack = onBackClick
            )
        }
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) {
            when (val state = uiState) {
                is HistoryUiState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                is HistoryUiState.RootNotConfigured -> {
                    HistoryRootNotConfiguredView(
                        onNavigateToSettings = onNavigateToSettings,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                is HistoryUiState.PermissionDenied -> {
                    HistoryPermissionDeniedView(
                        message = state.message,
                        onNavigateToSettings = onNavigateToSettings,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                is HistoryUiState.Empty -> {
                    HistoryEmptyView(
                        onOpenLibraryClick = onOpenLibraryClick,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                is HistoryUiState.Success -> {
                    HistorySuccessView(
                        groups = state.groups,
                        viewModel = viewModel,
                        onItemClick = { item -> viewModel.onComicClicked(item.comic.id) },
                        onContinueClick = { item -> viewModel.onContinueReadingClicked(item) }
                    )
                }
                is HistoryUiState.Error -> {
                    HistoryErrorView(
                        message = state.message,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
        }
    }
}

@Composable
private fun HistorySuccessView(
    groups: List<HistoryGroup>,
    viewModel: HistoryViewModel,
    onItemClick: (ComicWithProgress) -> Unit,
    onContinueClick: (ComicWithProgress) -> Unit,
    modifier: Modifier = Modifier
) {
    var itemToDelete by remember { mutableStateOf<ComicWithProgress?>(null) }

    itemToDelete?.let { item ->
        SingleDeleteConfirmationDialog(
            comicTitle = item.comic.title,
            onConfirm = {
                viewModel.clearHistoryForComic(item.comic.id)
                itemToDelete = null
            },
            onDismiss = { itemToDelete = null }
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column {
                Text(
                    text = "History",
                    style = MaterialTheme.typography.displayLarge
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Right where you left off.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        groups.forEach { group ->
            item(key = "header_${group.header.name}") {
                Text(
                    text = group.header.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            items(
                items = group.items,
                key = { "${group.header.name}_${it.comic.id}" }
            ) { item ->
                HistoryItemCard(
                    item = item,
                    viewModel = viewModel,
                    onClick = { onItemClick(item) },
                    onContinueClick = { onContinueClick(item) },
                    onDeleteClick = { itemToDelete = item }
                )
            }
        }
    }
}

@Composable
private fun HistoryItemCard(
    item: ComicWithProgress,
    viewModel: HistoryViewModel,
    onClick: () -> Unit,
    onContinueClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .width(104.dp)
                        .aspectRatio(0.70f)
                ) {
                    HistoryCoverImage(
                        comic = item.comic,
                        viewModel = viewModel
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = item.comic.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = onDeleteClick) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = "Delete Comic History",
                                tint = MaterialTheme.colorScheme.outline
                            )
                        }
                    }

                    val timestamp = item.comic.lastReadAt ?: item.progress?.updatedAt
                    if (timestamp != null && timestamp > 0L) {
                        val timeFormat = DateFormat.getTimeInstance(DateFormat.SHORT)
                        Text(
                            text = timeFormat.format(Date(timestamp)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    item.progress?.let { progress ->
                        val chapterName = "Chapter ${progress.lastReadChapterNumber}"
                        val pageNumber = progress.pageIndex + 1
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "$chapterName • Page $pageNumber",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            KitsunePrimaryButton(
                text = "Continue Reading",
                onClick = onContinueClick,
                leadingIcon = Icons.Default.PlayArrow
            )
        }
    }
}

@Composable
private fun SingleDeleteConfirmationDialog(
    comicTitle: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete Comic History") },
        text = {
            Text("Are you sure you want to remove \"$comicTitle\" from your reading history? Your reading progress in Library will remain intact.")
        },
        confirmButton = {
            Button(onClick = onConfirm) {
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

@Composable
private fun HistoryCoverImage(
    comic: ComicEntity,
    viewModel: HistoryViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coverState by produceState<CoverUiState>(
        initialValue = CoverUiState.Loading,
        key1 = comic.id,
        key2 = comic.coverEntryPath,
        key3 = comic.lastScannedAt
    ) {
        var acquiredFile: File? = null
        try {
            val cacheResult = viewModel.getCoverFile(comic.id)
            val file = when (cacheResult) {
                is CoverCacheResult.Hit -> cacheResult.file
                is CoverCacheResult.Success -> cacheResult.file
                else -> null
            }

            if (file == null || !file.isFile || !file.exists()) {
                value = CoverUiState.Unavailable
                return@produceState
            }

            acquiredFile = file

            val request = ImageRequest.Builder(context)
                .data(file)
                .size(300, 420)
                .build()

            val imageLoader = context.imageLoader
            val result = imageLoader.execute(request)

            if (result is SuccessResult) {
                val drawable = result.drawable
                val bitmap = when (drawable) {
                    is BitmapDrawable -> drawable.bitmap
                    else -> {
                        val width = drawable.intrinsicWidth.coerceAtLeast(1)
                        val height = drawable.intrinsicHeight.coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bmp)
                        drawable.setBounds(0, 0, width, height)
                        drawable.draw(canvas)
                        bmp
                    }
                }
                value = CoverUiState.Loaded(bitmap.asImageBitmap())
            } else {
                value = CoverUiState.Error
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            value = CoverUiState.Error
        } finally {
            acquiredFile?.let { file ->
                withContext(NonCancellable) {
                    viewModel.releaseCoverFile(file)
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(0.70f)
            .clip(RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        when (val state = coverState) {
            is CoverUiState.Loaded -> {
                Image(
                    painter = BitmapPainter(state.imageBitmap),
                    contentDescription = "Cover for ${comic.title}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            else -> {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.MenuBook,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryEmptyView(
    onOpenLibraryClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        KitsuneEmptyState(
            icon = Icons.AutoMirrored.Filled.MenuBook,
            title = "Every story starts somewhere",
            message = "The manga you read will appear here, with your last page saved. Start a chapter from your library.",
            actionText = "Open Library",
            onAction = onOpenLibraryClick,
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Composable
private fun HistoryRootNotConfiguredView(
    onNavigateToSettings: () -> Unit,
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
                imageVector = Icons.Default.FolderOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            Text(
                text = "Root Folder Not Configured",
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Select a 'Kitsune' folder in device storage to load reading history.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onNavigateToSettings) {
                Text("Open Settings")
            }
        }
    }
}

@Composable
private fun HistoryPermissionDeniedView(
    message: String,
    onNavigateToSettings: () -> Unit,
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
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            Text(
                text = "Folder Access Disconnected",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Folder read permission lost: $message",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onNavigateToSettings) {
                Text("Recheck Access")
            }
        }
    }
}

@Composable
private fun HistoryErrorView(
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
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            Text(
                text = "Failed to Load History",
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
